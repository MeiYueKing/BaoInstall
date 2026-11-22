package bao.omk.installer

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import java.io.File
import java.security.MessageDigest

data class PackageMeta(
    val packageName: String,
    val label: String,
    val versionName: String?,
    val versionCode: Long,
    val icon: Drawable?,
    val splitCount: Int,
    val minSdk: Int,
    val targetSdk: Int,
    val installedVersionCode: Long?,
    val installedVersionName: String?,
    val signatureChanged: Boolean,
    val permissions: List<String>,
) {
    val isUpgrade: Boolean get() = installedVersionCode != null && versionCode > installedVersionCode
    val isDowngrade: Boolean get() = installedVersionCode != null && versionCode < installedVersionCode
    val isReinstall: Boolean get() = installedVersionCode != null && versionCode == installedVersionCode
}

object PackageInspector {

    private const val FLAGS = PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_PERMISSIONS

    fun inspect(ctx: Context, baseApk: File, fileCount: Int): PackageMeta? {
        val pm = ctx.packageManager
        val archive = runCatching { pm.getPackageArchiveInfo(baseApk.absolutePath, FLAGS) }.getOrNull()
            ?: return null
        val ai = archive.applicationInfo ?: return null
        ai.sourceDir = baseApk.absolutePath
        ai.publicSourceDir = baseApk.absolutePath

        val label = runCatching { pm.getApplicationLabel(ai).toString() }.getOrDefault(archive.packageName)
        val icon = runCatching { ai.loadIcon(pm) }.getOrNull()
        val newDigests = digests(archive)

        val installed = runCatching {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(archive.packageName, FLAGS)
        }.getOrNull()

        val signatureChanged = if (installed == null) {
            false
        } else {
            val old = digests(installed)
            old.isNotEmpty() && newDigests.isNotEmpty() && old.intersect(newDigests).isEmpty()
        }

        return PackageMeta(
            packageName = archive.packageName,
            label = label,
            versionName = archive.versionName,
            versionCode = archive.longVersionCode,
            icon = icon,
            splitCount = maxOf(archive.splitNames?.size ?: 0, fileCount - 1),
            minSdk = ai.minSdkVersion,
            targetSdk = ai.targetSdkVersion,
            installedVersionCode = installed?.longVersionCode,
            installedVersionName = installed?.versionName,
            signatureChanged = signatureChanged,
            permissions = archive.requestedPermissions?.toList().orEmpty(),
        )
    }

    private fun digests(info: android.content.pm.PackageInfo): Set<String> {
        val signers = info.signingInfo?.apkContentsSigners ?: return emptySet()
        return signers.map { sig ->
            MessageDigest.getInstance("SHA-256").digest(sig.toByteArray())
                .joinToString("") { "%02x".format(it) }
        }.toSet()
    }
}
