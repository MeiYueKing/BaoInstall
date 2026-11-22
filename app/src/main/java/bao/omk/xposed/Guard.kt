package bao.omk.xposed

import bao.omk.ModuleInfo
import java.io.File

/**
 * 误伤保护：system_server 里出错代价是开不了机，所以留一个物理开关。
 * adb shell touch /data/local/tmp/bao_disable_hooks 后重启，模块完全不介入。
 */
object Guard {
    fun isDisabled(): Boolean =
        runCatching { File(ModuleInfo.DISABLE_HOOKS_FILE).exists() }.getOrDefault(false)
}
