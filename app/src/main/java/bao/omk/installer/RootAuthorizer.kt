package bao.omk.installer

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Root 通道：直接走 pm 命令。
 *
 * 关键点：pm 运行在独立进程里直接调用 PackageManagerService，
 * 完全不会经过 OPPO 的 OPlusPackageInstallerActivity，
 * 所以广告、安全检测、多步确认全部不存在。
 * 唯一需要处理的是 ColorOS 对 ADB/root 安装的拦截，
 * 那一个点由模块在 system_server 侧放行。
 */
class RootAuthorizer {

    suspend fun install(
        ctx: Context,
        job: InstallJob,
        onProgress: (InstallProgress) -> Unit = {},
    ): InstallOutcome = withContext(Dispatchers.IO) {
        val start = SystemClock.elapsedRealtime()
        fun done(ok: Boolean, msg: String) =
            InstallOutcome(ok, msg, CHANNEL, SystemClock.elapsedRealtime() - start)

        if (job.files.isEmpty()) return@withContext done(false, "没有可安装的文件")
        if (!Shell.hasRoot(ctx)) {
            return@withContext done(
                false,
                "未获得 Root 权限。请在 KernelSU / Magisk 中给「BaoInstall」授予 root",
            )
        }

        val total = job.files.sumOf { it.length() }
        val createCmd = buildString {
            append("pm install-create -r")
            if (job.downgrade) append(" -d")
            append(" --user ").append(job.userId)
            append(" -S ").append(total)
        }
        val create = Shell.su(ctx, createCmd)
        val sessionId = SESSION_ID.find(create.merged)?.groupValues?.get(1)
        if (sessionId == null) {
            val reason = InstallErrors.translate(create.merged)
            return@withContext done(
                false,
                if (reason.isBlank()) "创建安装会话失败" else reason,
            )
        }

        onProgress(
            InstallProgress(InstallProgress.Stage.WRITING, 0, total, 0, job.files.size)
        )
        var writtenBytes = 0L

        job.files.forEachIndexed { index, file ->
            // 只允许写缓存目录下的文件，避免把任意路径拼进 root 命令
            if (!file.absolutePath.startsWith(ctx.cacheDir.absolutePath + File.separator)) {
                Shell.su(ctx, "pm install-abandon $sessionId")
                return@withContext done(false, "安装包路径异常，已中止")
            }
            val splitName = if (index == 0) "base" else "split_$index"
            val write = Shell.su(
                ctx,
                "pm install-write -S ${file.length()} $sessionId $splitName " +
                    Shell.quote(file.absolutePath),
            )
            if (!write.ok || write.merged.contains("Failure")) {
                Shell.su(ctx, "pm install-abandon $sessionId")
                return@withContext done(false, InstallErrors.translate(write.merged))
            }
            writtenBytes += file.length()
            onProgress(
                InstallProgress(
                    InstallProgress.Stage.WRITING,
                    writtenBytes,
                    total,
                    index + 1,
                    job.files.size,
                )
            )
        }

        // 写入只是把字节放进会话，校验、解析、dexopt 都在 commit 里
        onProgress(
            InstallProgress(
                InstallProgress.Stage.COMMITTING,
                total,
                total,
                job.files.size,
                job.files.size,
            )
        )
        val commit = Shell.su(ctx, "pm install-commit $sessionId")
        val ok = commit.ok && commit.merged.contains("Success")
        if (ok) done(true, "安装成功") else done(false, InstallErrors.translate(commit.merged))
    }

    companion object {
        const val CHANNEL = "Root"
        private val SESSION_ID = Regex("\\[(\\d+)]")
    }
}
