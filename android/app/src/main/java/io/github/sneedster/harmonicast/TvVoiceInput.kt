package io.github.sneedster.harmonicast

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.Locale

/** Use the recognition service directly: SHIELD's recognition activity may not return results. */
@Composable internal fun TvVoiceInput(onResult: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val resultCallback by rememberUpdatedState(onResult)
    val dismissCallback by rememberUpdatedState(onDismiss)
    var permitted by remember { mutableStateOf(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) }
    var attempt by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf("Preparing microphone…") }
    var listening by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        permitted = it
        if (!it) status = "Allow microphone access to use voice input."
    }
    LaunchedEffect(Unit) { if (!permitted) permission.launch(Manifest.permission.RECORD_AUDIO) }
    DisposableEffect(permitted, attempt) {
        val recognizer = if (permitted) SpeechRecognizer.createSpeechRecognizer(context) else null
        var timeout: Runnable? = null
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        fun finishListening() { listening = false; timeout?.let(handler::removeCallbacks) }
        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { listening = true; status = "Speak into your remote." }
            override fun onBeginningOfSpeech() { status = "Hearing you…" }
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { status = "Recognizing…" }
            override fun onError(error: Int) {
                finishListening()
                status = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech heard. Try again and speak into your remote."
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is required."
                    SpeechRecognizer.ERROR_AUDIO -> "The remote microphone could not be opened."
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Voice recognition could not connect. Try again."
                    else -> "Voice recognition stopped. Try again."
                }
            }
            override fun onResults(results: Bundle?) {
                finishListening()
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!text.isNullOrBlank()) { resultCallback(text); dismissCallback() }
                else status = "No speech heard. Try again."
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        if (recognizer != null) {
            try {
                listening = true
                recognizer.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag()))
                timeout = Runnable { recognizer.cancel(); listening = false; status = "No speech received. Try again." }
                handler.postDelayed(timeout!!, 15000)
            } catch (_: Exception) { listening = false; status = "Voice recognition is unavailable." }
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) { recognizer?.cancel(); finishListening(); dismissCallback() }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); timeout?.let(handler::removeCallbacks); recognizer?.destroy() }
    }
    FocusRestoringAlertDialog(onDismissRequest = onDismiss, title = { Text("Voice input") }, text = { Text(status) },
        confirmButton = { TextButton(onClick = { if (permitted) attempt++ else permission.launch(Manifest.permission.RECORD_AUDIO) }, enabled = !listening, modifier = Modifier.tvFocusFeedback()) { Text("Try again") } },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.tvFocusFeedback()) { Text("Cancel") } })
}
