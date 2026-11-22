package bao.omk

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import bao.omk.config.Config
import bao.omk.installer.ApkResolver
import bao.omk.installer.InstallEngine
import bao.omk.installer.InstallJob
import bao.omk.installer.InstallProgress
import bao.omk.installer.PackageInspector
import bao.omk.installer.PackageMeta
import bao.omk.ui.theme.BaoTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 安装确认弹窗。
 *
 * 形态对齐 AOSP PackageInstallerActivity：它继承 AlertActivity，
 * bindUi() 里 setIcon / setTitle(mAppSnippet.label) / setView / setButton 然后 setupAlert()，
 * 本质就是"图标 + 标题 + 内容 + 取消/安装"的对话框窗口。
 *
 * 卡片高度固定：内容区 weight(1f)，按钮固定在底部，切换状态时高度不跳。
 */
class InstallActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.setDimAmount(0.5f)
        val uris = collectUris(intent)
        val primary = uris.firstOrNull()
        val mime = intent?.type
        setContent {
            BaoTheme {
                InstallDialog(uris, primary, mime)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        recreate()
    }

    private fun collectUris(intent: Intent?): List<Uri> {
        if (intent == null) return emptyList()
        val uris = mutableListOf<Uri>()
        intent.data?.let { uris += it }
        val clip = intent.clipData
        if (clip != null) {
            for (i in 0 until clip.itemCount) {
                clip.getItemAt(i)?.uri?.let { uris += it }
            }
        }
        return uris.distinct()
    }
}

sealed interface InstallState {
    data object Loading : InstallState
    data class Ready(val meta: PackageMeta?) : InstallState
    data class Installing(
        val meta: PackageMeta?,
        val progress: InstallProgress,
    ) : InstallState

    data class Done(
        val success: Boolean,
        val message: String,
        val meta: PackageMeta?,
        val launchIntent: Intent?,
    ) : InstallState

    data class Failed(val message: String) : InstallState
}

private class InstallController(private val ctx: Context) {

    var state by mutableStateOf<InstallState>(InstallState.Loading)
        private set

    private var files: List<File> = emptyList()
    private var meta: PackageMeta? = null

    suspend fun load(uris: List<Uri>): Boolean = try {
        state = InstallState.Loading
        val resolved = withContext(Dispatchers.IO) { ApkResolver.resolve(ctx, uris) }
        if (resolved.isEmpty()) {
            state = InstallState.Failed("没有解析到可安装的 APK")
            false
        } else {
            files = resolved
            meta = withContext(Dispatchers.IO) {
                PackageInspector.inspect(ctx, resolved.first(), resolved.size)
            }
            state = InstallState.Ready(meta)
            true
        }
    } catch (t: Throwable) {
        state = InstallState.Failed(t.message ?: "无法读取安装包")
        false
    }

    suspend fun install() {
        if (files.isEmpty()) return
        state = InstallState.Installing(meta, InstallProgress(InstallProgress.Stage.WRITING))
        val job = InstallJob(
            files = files,
            packageName = meta?.packageName,
            downgrade = Config.bool(ctx, Config.DOWNGRADE),
        )
        val outcome = InstallEngine.install(ctx, job) { progress ->
            state = InstallState.Installing(meta, progress)
        }
        state = InstallState.Done(
            success = outcome.success,
            message = outcome.message,
            meta = meta,
            launchIntent = if (outcome.success) launchIntentOf(meta?.packageName) else null,
        )
    }

    /**
     * 和 AOSP InstallSuccess 的判定一致：拿得到启动 Intent 才允许"打开"。
     * 只有服务/模块、没有 LAUNCHER Activity 的应用会返回 null。
     */
    private suspend fun launchIntentOf(pkg: String?): Intent? {
        if (pkg == null) return null
        return withContext(Dispatchers.IO) {
            runCatching {
                ctx.packageManager.getLaunchIntentForPackage(pkg)?.apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }.getOrNull()
        }
    }
}

private const val APK_MIME = "application/vnd.android.package-archive"

/** 找一个非本应用的处理者（通常是系统安装器），用于"改用系统安装器" */
private fun systemInstallerIntent(ctx: Context, uri: Uri, mime: String?): Intent? {
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mime ?: APK_MIME)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val handlers = runCatching {
        ctx.packageManager.queryIntentActivities(intent, 0)
            .map { it.activityInfo }
            .filter { it.packageName != ctx.packageName }
    }.getOrDefault(emptyList())
    val target = handlers.firstOrNull { it.packageName.contains("packageinstaller") }
        ?: handlers.firstOrNull()
        ?: return null
    return intent.setComponent(ComponentName(target.packageName, target.name))
}

@Composable
private fun InstallDialog(uris: List<Uri>, primaryUri: Uri?, mimeType: String?) {
    val ctx = LocalContext.current
    val controller = remember { InstallController(ctx) }
    val scope = rememberCoroutineScope()

    val state = controller.state
    val installing = state is InstallState.Installing

    fun dismiss() {
        (ctx as? Activity)?.finish()
    }

    LaunchedEffect(Unit) {
        controller.load(uris)
    }

    // 安装中不允许用返回键退出：pm 还在跑，退出去只会让用户以为失败了
    BackHandler(enabled = installing) { }

    Box(Modifier.fillMaxSize()) {
        // 点空白处取消，和 AlertDialog 一致；安装过程中禁用
        Box(
            Modifier
                .fillMaxSize()
                .clickable(
                    enabled = !installing,
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { dismiss() }
        )

        Card(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth(0.92f)
                .pointerInput(Unit) { detectTapGestures { } },
            shape = RoundedCornerShape(28.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
            ),
        ) {
            // 读取阶段：内容高度自适应，不需要撑成对话框大小
            if (state is InstallState.Loading) {
                Column(Modifier.padding(horizontal = 24.dp, vertical = 22.dp)) {
                    BusyRow("正在读取安装包…")
                }
                return@Card
            }

            Column(
                Modifier
                    .padding(start = 24.dp, end = 24.dp, top = 22.dp, bottom = 8.dp)
                    .height(160.dp)
            ) {
                Box(Modifier.weight(1f)) {
                    when (state) {
                        is InstallState.Loading -> Unit

                        is InstallState.Failed -> Text(
                            state.message,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )

                        is InstallState.Ready -> Column {
                            state.meta?.let { Header(it) }
                            if (state.meta?.signatureChanged == true) {
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    "签名与已安装版本不一致，无法覆盖安装",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }

                        is InstallState.Installing -> Column(Modifier.fillMaxSize()) {
                            state.meta?.let { Header(it) }
                            Spacer(Modifier.height(14.dp))
                            ProgressBlock(state.progress)
                        }

                        is InstallState.Done -> Column {
                            state.meta?.let { Header(it) }
                            Spacer(Modifier.height(12.dp))
                            Text(
                                if (state.success) "安装成功" else "安装失败",
                                style = MaterialTheme.typography.titleMedium,
                                color = if (state.success) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.error
                                },
                            )
                            if (!state.success) {
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    state.message,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    when (state) {
                        is InstallState.Ready -> {
                            TextButton(onClick = { dismiss() }) { Text("取消") }
                            Spacer(Modifier.width(4.dp))
                            Button(onClick = { scope.launch { controller.install() } }) {
                                Text("安装")
                            }
                        }

                        is InstallState.Failed -> {
                            SystemInstallerButton(ctx, primaryUri, mimeType) { dismiss() }
                            TextButton(onClick = { dismiss() }) { Text("关闭") }
                        }

                        is InstallState.Done -> {
                            if (state.success && state.launchIntent != null) {
                                TextButton(onClick = { dismiss() }) { Text("关闭") }
                                Spacer(Modifier.width(4.dp))
                                Button(onClick = {
                                    runCatching { ctx.startActivity(state.launchIntent) }
                                    dismiss()
                                }) { Text("打开") }
                            } else {
                                if (!state.success) {
                                    SystemInstallerButton(ctx, primaryUri, mimeType) { dismiss() }
                                }
                                TextButton(onClick = { dismiss() }) { Text("关闭") }
                            }
                        }

                        else -> Unit
                    }
                }
            }
        }
    }
}

/** 失败时的出口：交给系统安装器再试一次 */
@Composable
private fun SystemInstallerButton(
    ctx: Context,
    uri: Uri?,
    mimeType: String?,
    onLaunch: () -> Unit,
) {
    if (uri == null) return
    TextButton(onClick = {
        val intent = systemInstallerIntent(ctx, uri, mimeType)
        if (intent != null) {
            runCatching { ctx.startActivity(intent) }
            onLaunch()
        }
    }) { Text("用系统安装器") }
}

@Composable
private fun ProgressBlock(progress: InstallProgress) {
    // 与「安装成功」同样的左边距，进度条拉满内容宽度
    Column(Modifier.fillMaxWidth()) {
        val fraction = progress.fraction
        Text(
            when {
                progress.stage == InstallProgress.Stage.COMMITTING -> "正在安装…"
                progress.totalSplits > 1 -> "正在写入 ${progress.writtenSplits}/${progress.totalSplits}"
                else -> "正在写入…"
            },
            fontSize = 18.sp,
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(10.dp))
        if (fraction != null) {
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            // commit 阶段拿不到百分比（校验/dexopt 在 PMS 内部），用不确定进度
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun Header(meta: PackageMeta) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        AppIcon(meta.icon)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                meta.label,
                fontSize = 19.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "版本 ${meta.versionName ?: "?"} (${meta.versionCode})",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun BusyRow(text: String, large: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(
            modifier = Modifier.size(if (large) 26.dp else 18.dp),
            strokeWidth = if (large) 2.5.dp else 2.dp,
        )
        Spacer(Modifier.width(if (large) 14.dp else 12.dp))
        Text(
            text,
            fontSize = if (large) 18.sp else 14.sp,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun AppIcon(icon: Drawable?) {
    if (icon == null) {
        Spacer(Modifier.size(56.dp))
        return
    }
    Image(
        bitmap = icon.toBitmap(112, 112).asImageBitmap(),
        contentDescription = null,
        modifier = Modifier.size(56.dp),
    )
}
