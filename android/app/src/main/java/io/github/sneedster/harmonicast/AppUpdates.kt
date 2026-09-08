package io.github.sneedster.harmonicast

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

internal data class AppRelease(val version: String, val notes: String, val url: String, val size: Long, val sha256: String)
internal fun versionParts(value: String): List<Int>? = Regex("^v?(\\d+)\\.(\\d+)\\.(\\d+)$").matchEntire(value)
    ?.groupValues?.drop(1)?.map { it.toIntOrNull() ?: return null }
internal fun newerVersion(candidate: String, current: String): Boolean {
    val a = versionParts(candidate) ?: return false
    val b = versionParts(current) ?: return false
    for (i in a.indices) if (a[i] != b[i]) return a[i] > b[i]
    return false
}
internal fun parseAppRelease(json: String, current: String): AppRelease? {
    val release = JSONObject(json)
    if (release.optBoolean("draft") || release.optBoolean("prerelease")) return null
    val version = release.getString("tag_name")
    if (!newerVersion(version, current)) return null
    val assets = release.getJSONArray("assets")
    val matches = (0 until assets.length()).map { assets.getJSONObject(it) }
        .filter { it.optString("name") == "harmonicast-${version.removePrefix("v")}.apk" }
    require(matches.size == 1) { "This release has no compatible APK." }
    val apk = matches.single()
    val url = apk.getString("browser_download_url")
    require(url == "https://github.com/sneedster/harmonicast/releases/download/$version/${apk.getString("name")}") { "Unexpected update download location." }
    require(apk.optString("digest").startsWith("sha256:")) { "This release has no SHA-256 checksum." }
    val digest = apk.optString("digest").removePrefix("sha256:")
    require(digest.matches(Regex("[0-9a-fA-F]{64}"))) { "This release has no valid SHA-256 checksum." }
    val size = apk.getLong("size")
    require(size in 1..150_000_000) { "Invalid APK size." }
    return AppRelease(version.removePrefix("v"), release.optString("body").take(12000), url, size, digest.lowercase())
}

internal fun validateUpdateIdentity(packageName: String, expectedPackage: String, version: String?, expectedVersion: String,
    versionCode: Long, installedCode: Long, signers: Set<String>, installedSigners: Set<String>) {
    check(packageName == expectedPackage) { "APK belongs to another app." }
    check(version == expectedVersion && versionCode > installedCode) { "APK is not a newer version." }
    check(installedSigners.isNotEmpty() && signers == installedSigners) { "APK signing certificate does not match this installation." }
}

class AppUpdateViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("updates", 0)
    private val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.MINUTES).followSslRedirects(false).build()
    var automatic by mutableStateOf(prefs.getBoolean("automatic", false)); private set
    internal var release by mutableStateOf<AppRelease?>(null); private set
    var message by mutableStateOf(""); private set
    var busy by mutableStateOf(false); private set
    var progress by mutableFloatStateOf(0f); private set
    var downloaded by mutableStateOf(false); private set
    var showPrompt by mutableStateOf(false)
    private var job: Job? = null
    private val apk get() = File(getApplication<Application>().cacheDir, "updates/update.apk")
    init {
        apk.parentFile?.mkdirs()
        apk.delete()
        if (automatic && System.currentTimeMillis() - prefs.getLong("lastCheck", 0) > 24 * 60 * 60 * 1000L) check()
    }
    fun updateAutomatic(value: Boolean) { automatic = value; prefs.edit().putBoolean("automatic", value).apply() }
    fun check() {
        if (busy) return
        busy = true; message = "Checking GitHub…"
        prefs.edit().putLong("lastCheck", System.currentTimeMillis()).apply()
        job = viewModelScope.launch {
            try {
                val found = withContext(Dispatchers.IO) {
                    http.newCall(Request.Builder().url("https://api.github.com/repos/sneedster/harmonicast/releases/latest")
                        .header("Accept", "application/vnd.github+json").header("User-Agent", "Harmonicast/${BuildConfig.VERSION_NAME}").build()).execute().use {
                        if (it.code == 403 || it.code == 429) error("GitHub check limit reached. Try again later.")
                        check(it.isSuccessful) { "Could not check GitHub (${it.code})." }
                        parseAppRelease(it.body?.string() ?: error("Empty GitHub response."), BuildConfig.VERSION_NAME)
                    }
                }
                apk.delete(); downloaded = false; release = found
                prefs.edit().putLong("lastCheck", System.currentTimeMillis()).apply()
                message = if (found == null) "You're up to date." else "Version ${found.version} is available."
                if (found != null) showPrompt = true
            } catch (e: CancellationException) { message = "Update check cancelled."; throw e }
            catch (e: Exception) { message = e.message ?: "Update check failed. Try again." }
            finally { busy = false }
        }
    }
    fun download() {
        val target = release ?: return
        if (busy) return
        busy = true; downloaded = false; progress = 0f; message = "Downloading update…"
        job = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val partial = File(apk.parentFile, "update.part")
                    try {
                        http.newCall(Request.Builder().url(target.url).build()).execute().use { response ->
                            check(response.isSuccessful) { "Download failed (${response.code})." }
                            val body = response.body ?: error("Empty download.")
                            val digest = MessageDigest.getInstance("SHA-256")
                            body.byteStream().use { input -> partial.outputStream().use { output ->
                                val buffer = ByteArray(65536); var total = 0L
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    val count = input.read(buffer); if (count < 0) break
                                    total += count
                                    check(total <= target.size) { "APK size does not match the release." }
                                    digest.update(buffer, 0, count); output.write(buffer, 0, count)
                                    withContext(Dispatchers.Main) { progress = total.toFloat() / target.size }
                                }
                                check(total == target.size) { "Incomplete APK download. Try again." }
                            } }
                            check(digest.digest().joinToString("") { "%02x".format(it) } == target.sha256) { "APK checksum verification failed." }
                        }
                        verifyApk(partial, target)
                        apk.delete(); check(partial.renameTo(apk)) { "Could not save the update." }
                    } finally { partial.delete() }
                }
                downloaded = true; message = "Update verified. Ready to install."
            } catch (e: CancellationException) { message = "Download cancelled."; throw e }
            catch (e: Exception) { message = e.message ?: "Download failed. Try again."; apk.delete() }
            finally { busy = false }
        }
    }
    @Suppress("DEPRECATION")
    private fun verifyApk(file: File, target: AppRelease) {
        val context = getApplication<Application>(); val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val candidate = pm.getPackageArchiveInfo(file.path, flags) ?: error("Invalid APK.")
        val installed = pm.getPackageInfo(context.packageName, flags)
        check((candidate.applicationInfo?.minSdkVersion ?: Int.MAX_VALUE) <= Build.VERSION.SDK_INT) { "This update requires a newer Android version." }
        fun signatures(info: android.content.pm.PackageInfo) = (if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures)
            ?.map { it.toCharsString() }?.toSet().orEmpty()
        validateUpdateIdentity(candidate.packageName, context.packageName, candidate.versionName, target.version,
            PackageInfoCompat.getLongVersionCode(candidate), PackageInfoCompat.getLongVersionCode(installed),
            signatures(candidate), signatures(installed))
    }
    fun cancel() { job?.cancel() }
    fun install(context: android.content.Context) {
        val target = release ?: return
        if (!downloaded || busy) return
        try {
            if (!context.packageManager.canRequestPackageInstalls()) {
                message = "Allow Harmonicast to install apps, then return and tap Install update."
                context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
                return
            }
            verifyApk(apk, target)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
            context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            message = "Confirm the update in Android's installer. If cancelled, you can try again."
            showPrompt = false
        } catch (e: Exception) { message = e.message ?: "Could not open Android's installer." }
    }
}

@Composable internal fun UpdateSettings() {
    val vm: AppUpdateViewModel = viewModel()
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("App updates", style = MaterialTheme.typography.titleMedium)
            Text("Installed version ${BuildConfig.VERSION_NAME}")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Check automatically on launch", Modifier.weight(1f))
                Switch(vm.automatic, vm::updateAutomatic, modifier = Modifier.tvFocusFeedback())
            }
            Text("Checks GitHub at most once a day. Downloads only when you choose.", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { vm.check() }, modifier = Modifier.tvFocusFeedback()) { Text("Check for updates") }
            UpdateActions(vm)
        }
    }
}
@Composable private fun UpdateActions(vm: AppUpdateViewModel) {
    val context = LocalContext.current
    if (vm.message.isNotBlank()) Text(vm.message)
    if (vm.busy) { LinearProgressIndicator(progress = { vm.progress }, modifier = Modifier.fillMaxWidth()); TextButton(onClick = vm::cancel, modifier = Modifier.tvFocusFeedback()) { Text("Cancel") } }
    vm.release?.let { release ->
        if (!vm.busy) {
            if (vm.downloaded) {
                Text("Android will ask you to confirm. Installing restarts Harmonicast.")
                Button(onClick = { vm.install(context) }, modifier = Modifier.tvFocusFeedback()) { Text("Install update") }
            }
            else Button(onClick = vm::download, modifier = Modifier.tvFocusFeedback()) { Text("Download ${release.version}") }
            TextButton(onClick = { vm.showPrompt = true }, modifier = Modifier.tvFocusFeedback()) { Text("Release notes") }
        }
    }
}
@Composable internal fun UpdatePrompt() {
    val vm: AppUpdateViewModel = viewModel()
    if (vm.showPrompt) vm.release?.let { release ->
        FocusRestoringAlertDialog(onDismissRequest = { vm.showPrompt = false }, title = { Text("Harmonicast ${release.version}") },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(release.notes.ifBlank { "A new version is available." }); UpdateActions(vm)
            } }, confirmButton = { TextButton(onClick = { vm.showPrompt = false }, modifier = Modifier.tvFocusFeedback()) { Text("Close") } })
    }
}
