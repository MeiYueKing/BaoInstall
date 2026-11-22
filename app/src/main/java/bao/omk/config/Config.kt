package bao.omk.config

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle

/**
 * 模块配置。App 侧写入 SharedPreferences，system_server 侧的 Hook 通过
 * [bao.omk.xposed.ModuleConfigProvider] 读取同一份数据。
 *
 * 只保留用户真正需要决定的开关：
 * - 接管安装入口：会影响其它 App 的行为，值得给开关
 * - 降级安装：有风险，值得给开关
 * - 安装时授予全部权限：有风险，值得给开关
 *
 * 其余（放行 ColorOS 拦截、授予安装权限、优先 Root）属于基础设施，固定开启。
 */
object Config {

    const val AUTHORITY = "bao.omk.config"
    private const val PREF_NAME = "bao_config"

    /** 接管系统安装入口（ActivityStarter 重定向） */
    const val TAKEOVER = "takeover"

    /** 放行 ColorOS 的安装拦截（非 ColorOS 机型上该 Hook 找不到类，自动空转） */
    const val PASS_INTERCEPT = "pass_intercept"

    /** 允许降级安装 */
    const val DOWNGRADE = "downgrade"

    private const val LAST_RESULT = "last_result"
    private const val HOOK_SEEN_AT = "hook_seen_at"

    /** 自定义 Root 命令，例如 "suu -c"；留空则自动探测 */
    private const val ROOT_COMMAND = "root_command"

    /** 主题：light / dark */
    private const val THEME = "theme"

    /** 动画速度：fast / normal / slow */
    private const val SPEED = "speed"

    val DEFAULTS: Map<String, Boolean> = linkedMapOf(
        TAKEOVER to true,
        PASS_INTERCEPT to true,
        DOWNGRADE to true,
    )

    fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun bool(ctx: Context, key: String): Boolean =
        prefs(ctx).getBoolean(key, DEFAULTS[key] ?: false)

    fun setBool(ctx: Context, key: String, value: Boolean) {
        prefs(ctx).edit().putBoolean(key, value).apply()
    }

    fun toBundle(ctx: Context): Bundle = Bundle().apply {
        DEFAULTS.keys.forEach { putBoolean(it, bool(ctx, it)) }
    }

    fun lastResult(ctx: Context): String? = prefs(ctx).getString(LAST_RESULT, null)

    fun setLastResult(ctx: Context, value: String) {
        prefs(ctx).edit().putString(LAST_RESULT, value).apply()
    }

    /** Hook 每次启动都会来取一次配置，这个时间戳就是模块是否生效的证据 */
    fun markHookSeen(ctx: Context) {
        prefs(ctx).edit().putLong(HOOK_SEEN_AT, System.currentTimeMillis()).apply()
    }

    fun hookSeenAt(ctx: Context): Long = prefs(ctx).getLong(HOOK_SEEN_AT, 0L)

    fun rootCommand(ctx: Context): String = prefs(ctx).getString(ROOT_COMMAND, "").orEmpty()

    fun setRootCommand(ctx: Context, value: String) {
        prefs(ctx).edit().putString(ROOT_COMMAND, value).apply()
    }

    /** 主题：auto（跟随系统）/ light / dark */
    fun themeMode(ctx: Context): String = prefs(ctx).getString(THEME, "auto") ?: "auto"

    fun setThemeMode(ctx: Context, value: String) {
        prefs(ctx).edit().putString(THEME, value).apply()
    }

    fun speedMode(ctx: Context): String = prefs(ctx).getString(SPEED, "normal") ?: "normal"

    fun setSpeedMode(ctx: Context, value: String) {
        prefs(ctx).edit().putString(SPEED, value).apply()
    }

    /** 动画时长（毫秒），三档 */
    fun motionDurationMs(ctx: Context): Int = when (speedMode(ctx)) {
        "fast" -> 120
        "slow" -> 420
        else -> 240
    }

    /** 清空用户改动，回到默认值；保留模块生效时间戳，避免状态卡误报 */}
