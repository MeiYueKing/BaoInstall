package bao.omk.installer

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.StatFs
import android.provider.OpenableColumns
import android.util.Log
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.zip.ZipFile

/**
 * 把外部 Uri（content:// 或 file://）统一落成可读的本地文件。
 * 支持 .apk、.apks、.xapk、.apkm 以及普通 zip 里的多个 apk。
 *
 * 所有落盘都带大小上限：入口 Uri 是外部应用给的，
 * 不做限制的话一个 zip 炸弹或无限流就能把手机存储写满。
 */
object ApkResolver {

    private const val INBOX = "inbox"

    /**
     * 没有固定的大小上限，改为按剩余空间动态计算：
     * 安装要占两份空间——我们缓存里一份，pm 的 staging 目录（/data/app/vmdl*）一份，
     * 所以只取可用空间的 40%，剩下的留给 pm。
     * ABS_MAX 只是防止剩余空间极大时无节制复制。
     */
    private const val SPACE_RATIO = 0.4
    private const val ABS_MAX_BYTES = 8L * 1024 * 1024 * 1024

    /** 分包数量上限，防止构造上万个条目的压缩包 */
    private const val MAX_ENTRIES = 200

    /** 一次最多接收的 Uri 数量 */
    private const val MAX_URIS = 20

    fun resolve(ctx: Context, uris: List<Uri>): List<File> {
        val dir = File(ctx.cacheDir, INBOX)
        cleanupCache(ctx)
        dir.mkdirs()

        val collected = mutableListOf<File>()
        var budget = cacheBudget(ctx)

        uris.take(MAX_URIS).forEach { uri ->
            val name = displayName(ctx, uri) ?: "package.apk"
            val file = copyTo(ctx, uri, dir, name, budget)
            budget -= file.length()
            if (file.name.endsWith(".apk", ignoreCase = true) || !looksLikeZip(file)) {
                collected += file
            } else {
                val extracted = unzipApks(file, File(dir, "unz_" + UUID.randomUUID()), budget)
                budget -= extracted.sumOf { it.length() }
                collected += extracted
            }
        }
        return orderBaseFirst(ctx, filterSplits(ctx, collected.distinct()))
    }

    /** 本次可用预算：缓存目录所在分区剩余空间的 40% */
    private fun cacheBudget(ctx: Context): Long {
        val usable = runCatching {
            StatFs(ctx.cacheDir.absolutePath).availableBytes
        }.getOrDefault(0L)
        if (usable <= 0L) return ABS_MAX_BYTES
        return minOf(ABS_MAX_BYTES, (usable * SPACE_RATIO).toLong())
    }

    private val ABI_TOKENS = listOf(
        "arm64_v8a", "armeabi_v7a", "armeabi", "x86_64", "x86", "mips64", "mips",
    )

    private val DENSITY_DPI = linkedMapOf(
        "ldpi" to 120,
        "mdpi" to 160,
        "tvdpi" to 213,
        "hdpi" to 240,
        "xhdpi" to 320,
        "xxhdpi" to 480,
        "xxxhdpi" to 640,
        "nodpi" to 0,
    )

    /**
     * 分包筛选。
     *
     * .xapk / .apks 里常同时包含多个 ABI 的 config split，
     * 全部塞进同一个 session 会被 PMS 以"split 与设备不兼容"直接拒装。
     * 密度分包同理：同一类型的 config split 只能有一个。
     */
    private fun filterSplits(ctx: Context, files: List<File>): List<File> {
        if (files.size <= 1) return files
        val base = files.filter { isBaseApk(ctx, it) }
        val rest = files.filterNot { it in base }
        if (rest.isEmpty()) return files

        val deviceAbis = Build.SUPPORTED_ABIS.map { it.replace('-', '_').lowercase() }
        val deviceDensity = deviceDensityToken(ctx)

        val abiPool = mutableListOf<File>()
        val densityPool = mutableListOf<File>()
        val others = mutableListOf<File>()

        rest.forEach { file ->
            val token = splitTokenOf(ctx, file)
            when {
                ABI_TOKENS.any { token.contains(it) } -> abiPool += file
                DENSITY_DPI.keys.any { token.contains(it) } -> densityPool += file
                else -> others += file
            }
        }

        val keptAbi = when {
            abiPool.isEmpty() -> emptyList()
            else -> abiPool.filter { file ->
                val token = splitTokenOf(ctx, file)
                deviceAbis.any { token.contains(it) }
            }.ifEmpty { abiPool.take(1) }
        }

        val keptDensity = when {
            densityPool.size <= 1 -> densityPool
            else -> densityPool.filter { file ->
                val token = splitTokenOf(ctx, file)
                DENSITY_DPI[deviceDensity] != 0 && token.contains(deviceDensity)
            }.ifEmpty {
                // 没有完全匹配就取最接近的一档，nodpi 始终保留
                val closest = densityPool
                    .filter { !splitTokenOf(ctx, it).contains("nodpi") }
                    .minByOrNull { dpiDistance(it, ctx) }
                listOfNotNull(closest) + densityPool.filter { splitTokenOf(ctx, it).contains("nodpi") }
            }
        }

        val dropped = files.size - (base.size + keptAbi.size + keptDensity.size + others.size)
        if (dropped > 0) Log.i("ApkResolver", "已按设备筛选掉 $dropped 个不兼容分包")
        return base + keptAbi + keptDensity + others
    }

    private fun splitTokenOf(ctx: Context, file: File): String = runCatching {
        ctx.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
            ?.splitNames?.firstOrNull()
            ?: file.name
    }.getOrDefault(file.name).lowercase()

    private fun deviceDensityToken(ctx: Context): String {
        val dpi = ctx.resources.displayMetrics.densityDpi
        return DENSITY_DPI.entries
            .filter { it.value > 0 }
            .minByOrNull { kotlin.math.abs(it.value - dpi) }!!
            .key
    }

    private fun dpiDistance(file: File, ctx: Context): Int {
        val token = splitTokenOf(ctx, file)
        val value = DENSITY_DPI.entries.firstOrNull { token.contains(it.key) }?.value ?: 640
        return kotlin.math.abs(value - ctx.resources.displayMetrics.densityDpi)
    }

    /**
     * 清掉落地的安装包。三处调用：解析前、安装结束（finally）、应用启动时扫一遍，
     * 保证即使上次安装到一半进程被杀，也不会把 APK 一直留在缓存里。
     */
    fun cleanupCache(ctx: Context) {
        runCatching { File(ctx.cacheDir, INBOX).deleteRecursively() }
    }

    /**
     * 应用启动时扫一遍残留：上次安装若被系统杀进程，缓存里会留下 APK。
     * 安装过程中用户切回主界面的窗口极窄，这里直接全清，逻辑更简单。
     */
    fun sweepCache(ctx: Context) {
        cleanupCache(ctx)
    }

    private fun displayName(ctx: Context, uri: Uri): String? {
        if (uri.scheme == "file") return uri.lastPathSegment
        return runCatching {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull() ?: uri.lastPathSegment
    }

    private fun copyTo(ctx: Context, uri: Uri, dir: File, name: String, limit: Long): File {
        val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "package.apk" }
        val target = File(dir, UUID.randomUUID().toString().take(8) + "_" + safe)
        val input = when (uri.scheme) {
            "file" -> File(uri.path!!).inputStream()
            else -> ctx.contentResolver.openInputStream(uri)
        } ?: error("无法读取安装包：$uri")

        val expected = declaredSize(ctx, uri)
        val copied = input.use { ins ->
            target.outputStream().use { out -> copyLimited(ins, out, limit) }
        }

        // 参考 InstallerX 的做法：校验长度，防止 provider 给的流被截断或中途变更
        if (expected >= 0 && copied != expected) {
            target.delete()
            error("安装包读取不完整（期望 $expected 字节，实际 $copied 字节）")
        }
        return target
    }

    /** provider 声明的文件大小；拿不到返回 -1 */
    private fun declaredSize(ctx: Context, uri: Uri): Long {
        if (uri.scheme == "file") {
            return runCatching { File(uri.path!!).length() }.getOrDefault(-1L)
        }
        return runCatching {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
                ?.use { c ->
                    if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else -1L
                } ?: -1L
        }.getOrDefault(-1L)
    }

    private fun looksLikeZip(file: File): Boolean = runCatching {
        file.inputStream().use { s ->
            val head = ByteArray(2)
            s.read(head) == 2 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()
        }
    }.getOrDefault(false)

    private fun unzipApks(archive: File, dest: File, budget: Long): List<File> {
        dest.mkdirs()
        val out = mutableListOf<File>()
        var remaining = budget
        ZipFile(archive).use { zip ->
            zip.entries().asSequence()
                .filter { !it.isDirectory && it.name.endsWith(".apk", ignoreCase = true) }
                .take(MAX_ENTRIES)
                .forEach { entry ->
                    if (remaining <= 0) error("安装包超过总大小限制")
                    // 只取文件名，避免 zip 路径穿越
                    val target = File(dest, File(entry.name).name)
                    zip.getInputStream(entry).use { ins ->
                        target.outputStream().use { outStream ->
                            remaining -= copyLimited(ins, outStream, remaining)
                        }
                    }
                    out += target
                }
        }
        return out
    }

    private fun copyLimited(input: InputStream, output: OutputStream, limit: Long): Long {
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > limit) error("安装包超过大小限制（${limit / 1024 / 1024}MB）")
            output.write(buffer, 0, read)
        }
        return total
    }

    /** base.apk 必须在第一个写入会话，否则 install-write 会失败 */
    private fun orderBaseFirst(ctx: Context, files: List<File>): List<File> {
        if (files.size <= 1) return files
        val base = files.firstOrNull { isBaseApk(ctx, it) }
        return if (base == null) files else listOf(base) + files.filter { it != base }
    }

    private fun isBaseApk(ctx: Context, file: File): Boolean = runCatching {
        val info = ctx.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
        info != null && info.splitNames.isNullOrEmpty()
    }.getOrDefault(false)
}
