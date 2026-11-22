package bao.omk

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import bao.omk.config.Config
import bao.omk.installer.ApkResolver
import bao.omk.installer.Shell
import bao.omk.ui.theme.BaoTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private val refreshTick = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 上次安装若被系统杀进程，缓存里可能留下 APK，启动时扫掉残留
        ApkResolver.sweepCache(this)
        val activity = this
        setContent {
            // 读一次版本号：主题或动画速度变化时，整棵树跟着重组
            val prefsVersion = UiPrefs.version
            val systemDark = isSystemInDarkTheme()
            val dark = remember(prefsVersion, systemDark) {
                when (Config.themeMode(activity)) {
                    "light" -> false
                    "dark" -> true
                    else -> systemDark
                }
            }
            BaoTheme(darkTheme = dark) {
                AppRoot(refreshTick.intValue)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshTick.intValue++
    }
}

private data class Destination(val label: String, val icon: ImageVector)

/** 主题/动画速度改动后，靠这个版本号触发整棵树重组，避免重建 Activity */
private object UiPrefs {
    var version by mutableIntStateOf(0)

    fun bump() {
        version++
    }
}

/**
 * 底部导航 + HorizontalPager，形态参考 KernelSU 管理器的 Material3 实现：
 * 容器色 surfaceContainer，选中项高亮，切页走 pager 动画而不是直接换内容。
 */
@Composable
private fun AppRoot(tick: Int) {
    val destinations = remember {
        listOf(
            Destination("主页", Icons.Filled.Home),
            Destination("设置", Icons.Filled.Settings),
        )
    }
    val pagerState = rememberPagerState { destinations.size }
    val scope = rememberCoroutineScope()
    val motionCtx = LocalContext.current
    val motion = remember(UiPrefs.version) { Config.motionDurationMs(motionCtx) }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                destinations.forEachIndexed { index, destination ->
                    val selected = pagerState.currentPage == index
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            if (!selected) {
                                scope.launch {
                                    pagerState.animateScrollToPage(
                                        index,
                                        animationSpec = tween(durationMillis = motion),
                                    )
                                }
                            }
                        },
                        icon = {
                            Icon(destination.icon, contentDescription = destination.label)
                        },
                        label = {
                            Text(
                                destination.label,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                    )
                }
            }
        },
    ) { padding ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) { page ->
            when (page) {
                0 -> HomePage(tick)
                else -> SettingsPage()
            }
        }
    }
}

@Composable
private fun HomePage(tick: Int) {
    val ctx = LocalContext.current

    var rootAvailable by remember(tick) { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(tick) {
        rootAvailable = withContext(Dispatchers.IO) { Shell.hasRoot(ctx) }
    }

    // Hook 启动后会来 provider 取配置（系统就绪后补读），取一次就写时间戳，
    // 这就是"模块真的在 system_server 里跑着"的证据
    val moduleActive = remember(tick) { Config.hookSeenAt(ctx) > 0L }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                ctx.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            ctx.startActivity(
                Intent(ctx, InstallActivity::class.java).apply {
                    action = Intent.ACTION_VIEW
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            )
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        StatusCard(rootAvailable = rootAvailable, moduleActive = moduleActive)

        Spacer(Modifier.height(16.dp))

        Button(
            onClick = { picker.launch(arrayOf("*/*")) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("选择安装包") }
    }
}

/**
 * 状态卡：参考 KernelSU 主页 StatusCard 的做法——
 * 正常用 secondaryContainer、异常用 errorContainer，内容色由 contentColorFor 推导，
 * 左侧图标表达三态（检测中 / 正常 / 异常）。
 */
@Composable
private fun StatusCard(
    rootAvailable: Boolean?,
    moduleActive: Boolean,
) {
    val loading = rootAvailable == null
    val healthy = rootAvailable == true && moduleActive

    val container = when {
        loading -> MaterialTheme.colorScheme.surfaceContainer
        healthy -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.errorContainer
    }
    val content = if (loading) MaterialTheme.colorScheme.onSurface else contentColorFor(container)

    Card(
        colors = CardDefaults.cardColors(containerColor = container, contentColor = content),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(26.dp),
                    strokeWidth = 2.5.dp,
                    color = content,
                )
            } else {
                Icon(
                    imageVector = if (healthy) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                )
            }

            Spacer(Modifier.width(16.dp))

            Column(Modifier.weight(1f)) {
                Text(
                    when {
                        loading -> "检测中…"
                        healthy -> "运行正常"
                        else -> "需要处理"
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    when {
                        loading -> "正在检查 Root 与模块状态"
                        healthy -> "Root 可用 · 模块生效"
                        else -> buildList {
                            if (rootAvailable != true) add("Root 不可用")
                            if (!moduleActive) add("模块未生效")
                        }.joinToString(" · ")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = content.copy(alpha = 0.75f),
                )
            }
        }

        if (!loading && !healthy) {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 16.dp)) {
                if (!moduleActive) {
                    Text(
                        "在 LSPosed 中启用本模块，作用域勾选「系统框架」，然后重启手机。",
                        style = MaterialTheme.typography.bodySmall,
                        color = content.copy(alpha = 0.85f),
                    )
                }
                if (rootAvailable == false) {
                    Text(
                        "本应用强制要求 Root，未获得授权时无法安装。",
                        style = MaterialTheme.typography.bodySmall,
                        color = content.copy(alpha = 0.85f),
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

private enum class SettingsRoute { ROOT, GENERAL, COLOROS, APP, ABOUT }

/**
 * 设置：一级是分组条目（图标 + 标题 + 摘要 + 箭头），点击进二级子页。
 * 结构参考 KernelSU 管理器的 SegmentedList 设计。
 * 子页切换用与主页/设置页一致的滑动 + 淡入动画，时长跟随「动画速度」设置。
 */
@Composable
private fun SettingsPage() {
    var route by remember { mutableStateOf(SettingsRoute.ROOT) }
    val motionCtx = LocalContext.current
    val motion = remember(UiPrefs.version) { Config.motionDurationMs(motionCtx) }

    BackHandler(enabled = route != SettingsRoute.ROOT) { route = SettingsRoute.ROOT }

    AnimatedContent(
        targetState = route,
        transitionSpec = {
            val dir = if (targetState.ordinal >= initialState.ordinal) 1 else -1
            (slideInHorizontally(tween(motion)) { it / 6 * dir } + fadeIn(tween(motion))) togetherWith
                (slideOutHorizontally(tween(motion)) { -it / 6 * dir } + fadeOut(tween(motion)))
        },
        label = "settings-route",
    ) { current ->
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            if (current == SettingsRoute.ROOT) {
                Spacer(Modifier.height(8.dp))
                SettingsCard {
                    ListRow(
                        icon = Icons.Filled.Settings,
                        title = "通用",
                    ) { route = SettingsRoute.GENERAL }
                    HorizontalDivider()
                    ListRow(
                        icon = Icons.Filled.Build,
                        title = "系统适配",
                    ) { route = SettingsRoute.COLOROS }
                    HorizontalDivider()
                    ListRow(
                        icon = Icons.Filled.Create,
                        title = "应用设置",
                    ) { route = SettingsRoute.APP }
                }

                Spacer(Modifier.height(12.dp))

                SettingsCard {
                    ListRow(
                        icon = Icons.Filled.Info,
                        title = "关于",
                    ) { route = SettingsRoute.ABOUT }
                }

                Spacer(Modifier.height(16.dp))
                return@Column
            }

            SubHeader(
                title = when (current) {
                    SettingsRoute.GENERAL -> "通用"
                    SettingsRoute.COLOROS -> "系统适配"
                    SettingsRoute.APP -> "应用设置"
                    else -> "关于"
                },
                onBack = { route = SettingsRoute.ROOT },
            )

            when (current) {
                SettingsRoute.GENERAL -> GeneralPage()

                SettingsRoute.COLOROS -> SettingsCard {
                    ToggleRow(
                        title = "放行 ColorOS 安装拦截",
                        desc = "跳过 ColorOS「登录 OPPO 账号才能继续安装」这一拦截。非 ColorOS 机型可关闭，不影响使用。",
                        key = Config.PASS_INTERCEPT,
                    )
                }

                SettingsRoute.APP -> AppSettingsPage()

                else -> AboutPage()
            }
        }
    }
}

@Composable
private fun AboutPage() {
    val ctx = LocalContext.current
    val version = remember {
        runCatching {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
        }.getOrNull()
    }
    val icon = remember { ctx.applicationInfo.loadIcon(ctx.packageManager) }

    Column(Modifier.fillMaxWidth()) {
        // 头部：居中大图标 + 名称 + 版本（KernelSU / InstallerX 都是这个结构）
        Column(
            Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Image(
                bitmap = icon.toBitmap(192, 192).asImageBitmap(),
                contentDescription = null,
                modifier = Modifier
                    .size(76.dp)
                    .clip(RoundedCornerShape(18.dp)),
            )
            Spacer(Modifier.height(14.dp))
            Text(
                "BaoInstall",
                style = MaterialTheme.typography.headlineSmall,
            )
            if (!version.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    "版本 $version",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(28.dp))

        SettingsCard {
            ListRow(
                title = "GitHub",
                leading = {
                    Icon(
                        painterResource(R.drawable.ic_github),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp),
                    )
                },
            ) { openUrl(ctx, GITHUB_URL) }
        }

        Spacer(Modifier.height(12.dp))

        SettingsCard {
            ListRow(
                icon = Icons.Filled.Person,
                title = "开发者",
                summary = "本应用的作者与维护者",
                trailing = {},
            )
            HorizontalDivider()
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Image(
                    painter = painterResource(R.drawable.dev_avatar),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape),
                )
                Spacer(Modifier.width(14.dp))
                Text("芝士🧀雪豹", style = MaterialTheme.typography.bodyLarge)
                // 推到行尾，和右侧开关/箭头对齐
                Spacer(Modifier.weight(1f))
                IconButton(
                    onClick = { openUrl(ctx, GITHUB_URL) },
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        Icons.Filled.Info,
                        contentDescription = "GitHub 主页",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

private const val GITHUB_URL = "https://github.com/MeiYueKing/BaoInstall"

private fun openUrl(ctx: Context, url: String) {
    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}

@Composable
private fun GeneralPage() {
    val ctx = LocalContext.current
    var showDialog by remember { mutableStateOf(false) }
    var rootCommand by remember { mutableStateOf(Config.rootCommand(ctx)) }

    Column {
        SettingsCard {
            ToggleRow(
                title = "接管系统安装入口",
                key = Config.TAKEOVER,
            )
            HorizontalDivider()
            ToggleRow(
                title = "允许降级安装",
                key = Config.DOWNGRADE,
            )
        }

        Spacer(Modifier.height(12.dp))

        SettingsCard {
            ListRow(
                icon = Icons.Filled.Lock,
                title = "Root 命令",
                summary = rootCommand.ifBlank { "自动检测" },
            ) { showDialog = true }
        }
    }

    if (showDialog) {
        RootCommandDialog(
            initial = rootCommand,
            onDismiss = { showDialog = false },
            onConfirm = { value ->
                Config.setRootCommand(ctx, value)
                Shell.invalidateRootCache()
                rootCommand = value
                showDialog = false
            },
        )
    }
}

@Composable
private fun AppSettingsPage() {
    val ctx = LocalContext.current
    val theme = remember(UiPrefs.version) { Config.themeMode(ctx) }
    val speed = remember(UiPrefs.version) { Config.speedMode(ctx) }

    SettingsCard {
        DropdownRow(
            title = "应用主题",
            selected = theme,
            options = listOf("auto" to "自动", "light" to "浅色", "dark" to "暗色"),
            onSelect = {
                Config.setThemeMode(ctx, it)
                UiPrefs.bump()
            },
        )
        HorizontalDivider()
        DropdownRow(
            title = "动画速度",
            selected = speed,
            options = listOf("fast" to "快", "normal" to "适中", "slow" to "慢"),
            onSelect = {
                Config.setSpeedMode(ctx, it)
                UiPrefs.bump()
            },
        )
    }
}

/** 点击整行，在行下方弹出下拉菜单（不是居中对话框） */
@Composable
private fun DropdownRow(
    title: String,
    selected: String,
    options: List<Pair<String, String>>,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val current = options.firstOrNull { it.first == selected }?.second ?: options.first().second

    Box(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { expanded = true }
                .padding(vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Text(
                current,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(6.dp))
            Icon(
                Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }

        // 锚点放在行尾，菜单就从右侧展开（空间不够时会自动往左收）
        Box(Modifier.align(Alignment.TopEnd)) {
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
            options.forEach { (value, label) ->
                DropdownMenuItem(
                    text = {
                        Text(
                            label,
                            color = if (value == selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                    },
                    onClick = {
                        expanded = false
                        onSelect(value)
                    },
                    trailingIcon = {
                        if (value == selected) {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    },
                )
                }
            }
        }
    }
}

@Composable
private fun RootCommandDialog(
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Root 命令") },
        text = {
            Column {
                Text(
                    "留空则自动探测（su、/system/bin/su 等）。某些 ROM 需要 suu -c 这类自定义前缀。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    placeholder = { Text("例如：suu -c") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text.trim()) }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun ListRow(
    title: String,
    summary: String? = null,
    icon: ImageVector? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(16.dp))
        } else if (leading != null) {
            leading()
            Spacer(Modifier.width(16.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (summary != null) {
                Text(
                    summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (trailing != null) {
            trailing()
        } else {
            Text(
                "›",
                fontSize = 22.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SubHeader(title: String, onBack: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
        }
        Text(title, style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 2.dp)) {
            content()
        }
    }
}

@Composable
private fun ToggleRow(title: String, key: String, desc: String? = null) {
    val ctx = LocalContext.current
    var checked by remember { mutableStateOf(Config.bool(ctx, key)) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (desc != null) {
                Text(
                    desc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = {
                checked = it
                Config.setBool(ctx, key, it)
            },
        )
    }
}
