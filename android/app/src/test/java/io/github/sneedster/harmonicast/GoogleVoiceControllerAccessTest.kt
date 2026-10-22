package io.github.sneedster.harmonicast

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.Signature
import android.content.pm.SigningInfo
import android.os.Process
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowSigningInfo

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GoogleVoiceControllerAccessTest {
    // Public certificate from the verified Google APK's signing lineage. SHA-256 matches
    // android/uamp common/src/main/res/xml/allowed_media_browser_callers.xml (Google release).
    private val production = Signature(javaClass.getResourceAsStream("/google-media-release.der")!!.readBytes())
    private val other = Signature(byteArrayOf(1, 2, 3))

    @Test fun googleVoiceControllerAcceptsProductionAndRotatedSigningHistory() {
        val context: Context = RuntimeEnvironment.getApplication()
        val uid = Process.myUid() + 100
        install(context, MediaControllerAccess.GOOGLE_VOICE_SEARCH, uid, arrayOf(production))
        assertTrue(MediaControllerAccess.allowed(context, MediaControllerAccess.GOOGLE_VOICE_SEARCH, uid))
        install(context, MediaControllerAccess.GOOGLE_VOICE_SEARCH, uid, arrayOf(other), arrayOf(production, other))
        assertTrue(MediaControllerAccess.allowed(context, MediaControllerAccess.GOOGLE_VOICE_SEARCH, uid))
    }

    @Test fun wrongSignerWrongUidUnrelatedPackageAndMultipleSignersStayDenied() {
        val context: Context = RuntimeEnvironment.getApplication()
        val uid = Process.myUid() + 100
        install(context, MediaControllerAccess.GOOGLE_VOICE_SEARCH, uid, arrayOf(other))
        assertFalse(MediaControllerAccess.allowed(context, MediaControllerAccess.GOOGLE_VOICE_SEARCH, uid))
        install(context, MediaControllerAccess.GOOGLE_VOICE_SEARCH, uid, arrayOf(production))
        assertFalse(MediaControllerAccess.allowed(context, MediaControllerAccess.GOOGLE_VOICE_SEARCH, uid + 1))
        install(context, "org.example.foreign", uid + 2, arrayOf(production))
        assertFalse(MediaControllerAccess.allowed(context, "org.example.foreign", uid + 2))
        install(context, MediaControllerAccess.GOOGLE_VOICE_SEARCH, uid, arrayOf(production, other))
        assertFalse(MediaControllerAccess.allowed(context, MediaControllerAccess.GOOGLE_VOICE_SEARCH, uid))
    }

    private fun install(context: Context, name: String, packageUid: Int, signatures: Array<Signature>, history: Array<Signature>? = null) {
        val signing = Shadow.newInstanceOf(SigningInfo::class.java)
        Shadow.extract<ShadowSigningInfo>(signing).apply {
            setSignatures(signatures)
            history?.let { setPastSigningCertificates(it) }
        }
        shadowOf(context.packageManager).installPackage(PackageInfo().apply {
            packageName = name
            applicationInfo = ApplicationInfo().apply { packageName = name; uid = packageUid }
            signingInfo = signing
        })
    }
}
