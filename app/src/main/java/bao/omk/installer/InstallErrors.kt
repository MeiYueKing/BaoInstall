package bao.omk.installer

/**
 * 把 pm 返回的原始失败码翻译成人话。
 *
 * pm 的输出形如：
 *   Failure [INSTALL_FAILED_VERSION_DOWNGRADE: Downgrade detected: Update version code 100 ...]
 *   Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE: Package xxx signatures do not match ...]
 *
 * 直接把这种串丢给用户没有意义，这里映射成「原因 + 怎么办」。
 * 原始串仍然保留在返回值的括号里，便于排查。
 */
object InstallErrors {

    private val TABLE = listOf(
        "INSTALL_FAILED_VERSION_DOWNGRADE" to
            "已安装的版本更高，被拒绝降级。可在「设置 → 通用」里开启允许降级安装",
        "INSTALL_FAILED_UPDATE_INCOMPATIBLE" to
            "与已安装版本的签名不一致，需要先卸载原应用",
        "INSTALL_FAILED_SHARED_USER_INCOMPATIBLE" to
            "与已安装版本共享用户 ID 但签名不同，需要先卸载",
        "INSTALL_FAILED_DUPLICATE_PERMISSION" to
            "应用声明的权限与其他已装应用冲突，需要先卸载冲突的那个",
        "INSTALL_FAILED_INSUFFICIENT_STORAGE" to
            "存储空间不足",
        "INSTALL_FAILED_ALREADY_EXISTS" to
            "该应用已存在",
        "INSTALL_FAILED_INVALID_APK" to
            "安装包不完整或已损坏",
        "INSTALL_FAILED_INVALID_URI" to
            "安装包路径无效",
        "INSTALL_PARSE_FAILED_NO_CERTIFICATES" to
            "安装包没有签名",
        "INSTALL_PARSE_FAILED_INCONSISTENT_CERTIFICATES" to
            "安装包内各分包的签名不一致",
        "INSTALL_FAILED_OLDER_SDK" to
            "应用要求的最低系统版本高于当前系统",
        "INSTALL_FAILED_NEWER_SDK" to
            "应用不支持当前系统版本（maxSdk 限制）",
        "INSTALL_FAILED_NO_MATCHING_ABIS" to
            "应用没有适配本机 CPU 架构的分包",
        "INSTALL_FAILED_MISSING_SPLIT" to
            "缺少必要的分包，请使用完整的 apks / xapk 安装",
        "INSTALL_FAILED_USER_RESTRICTED" to
            "系统限制了这个来源的安装",
        "INSTALL_FAILED_ABORTED" to
            "安装被中止",
        "INSTALL_FAILED_VERIFICATION_FAILURE" to
            "系统完整性校验未通过",
        "INSTALL_FAILED_VERIFICATION_TIMEOUT" to
            "系统完整性校验超时",
        "INSTALL_FAILED_CONFLICTING_PROVIDER" to
            "应用声明的 ContentProvider 与已装应用冲突",
        "INSTALL_FAILED_DEPRECATED_SDK_VERSION" to
            "应用的目标 SDK 版本已不被系统支持",
    )

    /** 命中已知错误码就返回友好文案，否则原样返回 */
    fun translate(raw: String): String {
        val text = raw.trim()
        if (text.isEmpty()) return "安装失败（pm 无输出）"
        TABLE.firstOrNull { text.contains(it.first) }?.let { (_, friendly) ->
            return friendly
        }
        return text
    }
}
