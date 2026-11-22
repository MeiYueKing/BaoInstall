package bao.omk.xposed

import android.content.ComponentName
import android.content.Intent
import bao.omk.ModuleInfo

/**
 * 把系统的安装请求改写到我们自己的安装页。
 * 只处理带真实 Uri 的 VIEW / INSTALL_PACKAGE，其它一律放行。
 */
object Redirector {

    private val APK_MIMES = setOf("application/vnd.android.package-archive")
    private val APK_EXTENSIONS = listOf(".apk", ".apks", ".xapk", ".apkm")

    fun rewrite(intent: Intent): Boolean {
        if (intent.component?.packageName == ModuleInfo.PKG) return false

        val action = intent.action ?: return false
        val data = intent.data ?: return false
        if (data.scheme != "content" && data.scheme != "file") return false

        val isInstall = action == Intent.ACTION_INSTALL_PACKAGE ||
            (action == Intent.ACTION_VIEW && isApkPayload(intent, data.path))
        if (!isInstall) return false

        intent.component = ComponentName(ModuleInfo.PKG, ModuleInfo.INSTALL_ACTIVITY)
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // 关键：去掉 NEW_TASK。
        // 带 NEW_TASK 时系统会按 taskAffinity 找任务，默认 affinity 就是包名，
        // 于是把我们自己的任务（MainActivity）整个拉到前台——看起来就是"打开了 Bao"。
        // 去掉之后弹窗直接落在调用方任务里，背后仍是文件管理器/浏览器。
        intent.removeFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return true
    }

    private fun isApkPayload(intent: Intent, path: String?): Boolean {
        val mime = intent.type
        if (mime != null && mime in APK_MIMES) return true
        if (path == null) return false
        return APK_EXTENSIONS.any { path.endsWith(it, ignoreCase = true) }
    }
}
