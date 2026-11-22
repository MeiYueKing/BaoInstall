package bao.omk.xposed

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * 模块入口。只在 system_server 里干活：
 * 安装器界面那一套混淆方法名不碰，全部走 system_server 稳定入口。
 */
class HookEntry : IXposedHookLoadPackage, IXposedHookZygoteInit {

    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam) {
        HookLog.i("模块已加载：${startupParam.modulePath}")
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != "android") return
        if (lpparam.processName != "android") return
        if (Guard.isDisabled()) {
            HookLog.i("检测到 kill switch，本次不注入")
            return
        }
        try {
            ModuleConfig.load(lpparam.classLoader)
            SystemServerHooks.install(lpparam.classLoader)
            scheduleConfigRetry()
            HookLog.i("system_server Hook 完成")
        } catch (t: Throwable) {
            HookLog.e("Hook 初始化失败，已放弃全部注入", t)
        }
    }

    /**
     * 配置读取必须等系统起来，但在 AMS 回调里同步调 provider 有卡住开机的风险，
     * 所以放到守护线程里慢慢重试，失败就沿用默认值（默认值等于功能全开）。
     */
    private fun scheduleConfigRetry() {
        if (ModuleConfig.loaded) return
        Thread({
            var attempt = 0
            while (!ModuleConfig.loaded && attempt < 12) {
                try {
                    Thread.sleep(5_000)
                } catch (e: InterruptedException) {
                    return@Thread
                }
                ModuleConfig.retry()
                attempt++
            }
        }, "bao-config-sync").apply {
            isDaemon = true
            start()
        }
    }
}
