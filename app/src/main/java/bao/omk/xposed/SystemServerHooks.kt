package bao.omk.xposed

import android.content.Intent
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * system_server 侧的 Hook。只做两件事：
 *
 * 1. 放行 ColorOS 的安装拦截（ADB / root 安装时不再要求登录 OPPO 账号）
 * 2. 把系统分发的安装 Intent 改到我们自己的安装页
 *
 * 本应用强制走 Root 通道（su + pm），不需要在系统里给自己授予
 * INSTALL_PACKAGES——那类改动会让 system_server 的权限判断对某个 uid 失效，
 * 影响面远超安装本身，已移除。
 */
object SystemServerHooks {

    private val INTERCEPT_METHOD = Regex(
        "(allow|should|need|can)[A-Za-z]*[Ii]ntercept",
        RegexOption.IGNORE_CASE,
    )

    fun install(cl: ClassLoader) {
        // 始终挂载，是否放行由开关实时决定；非 ColorOS 机型找不到类，自动跳过
        hookInstallIntercept(cl)
        // 接管安装入口：会改变其它 App 的行为，交给用户决定
        if (ModuleConfig.takeover) hookIntentRedirect(cl)
    }

    // ---------------------------------------------------------------- 1

    private fun hookInstallIntercept(cl: ClassLoader) {
        val candidates = listOf(
            "com.android.server.pm.OplusPackageInstallInterceptManager", // ColorOS 16 实测存在
            "com.android.server.pm.ColorPackageInstallInterceptManager", // 旧版本命名
            "com.android.server.pm.OplusPackageManagerService",
        )
        candidates.forEach { className ->
            val cls = XposedHelpers.findClassIfExists(className, cl) ?: return@forEach
            var count = 0
            cls.declaredMethods
                .filter {
                    it.returnType == Boolean::class.javaPrimitiveType &&
                        it.parameterTypes.size <= 5 &&
                        INTERCEPT_METHOD.containsMatchIn(it.name)
                }
                .forEach { method ->
                    runCatching {
                        XposedBridge.hookMethod(method, object : XC_MethodReplacement() {
                            override fun replaceHookedMethod(param: MethodHookParam): Any? {
                                // 开关实时生效：关掉就原样回调系统逻辑。
                                // 回调本身也要兜住，替换方法返回 null 会让 boolean 调用方 NPE。
                                if (!ModuleConfig.passIntercept) {
                                    return runCatching {
                                        XposedBridge.invokeOriginalMethod(
                                            param.method, param.thisObject, param.args
                                        )
                                    }.getOrDefault(false)
                                }
                                return false
                            }
                        })
                        count++
                    }
                }
            if (count > 0) HookLog.i("放行安装拦截：$className ($count 个方法)")
        }
    }

    // ---------------------------------------------------------------- 2

    private fun hookIntentRedirect(cl: ClassLoader) {
        val starter = XposedHelpers.findClassIfExists("com.android.server.wm.ActivityStarter", cl)
        if (starter == null) {
            HookLog.e("找不到 ActivityStarter，安装入口接管未生效")
            return
        }
        val execute = starter.declaredMethods.firstOrNull {
            it.name == "execute" && it.parameterTypes.isEmpty()
        }
        if (execute == null) {
            HookLog.e("找不到 ActivityStarter.execute()，安装入口接管未生效")
            return
        }
        XposedBridge.hookMethod(execute, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                try {
                    // 配置在系统就绪后才读到，所以这里实时判断，而不是挂载时判断
                    if (!ModuleConfig.takeover) return
                    val request = XposedHelpers.getObjectField(param.thisObject, "mRequest")
                        ?: return
                    val intent = XposedHelpers.getObjectField(request, "intent") as? Intent
                        ?: return
                    if (Redirector.rewrite(intent)) {
                        HookLog.i("安装请求已转交 BaoInstall：${intent.dataString}")
                    }
                } catch (t: Throwable) {
                    // 单次失败绝不能让 system_server 崩溃
                }
            }
        })
        HookLog.i("安装入口接管已就绪")
    }
}
