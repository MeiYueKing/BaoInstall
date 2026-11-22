package bao.omk.xposed

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import bao.omk.config.Config

/**
 * 配置读取通道。system_server（system uid）无法直接读取本应用的 shared_prefs，
 * 因此通过 ContentProvider 暴露一份只读的开关快照。
 */
class ModuleConfigProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val ctx = context ?: return null
        // 只允许 system_server 和本应用自己读取：
        // 否则任意应用都能拉到配置，甚至反复触发 markHookSeen 伪造"模块生效"
        val caller = Binder.getCallingUid()
        if (caller != Process.SYSTEM_UID && caller != Process.myUid()) return null
        return when (method) {
            "get" -> {
                Config.markHookSeen(ctx)
                Config.toBundle(ctx)
            }
            else -> null
        }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0
}
