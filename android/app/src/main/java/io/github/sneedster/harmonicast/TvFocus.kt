package io.github.sneedster.harmonicast

import android.content.Intent
import android.content.pm.PackageManager
import android.speech.RecognizerIntent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.*
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.util.UUID

@Composable internal fun isTvDevice(): Boolean = LocalContext.current.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
internal class TvFocusMemory {
    var last: FocusRequester? = null
    var accepting = true
    val pages = mutableMapOf<String, TvFocusPage>()
}
internal class TvFocusPage {
    var last: String? = null
    val targets = mutableMapOf<String, FocusRequester>()
    fun restore() { last?.let { targets[it] }?.let { runCatching { it.requestFocus() } } }
}
private val LocalTvFocus = staticCompositionLocalOf { TvFocusMemory() }
private val LocalTvPage = staticCompositionLocalOf<TvFocusPage?> { null }

@Composable internal fun TvFocusRoot(content: @Composable () -> Unit) {
    val memory = remember { TvFocusMemory() }
    val television = isTvDevice()
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    var returnTo by remember { mutableStateOf<FocusRequester?>(null) }
    LaunchedEffect(windowFocused) {
        if (television) {
            if (!windowFocused) { returnTo = memory.last; memory.accepting = false }
            else {
                withFrameNanos { }
                returnTo?.let { runCatching { it.requestFocus() } }
                returnTo = null; memory.accepting = true
            }
        }
    }
    CompositionLocalProvider(LocalTvFocus provides memory) { content() }
}

@Composable internal fun TvFocusPage(key: String, active: Boolean = true, content: @Composable () -> Unit) {
    val memory = LocalTvFocus.current
    val page = remember(memory, key) { memory.pages.getOrPut(key) { TvFocusPage() } }
    val television = isTvDevice()
    LaunchedEffect(key, active) { if (television && active) { withFrameNanos { }; page.restore() } }
    CompositionLocalProvider(LocalTvPage provides page) { content() }
}

internal fun Modifier.tvFocusFeedback(): Modifier = composed {
    if (!isTvDevice()) return@composed this
    val memory = LocalTvFocus.current
    val page = LocalTvPage.current
    val id = rememberSaveable { UUID.randomUUID().toString() }
    val requester = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    val focusColor = MaterialTheme.colorScheme.primary
    DisposableEffect(page, id, requester) {
        page?.targets?.put(id, requester)
        onDispose { page?.targets?.remove(id) }
    }
    this.focusRequester(requester).onFocusChanged {
        focused = it.isFocused
        if (it.isFocused && memory.accepting) { memory.last = requester; page?.last = id }
    }.drawWithContent {
        drawContent()
        if (focused) {
            val inset = 2.dp.toPx()
            val bounds = Size((size.width - 2 * inset).coerceAtLeast(0f), (size.height - 2 * inset).coerceAtLeast(0f))
            drawRoundRect(focusColor.copy(alpha = .06f), Offset(inset, inset), bounds, CornerRadius(10.dp.toPx()))
            drawRoundRect(focusColor.copy(alpha = .9f), Offset(inset, inset), bounds, CornerRadius(10.dp.toPx()), style = Stroke(1.5.dp.toPx()))
        }
    }
}

@Composable internal fun FocusRestoringAlertDialog(onDismissRequest: () -> Unit, confirmButton: @Composable () -> Unit,
    dismissButton: (@Composable () -> Unit)? = null, title: (@Composable () -> Unit)? = null, text: (@Composable () -> Unit)? = null) {
    val parent = LocalTvFocus.current
    val target = remember { parent.last }
    val television = isTvDevice()
    // The parent scope survives this dialog; restore after the dialog's window closes.
    val restoreScope = LocalTvRestoreScope.current
    DisposableEffect(Unit) {
        onDispose { if (television) restoreScope?.launch { withFrameNanos { }; target?.let { runCatching { it.requestFocus() } } } }
    }
    CompositionLocalProvider(LocalTvFocus provides remember { TvFocusMemory() }, LocalTvPage provides null) {
        AlertDialog(onDismissRequest, confirmButton, dismissButton = dismissButton, title = title, text = text)
    }
}
private val LocalTvRestoreScope = staticCompositionLocalOf<kotlinx.coroutines.CoroutineScope?> { null }
@Composable internal fun TvFocusHost(content: @Composable () -> Unit) {
    val scope = rememberCoroutineScope()
    CompositionLocalProvider(LocalTvRestoreScope provides scope) { TvFocusRoot(content) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun RemoteTextField(value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier,
    label: (@Composable () -> Unit)? = null, leadingIcon: (@Composable () -> Unit)? = null,
    singleLine: Boolean = true, shape: Shape = RoundedCornerShape(16.dp),
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default, keyboardActions: KeyboardActions = KeyboardActions.Default,
    allowVoice: Boolean = true, visualTransformation: androidx.compose.ui.text.input.VisualTransformation = androidx.compose.ui.text.input.VisualTransformation.None) {
    val television = isTvDevice()
    var editing by remember { mutableStateOf(false) }
    var fieldFocused by remember { mutableStateOf(false) }
    var keyboardWasVisible by remember { mutableStateOf(false) }
    val keyboardVisible = WindowInsets.isImeVisible
    var showVoice by remember { mutableStateOf(false) }
    var voiceError by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val field = remember { FocusRequester() }
    val microphone = remember { FocusRequester() }
    LaunchedEffect(keyboardVisible) {
        if (television && keyboardWasVisible && !keyboardVisible && editing) {
            editing = false
            withFrameNanos { }
            field.requestFocus()
        }
        keyboardWasVisible = keyboardVisible
    }
    val recognizer = remember { Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_PROMPT, "Say a song, artist, or album") }
    val hasVoice = remember(context, television, allowVoice) { allowVoice && if (television) android.speech.SpeechRecognizer.isRecognitionAvailable(context) else recognizer.resolveActivity(context.packageManager) != null }
    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let(onValueChange)
        if (television) { editing = false; microphone.requestFocus() }
    }
    LaunchedEffect(editing) { if (television && editing) { withFrameNanos { }; field.requestFocus(); keyboard?.show() } }
    BackHandler(television && editing) { editing = false; keyboard?.hide() }
    if (showVoice) TvVoiceInput(onResult = onValueChange, onDismiss = { showVoice = false })
    // Keep the microphone outside the editor so D-pad events cannot be consumed by it.
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
    OutlinedTextField(value, onValueChange, modifier = Modifier.weight(1f).tvFocusFeedback().focusRequester(field)
        .onFocusChanged { fieldFocused = it.isFocused; if (!it.isFocused) editing = false }
        .onPreviewKeyEvent { event ->
            if (!television || editing || !fieldFocused) false
            else when (event.key) {
                Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> { if (event.type == KeyEventType.KeyUp) editing = true; true }
                Key.DirectionDown, Key.DirectionUp, Key.DirectionLeft, Key.DirectionRight -> {
                    if (event.type == KeyEventType.KeyDown) focusManager.moveFocus(when(event.key) {
                        Key.DirectionDown -> FocusDirection.Down; Key.DirectionUp -> FocusDirection.Up
                        Key.DirectionLeft -> FocusDirection.Left; else -> FocusDirection.Right })
                    true
                }
                else -> false
            }
        }, readOnly = television && !editing, label = label, leadingIcon = leadingIcon,
        singleLine = singleLine, shape = shape, visualTransformation = visualTransformation, keyboardOptions = keyboardOptions, keyboardActions = KeyboardActions(
            onSearch = { editing = false; keyboard?.hide(); keyboardActions.onSearch?.invoke(this) },
            onDone = keyboardActions.onDone, onGo = keyboardActions.onGo, onNext = keyboardActions.onNext,
            onPrevious = keyboardActions.onPrevious, onSend = keyboardActions.onSend),
        supportingText = if (voiceError != null) ({ Text(voiceError!!, Modifier.padding(bottom = 6.dp)) }) else if (television) ({ Text("Press OK to type" + if (hasVoice) " · microphone for voice" else "", Modifier.padding(bottom = 6.dp)) }) else null)
    if (hasVoice) IconButton(onClick = {
        keyboard?.hide(); editing = false; voiceError = null
        try { if (television) showVoice = true else voice.launch(recognizer) }
        catch (_: Exception) { voiceError = "Voice input is unavailable. Select the field to type." }
    }, modifier = Modifier.tvFocusFeedback().focusRequester(microphone)) { Icon(Icons.Default.Mic, "Voice input") }
    }
}
