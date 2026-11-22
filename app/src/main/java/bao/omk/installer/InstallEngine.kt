package bao.omk.installer

import android.content.Context
import bao.omk.config.Config
import java.io.File

data class InstallJob(
    val files: List<File>,
    val packageName: String?,
    val downgrade: Boolean,
    val userId: Int = 0,
)

data class InstallOutcome(
    val success: Boolean,
    val message: String,
    val channel: String,
    val elapsedMs: Long,
)

/**
 * 安装进度。
 *
 * 分两段是因为耗时结构如此：install-write 只是把字节写进会话（很快），
 * 真正慢的是 install-commit —— 校验签名、解析、dexopt 都在那一步。
 * 如果只按"分包数"算百分比，单个 APK 时会在写入完成的瞬间就显示 100%，
 * 然后卡着不动，看起来像卡死。
 */
data class InstallProgress(
    val stage: Stage,
    val writtenBytes: Long = 0,
    val totalBytes: Long = 0,
    val writtenSplits: Int = 0,
    val totalSplits: Int = 0,
) {
    enum class Stage { WRITING, COMMITTING }

    /** 写入阶段的比例；commit 阶段返回 null（表示无法给出确定进度） */
    val fraction: Float?
        get() = if (stage == Stage.WRITING && totalBytes > 0) {
            (writtenBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
        } else null
}

/**
 * 安装调度。本应用强制要求 Root + LSPosed，没有无 Root 的降级通道：
 * 缺少 Root 时直接失败并明确告知，不做"看起来能装其实装不上"的兜底。
 */
object InstallEngine {

    suspend fun install(
        ctx: Context,
        job: InstallJob,
        onProgress: (InstallProgress) -> Unit = {},
    ): InstallOutcome {
        val outcome = try {
            RootAuthorizer().install(ctx, job, onProgress)
        } catch (t: Throwable) {
            InstallOutcome(
                success = false,
                message = "安装异常：${t.message}",
                channel = RootAuthorizer.CHANNEL,
                elapsedMs = 0,
            )
        } finally {
            // 无论成功、失败还是协程被取消，都不在缓存里留安装包
            ApkResolver.cleanupCache(ctx)
        }
        record(ctx, job, outcome)
        return outcome
    }

    private fun record(ctx: Context, job: InstallJob, outcome: InstallOutcome) {
        val name = job.packageName ?: job.files.firstOrNull()?.name ?: "未知"
        val text = buildString {
            append(if (outcome.success) "成功" else "失败")
            append(" · ").append(name)
            append(" · ").append(outcome.channel).append("通道")
            append(" · ").append(outcome.elapsedMs).append("ms")
            // 成功不用再重复一句"安装成功"，失败才需要原因
            if (!outcome.success) append(" · ").append(outcome.message)
        }
        Config.setLastResult(ctx, text)
    }
}
