package com.resonance.android

import android.app.PendingIntent
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSession.ConnectionResult
import androidx.media3.session.CommandButton
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicReference

class ResonanceMediaService : MediaLibraryService() {
    private lateinit var player: ExoPlayer
    private var mediaLibrarySession: MediaLibrarySession? = null
    private lateinit var api: Api
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var webSocket: okhttp3.WebSocket? = null
    private val currentIsAuto = AtomicReference(false)
    private var positionSaveJob: kotlinx.coroutines.Job? = null
    private var previousMediaItem: MediaItem? = null

    companion object {
        private const val ROOT_ID = "resonance:root"
        private const val PLAY_RANDOM_ID = "resonance:play-random"
        private const val QUEUE_ID = "resonance:queue"
        private const val COMMAND_PLAY_SIMILAR = "android.resonance.PLAY_SIMILAR"
        private val PLAY_SIMILAR_COMMAND = SessionCommand(COMMAND_PLAY_SIMILAR, Bundle())
    }

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        val exoPlayer = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
        api = Api(getSharedPreferences("resonance", Context.MODE_PRIVATE))

        exoPlayer.addListener(object : Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                Log.e(
                    "ResonanceMedia",
                    "Playback failed (${error.errorCodeName}): ${error.message ?: "no message"}",
                    error,
                )
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    advance("ended")
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                syncCurrentPlaybackState(isPlaying)
                if (isPlaying) startPositionSaving() else stopPositionSaving()
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                // Android Auto can start a search result without toggling the
                // playing flag again. Publish the item transition as well so
                // the phone UI immediately reflects the selected track.
                val previous = previousMediaItem
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && previous != null && mediaItem != null) {
                    consumeAutomaticQueueTransition(previous, mediaItem)
                }
                previousMediaItem = mediaItem
                syncCurrentPlaybackState(player.isPlaying)
            }
        })

        // Use ForwardingPlayer so Media3 / Android Auto always sees Next / Skip as available
        val forwardingPlayer = object : ForwardingPlayer(exoPlayer) {
            override fun getAvailableCommands(): Player.Commands {
                return super.getAvailableCommands().buildUpon()
                    .add(COMMAND_SEEK_TO_NEXT)
                    .add(COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                    .build()
            }

            override fun isCommandAvailable(command: Int): Boolean {
                return command == COMMAND_SEEK_TO_NEXT ||
                       command == COMMAND_SEEK_TO_NEXT_MEDIA_ITEM ||
                       super.isCommandAvailable(command)
            }

            override fun hasNextMediaItem(): Boolean {
                return true
            }

            override fun seekToNext() {
                advance("skip")
            }

            override fun seekToNextMediaItem() {
                advance("skip")
            }
        }
        player = exoPlayer

        Log.d("ResonanceMedia", "Service created, base: ${api.base}, token length: ${api.token.length}")

        connectWebSocket()

        val callback = object : MediaLibrarySession.Callback {
            override fun onConnect(
                session: MediaSession,
                controller: MediaSession.ControllerInfo
            ): ConnectionResult {
                Log.d("ResonanceMedia", "onConnect from: ${controller.packageName}")

                val availableSessionCommands = ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                    .add(SessionCommand.COMMAND_CODE_LIBRARY_SEARCH)
                    .add(SessionCommand.COMMAND_CODE_LIBRARY_GET_CHILDREN)
                    .add(SessionCommand.COMMAND_CODE_LIBRARY_GET_ITEM)
                    .add(SessionCommand.COMMAND_CODE_LIBRARY_GET_LIBRARY_ROOT)
                    .add(PLAY_SIMILAR_COMMAND)
                    .build()

                val availablePlayerCommands = ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
                    .add(Player.COMMAND_PLAY_PAUSE)
                    .add(Player.COMMAND_STOP)
                    .add(Player.COMMAND_SEEK_TO_NEXT)
                    .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                    .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                    .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                    .add(Player.COMMAND_GET_METADATA)
                    .add(Player.COMMAND_GET_TIMELINE)
                    .add(Player.COMMAND_GET_CURRENT_MEDIA_ITEM)
                    .build()

                return ConnectionResult.AcceptedResultBuilder(session)
                    .setAvailableSessionCommands(availableSessionCommands)
                    .setAvailablePlayerCommands(availablePlayerCommands)
                    .build()
            }

            override fun onGetLibraryRoot(
                session: MediaLibrarySession,
                browser: MediaSession.ControllerInfo,
                params: LibraryParams?
            ): ListenableFuture<LibraryResult<MediaItem>> {
                val root = MediaItem.Builder()
                    .setMediaId(ROOT_ID)
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setIsBrowsable(true)
                            .setIsPlayable(false)
                            .setTitle("Resonance")
                            .setDisplayTitle("Resonance")
                            .setMediaType(MediaMetadata.MEDIA_TYPE_PLAYLIST)
                            .build()
                    )
                    .build()
                return Futures.immediateFuture(LibraryResult.ofItem(root, params))
            }

            override fun onGetChildren(
                session: MediaLibrarySession,
                browser: MediaSession.ControllerInfo,
                parentId: String,
                page: Int,
                pageSize: Int,
                params: LibraryParams?
            ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
                if (parentId == ROOT_ID) {
                    val items = listOf(
                        MediaItem.Builder().setMediaId(PLAY_RANDOM_ID).setMediaMetadata(
                            MediaMetadata.Builder().setTitle("Play random music").setDisplayTitle("Play random music")
                                .setSubtitle("Start the shared Resonance queue").setIsPlayable(true).setIsBrowsable(false)
                                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC).build()
                        ).build(),
                        MediaItem.Builder().setMediaId(QUEUE_ID).setMediaMetadata(
                            MediaMetadata.Builder().setTitle("Request queue").setDisplayTitle("Request queue")
                                .setSubtitle("Tracks added by listeners").setIsPlayable(false).setIsBrowsable(true)
                                .setMediaType(MediaMetadata.MEDIA_TYPE_PLAYLIST).build()
                        ).build(),
                    )
                    return Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.copyOf(items), params))
                }
                if (parentId == QUEUE_ID) {
                    return scope.future {
                        try {
                            val items = fetchQueueSongs().map(::createMediaItem)
                            LibraryResult.ofItemList(ImmutableList.copyOf(items), params)
                        } catch (e: Exception) {
                            Log.e("ResonanceMedia", "Get children failed", e)
                            LibraryResult.ofError(SessionError.ERROR_SESSION_AUTHENTICATION_EXPIRED)
                        }
                    }
                }
                return Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.of(), params))
            }

            override fun onGetItem(
                session: MediaLibrarySession,
                browser: MediaSession.ControllerInfo,
                mediaId: String
            ): ListenableFuture<LibraryResult<MediaItem>> {
                if (mediaId == ROOT_ID) {
                    return Futures.immediateFuture(LibraryResult.ofItem(
                        MediaItem.Builder()
                            .setMediaId(ROOT_ID)
                            .setMediaMetadata(
                                MediaMetadata.Builder()
                                    .setIsBrowsable(true)
                                    .setIsPlayable(false)
                                    .setTitle("Resonance")
                                    .setMediaType(MediaMetadata.MEDIA_TYPE_PLAYLIST)
                                    .build()
                            )
                            .build(),
                        null
                    ))
                }
                return scope.future {
                    try {
                        val npJson = api.json("now-playing")
                        val np = JSONObject(npJson)
                        val songObj = np.optJSONObject("song")
                        if (songObj != null && songObj.optString("id") == mediaId) {
                            val song = Song(
                                songObj.optString("id"),
                                songObj.optString("title"),
                                songObj.optString("artist"),
                                songObj.optString("album"),
                                songObj.optInt("duration"),
                                songObj.optString("coverArt")
                            )
                            LibraryResult.ofItem(createMediaItem(song), null)
                        } else {
                            val song = Song(mediaId, mediaId, "", "", 0, "")
                            LibraryResult.ofItem(createMediaItem(song), null)
                        }
                    } catch (e: Exception) {
                        Log.e("ResonanceMedia", "onGetItem failed", e)
                        LibraryResult.ofError(SessionError.ERROR_UNKNOWN)
                    }
                }
            }

            override fun onAddMediaItems(
                mediaSession: MediaSession,
                controller: MediaSession.ControllerInfo,
                mediaItems: MutableList<MediaItem>
            ): ListenableFuture<MutableList<MediaItem>> {
                Log.d("ResonanceMedia", "onAddMediaItems: ${mediaItems.size} items")
                return scope.future {
                    // Android Auto often sends only a media ID when a user
                    // chooses an item from a browsed queue. Resolve it back
                    // to the shared queue entry before building the playable
                    // item; otherwise title, artist and cover art are lost
                    // when now-playing is published.
                    val queuedSongs = if (mediaItems.any {
                        val title = it.mediaMetadata.title?.toString()
                        title.isNullOrBlank() || title == it.mediaId
                    }) {
                        fetchQueueSongs().associateBy { it.id }
                    } else emptyMap()
                    mediaItems.map { item ->
                        val metadata = item.mediaMetadata
                        val title = metadata.title?.toString()
                        val needsResolution = title.isNullOrBlank() || title == item.mediaId
                        val song = queuedSongs[item.mediaId]
                            ?: (if (needsResolution) fetchPlexSong(item.mediaId) else null)
                            ?: Song(
                                item.mediaId,
                                title.orEmpty().ifBlank { item.mediaId },
                                metadata.artist?.toString().orEmpty(),
                                metadata.albumTitle?.toString().orEmpty(),
                            )
                        createMediaItem(song)
                    }.toMutableList()
                }
            }

            override fun onSetMediaItems(
                mediaSession: MediaSession,
                controller: MediaSession.ControllerInfo,
                mediaItems: MutableList<MediaItem>,
                startIndex: Int,
                startPositionMs: Long,
            ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
                if (mediaItems.size == 1 && mediaItems[0].mediaId == PLAY_RANDOM_ID) {
                    return scope.future {
                        val item = dequeueRandomItem()
                        MediaSession.MediaItemsWithStartPosition(item?.let(::listOf) ?: emptyList(), 0, 0)
                    }
                }
                return super.onSetMediaItems(mediaSession, controller, mediaItems, startIndex, startPositionMs)
            }

            override fun onPlaybackResumption(
                mediaSession: MediaSession,
                controller: MediaSession.ControllerInfo,
            ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = scope.future {
                val item = dequeueRandomItem()
                MediaSession.MediaItemsWithStartPosition(item?.let(::listOf) ?: emptyList(), 0, 0)
            }

            override fun onCustomCommand(
                session: MediaSession,
                controller: MediaSession.ControllerInfo,
                customCommand: SessionCommand,
                args: Bundle,
            ): ListenableFuture<SessionResult> {
                if (customCommand.customAction != COMMAND_PLAY_SIMILAR) {
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
                }
                Log.d("ResonanceMedia", "Android Auto requested Track Radio queue")
                return scope.future {
                    try {
                        val result = JSONObject(api.json("queue/similar", "POST", JSONObject()))
                        val added = result.optInt("added", 0)
                        Log.d("ResonanceMedia", "Queued $added Track Radio songs from Android Auto")
                        SessionResult(SessionResult.RESULT_SUCCESS, Bundle().apply { putInt("added", added) })
                    } catch (e: Exception) {
                        Log.e("ResonanceMedia", "Failed to queue similar tracks", e)
                        SessionResult(SessionResult.RESULT_ERROR_UNKNOWN)
                    }
                }
            }

            override fun onSearch(
                session: MediaLibrarySession,
                browser: MediaSession.ControllerInfo,
                query: String,
                params: LibraryParams?
            ): ListenableFuture<LibraryResult<Void>> {
                Log.d("ResonanceMedia", "onSearch for: $query")
                scope.launch {
                    try {
                        val searchJson = api.json("search?q=" + java.net.URLEncoder.encode(query, "UTF-8"))
                        val array = JSONArray(searchJson)
                        Log.d("ResonanceMedia", "Search results found: ${array.length()}")
                        session.notifySearchResultChanged(browser, query, array.length(), params)
                    } catch (e: Exception) {
                        Log.e("ResonanceMedia", "Search failed", e)
                        session.notifySearchResultChanged(browser, query, 0, params)
                    }
                }
                return Futures.immediateFuture(LibraryResult.ofVoid())
            }

            override fun onGetSearchResult(
                session: MediaLibrarySession,
                browser: MediaSession.ControllerInfo,
                query: String,
                page: Int,
                pageSize: Int,
                params: LibraryParams?
            ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
                Log.d("ResonanceMedia", "onGetSearchResult for: $query")
                return scope.future {
                    try {
                        val searchJson = api.json("search?q=" + java.net.URLEncoder.encode(query, "UTF-8"))
                        val array = JSONArray(searchJson)
                        val items = mutableListOf<MediaItem>()
                        for (i in 0 until array.length()) {
                            val o = array.getJSONObject(i)
                            val song = Song(o.optString("id"), o.optString("title"), o.optString("artist"), o.optString("album"), o.optInt("duration"), o.optString("coverArt"))
                            items.add(createMediaItem(song))
                        }
                        Log.d("ResonanceMedia", "Returning ${items.size} search items")
                        LibraryResult.ofItemList(ImmutableList.copyOf(items), params)
                    } catch (e: Exception) {
                        Log.e("ResonanceMedia", "Get search result failed", e)
                        LibraryResult.ofError(SessionError.ERROR_UNKNOWN)
                    }
                }
            }

        }

        val sessionActivityIntent = packageManager.getLaunchIntentForPackage(packageName)
        val sessionActivityPendingIntent = sessionActivityIntent?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }

        mediaLibrarySession = MediaLibrarySession.Builder(this, forwardingPlayer, callback).apply {
            if (sessionActivityPendingIntent != null) {
                setSessionActivity(sessionActivityPendingIntent)
            }
            setCustomLayout(listOf(
                CommandButton.Builder(CommandButton.ICON_RADIO)
                    .setSessionCommand(PLAY_SIMILAR_COMMAND)
                    .setDisplayName("Queue Track Radio")
                    .setSlots(CommandButton.SLOT_OVERFLOW)
                    .build(),
            ))
        }.build()

        Log.d("ResonanceMedia", "Session built successfully")
    }

    private fun connectWebSocket() {
        if (api.base.isEmpty() || api.token.isEmpty()) return
        webSocket?.close(1000, null)
        webSocket = api.websocket(onMessage = { text ->
            try {
                val msg = JSONObject(text)
                val type = msg.optString("type")
                Log.d("ResonanceMedia", "WS message: $type")
                if (type == "force_skip") {
                    advance("skip")
                } else if (type == "queue") {
                    refreshAndroidAutoQueue()
                } else if (type == "player_session") {
                    scope.launch {
                        try {
                            val statusJson = api.json("player/status")
                            val isActive = JSONObject(statusJson).optBoolean("isActivePlayer", false)
                            if (!isActive) {
                                Log.d("ResonanceMedia", "Another device took over playback — pausing")
                                player.pause()
                                stopPositionSaving()
                            }
                        } catch (e: Exception) {
                            Log.e("ResonanceMedia", "Failed to check player status after session change", e)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("ResonanceMedia", "Failed to parse WS message", e)
            }
        })
    }

    /**
     * Android Auto has two independent queue surfaces: the browsable Request
     * queue and its player “Up next” screen. The latter reads ExoPlayer's
     * timeline, so both need to be updated when the shared queue changes.
     */
    private fun refreshAndroidAutoQueue() {
        scope.launch {
            try {
                val songs = fetchQueueSongs()
                mediaLibrarySession?.notifyChildrenChanged(QUEUE_ID, songs.size, null)
                synchronizePlayerTimeline(songs)
            } catch (e: Exception) {
                Log.e("ResonanceMedia", "Failed to refresh Android Auto queue", e)
            }
        }
    }

    private suspend fun fetchQueueSongs(): List<Song> {
        val array = JSONArray(api.json("queue"))
        return buildList {
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                add(Song(
                    o.optString("id"), o.optString("title"), o.optString("artist"),
                    o.optString("album"), o.optInt("duration"), o.optString("coverArt"),
                ))
            }
        }
    }

    private suspend fun fetchPlexSong(id: String): Song? = try {
        val item = JSONObject(api.json("plex/tracks/${java.net.URLEncoder.encode(id, "UTF-8")}"))
        val title = item.optString("title")
        if (title.isBlank()) null else Song(
            item.optString("id", id), title, item.optString("artist"), item.optString("album"),
            item.optInt("duration"), item.optString("coverArt"),
        )
    } catch (e: Exception) {
        Log.w("ResonanceMedia", "Could not resolve Plex metadata for $id", e)
        null
    }

    private fun synchronizePlayerTimeline(queue: List<Song>) {
        val current = player.currentMediaItem ?: return
        val desiredItems = listOf(current) + queue.map(::createMediaItem)
        val currentIds = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }
        val desiredIds = desiredItems.map { it.mediaId }
        if (player.currentMediaItemIndex == 0 && currentIds == desiredIds) return

        val position = player.currentPosition
        val wasPlaying = player.isPlaying
        player.setMediaItems(desiredItems, 0, position)
        player.prepare()
        if (wasPlaying) player.play()
    }

    /**
     * The first item in the player timeline is already out of the server
     * queue. When ExoPlayer advances to its next item automatically, remove
     * that matching head from Resonance, record the completed play, and let
     * the resulting queue broadcast refresh the timeline again.
     */
    private fun consumeAutomaticQueueTransition(previous: MediaItem, current: MediaItem) {
        scope.launch {
            try {
                val metadata = previous.mediaMetadata
                api.json(
                    "stats/play-event", "POST",
                    JSONObject()
                        .put("song_id", previous.mediaId)
                        .put("title", metadata.title ?: "")
                        .put("artist", metadata.artist ?: "")
                        .put("album", metadata.albumTitle ?: "")
                        .put("event", "complete")
                        .put("progress", 1)
                )
                api.json("scrobble", "POST", JSONObject().put("id", previous.mediaId).put("submission", true))

                val response = JSONObject(api.json("queue/dequeue", "POST", JSONObject()))
                val dequeued = response.optJSONObject("song")
                if (dequeued?.optString("id") != current.mediaId) {
                    Log.w("ResonanceMedia", "Automatic transition did not match the shared queue head")
                }
                currentIsAuto.set(!response.optBoolean("isManual", true))
                syncCurrentPlaybackState(player.isPlaying)
            } catch (e: Exception) {
                Log.e("ResonanceMedia", "Failed to consume Android Auto queue transition", e)
            }
        }
    }

    private fun syncCurrentPlaybackState(isPlaying: Boolean) {
        val item = player.currentMediaItem ?: return
        val songId = item.mediaId
        val title = item.mediaMetadata.title?.toString() ?: ""
        val artist = item.mediaMetadata.artist?.toString() ?: ""
        val album = item.mediaMetadata.albumTitle?.toString() ?: ""
        val coverArt = item.mediaMetadata.artworkUri?.toString()?.let {
            val idParam = it.substringAfter("cover-art/").substringBefore("?")
            java.net.URLDecoder.decode(idParam, "UTF-8")
        } ?: ""
        val isAuto = currentIsAuto.get()
        scope.launch {
            try {
                // The server deliberately clears the active session on a
                // restart. A device that is already playing must reclaim it
                // before it can restore the shared now-playing state.
                if (isPlaying) api.json("player/claim", "POST", JSONObject())
                api.json(
                    "now-playing", "POST",
                    JSONObject()
                        .put("song", JSONObject()
                            .put("id", songId)
                            .put("title", title)
                            .put("artist", artist)
                            .put("album", album)
                            .put("coverArt", coverArt))
                        .put("isPlaying", isPlaying)
                        .put("isAutoQueue", isAuto)
                )
            } catch (e: Exception) {
                Log.e("ResonanceMedia", "Failed to sync play state", e)
            }
        }
    }

    private fun startPositionSaving() {
        stopPositionSaving()
        positionSaveJob = scope.launch {
            while (true) {
                kotlinx.coroutines.delay(2000)
                // ExoPlayer reports milliseconds; the Resonance API stores
                // seconds so browser and Android hosts can resume consistently.
                val positionSeconds = player.currentPosition / 1000.0
                if (positionSeconds >= 0) {
                    try {
                        api.json(
                            "now-playing/position", "PUT",
                            JSONObject().put("position", positionSeconds)
                        )
                    } catch (e: Exception) {
                        Log.e("ResonanceMedia", "Failed to save position", e)
                    }
                }
            }
        }
    }

    private fun stopPositionSaving() {
        positionSaveJob?.cancel()
        positionSaveJob = null
    }

    private fun advance(reason: String) {
        scope.launch {
            try {
                val currentMediaItem = player.currentMediaItem
                if (currentMediaItem != null) {
                    val oldId = currentMediaItem.mediaId
                    val metadata = currentMediaItem.mediaMetadata
                    val progress = if (player.duration > 0) {
                        (player.currentPosition.toFloat() / player.duration).coerceIn(0f, 1f)
                    } else 0f

                    if (reason == "ended") {
                        api.json(
                            "stats/play-event", "POST",
                            JSONObject()
                                .put("song_id", oldId)
                                .put("title", metadata.title ?: "")
                                .put("artist", metadata.artist ?: "")
                                .put("album", metadata.albumTitle ?: "")
                                .put("event", "complete")
                                .put("progress", 1)
                        )
                        api.json(
                            "scrobble", "POST",
                            JSONObject().put("id", oldId).put("submission", true)
                        )
                    } else {
                        api.json(
                            "stats/play-event", "POST",
                            JSONObject()
                                .put("song_id", oldId)
                                .put("title", metadata.title ?: "")
                                .put("artist", metadata.artist ?: "")
                                .put("album", metadata.albumTitle ?: "")
                                .put("event", "skip")
                                .put("progress", progress)
                        )
                    }
                }

                val response = JSONObject(api.json("queue/dequeue", "POST", JSONObject()))
                val nextObj = response.optJSONObject("song")
                val isManual = response.optBoolean("isManual", true)

                if (nextObj != null) {
                    val song = Song(
                        nextObj.optString("id"),
                        nextObj.optString("title"),
                        nextObj.optString("artist"),
                        nextObj.optString("album"),
                        nextObj.optInt("duration"),
                        nextObj.optString("coverArt")
                    )
                    val isAuto = !isManual
                    currentIsAuto.set(isAuto)
                    api.json(
                        "now-playing", "POST",
                        JSONObject()
                            .put("song", JSONObject()
                                .put("id", song.id)
                                .put("title", song.title)
                                .put("artist", song.artist)
                                .put("album", song.album)
                                .put("coverArt", song.coverArt))
                            .put("isPlaying", true)
                            .put("isAutoQueue", isAuto)
                    )
                    api.json(
                        "scrobble", "POST",
                        JSONObject().put("id", song.id).put("submission", false)
                    )
                    // `/queue/dequeue` broadcasts before its HTTP response.
                    // Fetch the completed queue here and install the selected
                    // next song plus all remaining entries in one operation,
                    // so that earlier broadcast cannot collapse Auto's
                    // timeline to a single item.
                    val timeline = listOf(createMediaItem(song)) + fetchQueueSongs().map(::createMediaItem)
                    player.setMediaItems(timeline, 0, 0)
                    player.prepare()
                    player.play()
                } else {
                    currentIsAuto.set(false)
                    api.json(
                        "now-playing", "POST",
                        JSONObject().put("song", JSONObject.NULL).put("isPlaying", false)
                    )
                    player.stop()
                }
            } catch (e: Exception) {
                Log.e("ResonanceMedia", "advance failed", e)
            }
        }
    }

    /** Enables the shared auto queue and returns its next playable track. */
    private suspend fun dequeueRandomItem(): MediaItem? = try {
        api.json("jukebox", "POST", JSONObject().put("enabled", true))
        val response = JSONObject(api.json("queue/dequeue", "POST", JSONObject()))
        val next = response.optJSONObject("song") ?: return null
        currentIsAuto.set(!response.optBoolean("isManual", true))
        createMediaItem(Song(
            next.optString("id"), next.optString("title"), next.optString("artist"),
            next.optString("album"), next.optInt("duration"), next.optString("coverArt"),
        ))
    } catch (e: Exception) {
        Log.e("ResonanceMedia", "Failed to start random playback", e)
        null
    }

    private fun coverArtUri(coverArt: String): Uri? {
        if (coverArt.isEmpty()) return null
        return Uri.parse("${api.base}/api/cover-art/${java.net.URLEncoder.encode(coverArt, "UTF-8")}?token=${java.net.URLEncoder.encode(api.token, "UTF-8")}&size=300")
    }

    private fun createMediaItem(song: Song): MediaItem {
        val streamUri = Uri.parse("${api.base}/api/stream/${java.net.URLEncoder.encode(song.id, "UTF-8")}?token=${java.net.URLEncoder.encode(api.token, "UTF-8")}")
        val artworkUri = coverArtUri(song.coverArt)
        val metadataBuilder = MediaMetadata.Builder()
            .setTitle(song.title)
            .setArtist(song.artist)
            .setAlbumTitle(song.album)
            .setDisplayTitle(song.title)
            .setSubtitle(song.artist)
            .setIsPlayable(true)
            .setIsBrowsable(false)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
        if (artworkUri != null) {
            metadataBuilder.setArtworkUri(artworkUri)
        }
        return MediaItem.Builder()
            .setMediaId(song.id)
            .setUri(streamUri)
            .setMediaMetadata(metadataBuilder.build())
            .setRequestMetadata(
                MediaItem.RequestMetadata.Builder()
                    .setMediaUri(streamUri)
                    .build()
            )
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = mediaLibrarySession

    override fun onDestroy() {
        stopPositionSaving()
        webSocket?.close(1000, null)
        webSocket = null
        mediaLibrarySession?.run {
            player.release()
            release()
            mediaLibrarySession = null
        }
        super.onDestroy()
    }
}
