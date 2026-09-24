package io.github.sneedster.harmonicast

import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.Executor

/** Main-thread owner for one local playback connection and commands waiting for it. */
internal class PlaybackConnection<T>(
    private val create: () -> ListenableFuture<T>,
    private val connected: (T) -> Boolean,
    private val release: (T) -> Unit,
    private val changed: (T?) -> Unit,
    private val failed: (Exception) -> Unit,
    private val executor: Executor,
) {
    private var current: T? = null
    private var pending: ListenableFuture<T>? = null
    private val commands = mutableListOf<(T) -> Unit>()
    private var generation = 0

    fun connect(command: ((T) -> Unit)? = null) {
        current?.let { value ->
            if (connected(value)) { command?.invoke(value); return }
            release(value)
            current = null
            changed(null)
        }
        command?.let { commands.add(it) }
        if (pending != null) return
        val request = ++generation
        try {
            val future = create()
            pending = future
            future.addListener({
                try {
                    val value = future.get()
                    if (request != generation) { release(value); return@addListener }
                    pending = null
                    if (!connected(value)) { release(value); error("Playback session disconnected") }
                    current = value
                    changed(value)
                    val waiting = commands.toList()
                    commands.clear()
                    waiting.forEach { it(value) }
                } catch (error: Exception) {
                    if (request == generation) { pending = null; commands.clear(); failed(error) }
                }
            }, executor)
        } catch (error: Exception) { pending = null; commands.clear(); failed(error) }
    }

    fun close() {
        generation++
        pending = null
        commands.clear()
        current?.let(release)
        current = null
        changed(null)
    }
}
