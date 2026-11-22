package bao.omk.xposed

import de.robv.android.xposed.XposedBridge

object HookLog {
    private const val TAG = "BaoInstaller"

    fun i(msg: String) {
        XposedBridge.log("$TAG: $msg")
    }

    fun e(msg: String, t: Throwable? = null) {
        XposedBridge.log("$TAG: $msg")
        if (t != null) XposedBridge.log(t)
    }
}
