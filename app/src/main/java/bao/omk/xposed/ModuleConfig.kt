package bao.omk.xposed

import android.content.Context
import android.net.Uri
import bao.omk.config.Config

/**
 * Hook 侧配置读取。
 *
 * 注意时序：handleLoadPackage 执行时 ActivityManagerService 还没就绪，
 * 这时调 ContentResolver 会 NPE（acquireProvider 拿到 null 的 IActivityManager），
 * 所以要等系统起来之后补读一次。
 *
 * 只有「接管安装入口」和「放行 ColorOS 拦截」需要读：两者都按实时状态生效。
 * 授予安装权限属于兜底设施，固定开启。
 */
object ModuleConfig {

    var takeover: Boolean = true
        private set
    var passIntercept: Boolean = true
        private set
    var loaded: Boolean = false
        private set

    private var classLoader: ClassLoader? = null

    fun load(cl: ClassLoader) {
        classLoader = cl
        readConfig()
    }

    /** 系统就绪后由 HookEntry 触发 */
    fun retry() {
        if (!loaded) readConfig()
    }

    private fun readConfig() {
        val cl = classLoader ?: return
        runCatching {
            val activityThread = Class.forName("android.app.ActivityThread", false, cl)
            val current = activityThread.getMethod("currentActivityThread").invoke(null)
            val ctx = activityThread.getMethod("getSystemContext").invoke(current) as? Context
                ?: return@runCatching
            val bundle = ctx.contentResolver.call(
                Uri.parse("content://${Config.AUTHORITY}"), "get", null, null
            ) ?: return@runCatching
            takeover = bundle.getBoolean(Config.TAKEOVER, takeover)
            passIntercept = bundle.getBoolean(Config.PASS_INTERCEPT, passIntercept)
            loaded = true
            HookLog.i("配置已载入 takeover=$takeover intercept=$passIntercept")
        }.onFailure { HookLog.e("读取模块配置失败，沿用默认值", it) }
    }
}
