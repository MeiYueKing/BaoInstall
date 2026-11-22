package bao.omk.installer

import android.content.Context
import android.os.SystemClock
import bao.omk.config.Config

/**
 * root shell 封装。只用于 pm 命令，不做任何长驻服务。
 *
 * root 命令不一定是 "su"：Magisk / KernelSU / APatch 都在 /system/bin/su 放了入口，
 * 但有些 ROM 在 /system/xbin/su、/sbin/su，userdebug 构建干脆 `adb root` 后 shell 就是 root，
 * 也有 ROM 要求 "suu -c" 这类自定义命令。
 * 因此优先级是：用户自定义 → 已是 root → 常见 su 路径。
 */
object Shell {

    data class Result(val code: Int, val out: String, val err: String) {
        val ok: Boolean get() = code == 0
        val merged: String get() = (out + err).trim()
    }

    /** 各 root 方案的常见入口，按命中概率排序 */
    private val SU_PREFIXES = listOf(
        listOf("su", "-c"),
        listOf("/system/bin/su", "-c"),
        listOf("/system/xbin/su", "-c"),
        listOf("/sbin/su", "-c"),
        listOf("/debug_ramdisk/su", "-c"),
        listOf("/data/adb/ksu/bin/su", "-c"),
        listOf("/data/adb/ap/bin/su", "-c"),
    )

    private var detected = false
    /** null 表示没有可用 root；空列表表示当前进程本身就是 root */
    private var prefix: List<String>? = null
    private var checkedAt = 0L
    private var lastCustom = ""

    fun hasRoot(ctx: Context): Boolean {
        val custom = Config.rootCommand(ctx).trim()
        val now = SystemClock.elapsedRealtime()
        if (detected && custom == lastCustom && now - checkedAt < 30_000) return prefix != null
        prefix = detect(custom)
        lastCustom = custom
        detected = true
        checkedAt = now
        return prefix != null
    }

    fun invalidateRootCache() {
        detected = false
        prefix = null
        lastCustom = ""
        checkedAt = 0L
    }

    private fun detect(custom: String): List<String>? {
        // 用户指定的命令优先；但失败仍然回落自动探测，避免填错一次就彻底不能用
        if (custom.isNotEmpty()) {
            val parts = custom.split(Regex("\\s+")).filter { it.isNotBlank() }
            if (parts.isNotEmpty() && canRoot(parts)) return parts
        }

        // 已经是 root（userdebug / adb root / 部分 AOSP 设备）
        val direct = exec(listOf("sh", "-c", "id -u"))
        if (direct.ok && direct.out.trim() == "0") return emptyList()

        // 逐个试 su 入口
        for (candidate in SU_PREFIXES) {
            if (canRoot(candidate)) return candidate
        }
        return null
    }

    private fun canRoot(launcher: List<String>): Boolean {
        val result = exec(launcher + "id -u")
        return result.ok && result.out.trim() == "0"
    }

    /** 以 root 执行一段 shell 脚本 */
    fun su(ctx: Context, script: String): Result {
        if (!hasRoot(ctx)) return Result(-1, "", "未获得 Root 权限")
        val launcher = prefix ?: return Result(-1, "", "未获得 Root 权限")
        val argv = if (launcher.isEmpty()) listOf("sh", "-c", script) else launcher + script
        return exec(argv)
    }

    private fun exec(argv: List<String>): Result = try {
        val process = ProcessBuilder(argv).start()
        val out = StringBuilder()
        val err = StringBuilder()
        val t1 = Thread { process.inputStream.bufferedReader().forEachLine { out.appendLine(it) } }
        val t2 = Thread { process.errorStream.bufferedReader().forEachLine { err.appendLine(it) } }
        t1.isDaemon = true
        t2.isDaemon = true
        t1.start()
        t2.start()
        val code = process.waitFor()
        t1.join(3_000)
        t2.join(3_000)
        Result(code, out.toString(), err.toString())
    } catch (t: Throwable) {
        Result(-1, "", t.message ?: "命令执行失败")
    }

    fun quote(path: String): String = "'" + path.replace("'", "'\\''") + "'"
}
