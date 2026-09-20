package io.github.sneedster.harmonicast

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import androidx.media3.session.MediaSession
import java.security.MessageDigest

/** Names alone (including Media3's Auto/notification helpers) are not caller authentication. */
internal object MediaControllerAccess {
    const val ANDROID_AUTO = "com.google.android.projection.gearhead"
    // Google production certificates published by android/uamp's allowed_media_browser_callers.xml.
    // Deliberately exclude its development and simulator keys. Includes the rotated release key.
    private val autoCertificates = setOf(
        "fdb00c43dbde8b51cb312aa81d3b5fa17713adb94b28f598d77f8eb89daceedf",
        "1ca8dcc0bed3cbd872d2cb791200c0292ca9975768a82d676b8b424fb65b5295",
    )
    private val systemControllers = setOf("android", "com.android.systemui", "com.android.bluetooth")

    fun allowed(context: Context, controller: MediaSession.ControllerInfo): Boolean =
        allowed(context, controller.packageName, controller.uid)

    @Suppress("DEPRECATION")
    fun allowed(context: Context, packageName: String, uid: Int): Boolean = runCatching {
        val pm = context.packageManager
        // Media3 reads the UID from Binder; the supplied package name must belong to that UID.
        if (uid < 0 || pm.getPackagesForUid(uid)?.contains(packageName) != true) return false
        if (packageName == context.packageName) return uid == Process.myUid()
        if (packageName == ANDROID_AUTO) {
            val signatures = if (Build.VERSION.SDK_INT >= 28) {
                val info = pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo
                    ?: return false
                if (info.hasMultipleSigners()) return false
                info.signingCertificateHistory
            } else pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES).signatures
            return signatures?.any { signature ->
                MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
                    .joinToString("") { "%02x".format(it) } in autoCertificates
            } == true
        }
        // Only the named OS media surfaces; neither arbitrary system apps nor notification listeners.
        packageName in systemControllers &&
            pm.getApplicationInfo(packageName, 0).flags and ApplicationInfo.FLAG_SYSTEM != 0 &&
            (pm.checkSignatures("android", packageName) == PackageManager.SIGNATURE_MATCH ||
                pm.checkPermission("android.permission.MEDIA_CONTENT_CONTROL", packageName) == PackageManager.PERMISSION_GRANTED)
    }.getOrDefault(false)

    fun legacySessionLookup(controller: MediaSession.ControllerInfo) =
        controller.uid < 0 && controller.controllerVersion == 0 &&
            controller.packageName == MediaSession.ControllerInfo.LEGACY_CONTROLLER_PACKAGE_NAME
}
