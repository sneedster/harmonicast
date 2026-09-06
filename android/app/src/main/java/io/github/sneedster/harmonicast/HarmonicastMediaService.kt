package io.github.sneedster.harmonicast

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference
import androidx.compose.runtime.mutableStateOf

class HarmonicastMediaService : MediaLibraryService() {
    private lateinit var player: ExoPlayer
    private var mediaLibrarySession: MediaLibrarySession? = null
    private lateinit var api: Api
    private lateinit var core: HarmonicastCore
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var webSocket: CoreSubscription? = null
    private var webSocketReconnectJob: kotlinx.coroutines.Job? = null
    private var webSocketGeneration = 0
    private var webSocketStopped = false
    private val currentIsAuto = AtomicReference(false)
    private val androidAutoControllers = mutableSetOf<MediaSession.ControllerInfo>()
    private var positionSaveJob: kotlinx.coroutines.Job? = null
    private var previousMediaItem: MediaItem? = null
    private data class HistoryItem(val mediaItem: MediaItem, val isAuto: Boolean)
    private val playbackHistory = PlaybackHistory<HistoryItem>()
    private var changingTrack = false
    private var guestRoomGateway: GuestRoomGateway? = null
    private var nearbyRoomHost: NearbyRoomHost? = null

    companion object {
        private const val ROOT_ID = "harmonicast:root"
        private const val PLAY_RANDOM_ID = "harmonicast:play-random"
        private const val QUEUE_ID = "harmonicast:queue"
        private const val PLAYLISTS_ID = "harmonicast:playlists"
        private const val PLAYLIST_ID_PREFIX = "harmonicast:playlist:"
        private const val PLAY_PLAYLIST_PREFIX = "harmonicast:play-playlist:"
        private const val SHUFFLE_PLAYLIST_PREFIX = "harmonicast:shuffle-playlist:"
        private const val COMMAND_PLAY_SIMILAR = "io.github.sneedster.harmonicast.PLAY_SIMILAR"
        private const val COMMAND_CLEAR_QUEUE = "io.github.sneedster.harmonicast.CLEAR_QUEUE"
        const val CLAIM_PLAYBACK_ACTION = "io.github.sneedster.harmonicast.CLAIM_PLAYBACK"
        const val RELOAD_PROFILE_ACTION = "io.github.sneedster.harmonicast.RELOAD_PROFILE"
        const val ENABLE_GUEST_CONTROL_ACTION = "io.github.sneedster.harmonicast.ENABLE_GUEST_CONTROL"
        const val DISABLE_GUEST_CONTROL_ACTION = "io.github.sneedster.harmonicast.DISABLE_GUEST_CONTROL"
        val roomShareState = mutableStateOf(RoomShareState())
        private val PLAY_SIMILAR_COMMAND = SessionCommand(COMMAND_PLAY_SIMILAR, Bundle())
        private val CLEAR_QUEUE_COMMAND = SessionCommand(COMMAND_CLEAR_QUEUE, Bundle())
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
        api = Api(getSharedPreferences("harmonicast", Context.MODE_PRIVATE))
        core = harmonicastCore(api)

        exoPlayer.addListener(object : Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                Log.e(
                    "HarmonicastMedia",
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
                if (mediaItem != null) playbackHistory.record(HistoryItem(mediaItem, currentIsAuto.get()))
                previousMediaItem = mediaItem
                syncCurrentPlaybackState(player.isPlaying)
            }
        })

        // Handle transport outside the single-item Media3 timeline.
        val forwardingPlayer = object : ForwardingPlayer(exoPlayer) {
            override fun getAvailableCommands(): Player.Commands {
                return super.getAvailableCommands().buildUpon()
                    .add(COMMAND_SEEK_TO_NEXT)
                    .add(COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                    .add(COMMAND_SEEK_TO_PREVIOUS)
                    .add(COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                    .build()
            }

            override fun isCommandAvailable(command: Int): Boolean {
                return command == COMMAND_SEEK_TO_NEXT ||
                       command == COMMAND_SEEK_TO_NEXT_MEDIA_ITEM ||
                       command == COMMAND_SEEK_TO_PREVIOUS ||
                       command == COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM ||
                       super.isCommandAvailable(command)
            }

            override fun hasNextMediaItem(): Boolean {
                return true
            }

            override fun seekToPrevious() {
                goBack()
            }

            override fun seekToPreviousMediaItem() {
                goBack(forcePrevious = true)
            }

            override fun seekToNext() {
                advance("skip")
            }

            override fun seekToNextMediaItem() {
                advance("skip")
            }
        }
        player = exoPlayer

        Log.d("HarmonicastMedia", "Service created, base: ${api.base}, token length: ${api.token.length}")

        connectWebSocket()

        val callback = object : MediaLibrarySession.Callback {
            override fun onConnect(
                session: MediaSession,
                controller: MediaSession.ControllerInfo
            ): ConnectionResult {
                Log.d("HarmonicastMedia", "onConnect from: ${controller.packageName}")

                // The car is the authoritative playback endpoint while it is
                // connected. Claim immediately instead of leaving a phone or
                // browser session in control and waiting for a manual action.
                if (isAndroidAutoController(controller)) {
                    androidAutoControllers.add(controller)
                    claimAndroidAutoPlayback()
                }

                // Android Auto is a MediaBrowser controller. The default
                // connection result does not grant the library commands it
                // uses to load the root, browse the request queue, or return
                // search results. Keep this explicit: a normal phone
                // MediaController happens to work with the defaults, which
                // otherwise makes an Auto regression easy to miss.
                val availableSessionCommands = ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                    .add(SessionCommand.COMMAND_CODE_LIBRARY_SEARCH)
                    .add(SessionCommand.COMMAND_CODE_LIBRARY_GET_CHILDREN)
                    .add(SessionCommand.COMMAND_CODE_LIBRARY_GET_ITEM)
                    .add(SessionCommand.COMMAND_CODE_LIBRARY_GET_LIBRARY_ROOT)
                    .add(PLAY_SIMILAR_COMMAND)
                    .add(CLEAR_QUEUE_COMMAND)
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

            override fun onDisconnected(
                session: MediaSession,
                controller: MediaSession.ControllerInfo,
            ) {
                if (isAndroidAutoController(controller)) {
                    androidAutoControllers.remove(controller)
                }
                super.onDisconnected(session, controller)
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
                            .setTitle("Harmonicast")
                            .setDisplayTitle("Harmonicast")
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
                                .setSubtitle("Start the shared Harmonicast queue").setIsPlayable(true).setIsBrowsable(false)
                                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC).build()
                        ).build(),
                        MediaItem.Builder().setMediaId(QUEUE_ID).setMediaMetadata(
                            MediaMetadata.Builder().setTitle("Request queue").setDisplayTitle("Request queue")
                                .setSubtitle("Tracks added by listeners").setIsPlayable(false).setIsBrowsable(true)
                                .setMediaType(MediaMetadata.MEDIA_TYPE_PLAYLIST).build()
                        ).build(),
                        MediaItem.Builder().setMediaId(PLAYLISTS_ID).setMediaMetadata(
                            MediaMetadata.Builder().setTitle("Plex playlists").setDisplayTitle("Plex playlists")
                                .setSubtitle("Browse your personal playlists").setIsPlayable(false).setIsBrowsable(true)
                                .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS).build()
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
                            Log.e("HarmonicastMedia", "Get children failed", e)
                            LibraryResult.ofError(SessionError.ERROR_SESSION_AUTHENTICATION_EXPIRED)
                        }
                    }
                }
                if (parentId == PLAYLISTS_ID) {
                    return scope.future {
                        try {
                            val items = core.library.playlists().map { playlist ->
                                MediaItem.Builder().setMediaId(PLAYLIST_ID_PREFIX + encode(playlist.id)).setMediaMetadata(
                                    MediaMetadata.Builder().setTitle(playlist.title).setDisplayTitle(playlist.title)
                                        .setSubtitle(if (playlist.trackCount > 0) "${playlist.trackCount} tracks" else "Playlist")
                                        .setIsPlayable(false).setIsBrowsable(true)
                                        .setMediaType(MediaMetadata.MEDIA_TYPE_PLAYLIST).build()
                                ).build()
                            }
                            LibraryResult.ofItemList(ImmutableList.copyOf(items), params)
                        } catch (e: Exception) {
                            Log.e("HarmonicastMedia", "Could not browse Plex playlists", e)
                            LibraryResult.ofError(SessionError.ERROR_UNKNOWN)
                        }
                    }
                }
                if (parentId.startsWith(PLAYLIST_ID_PREFIX)) {
                    return scope.future {
                        try {
                            val playlistId = java.net.URLDecoder.decode(parentId.removePrefix(PLAYLIST_ID_PREFIX), "UTF-8")
                            val tracks = core.library.playlistTracks(playlistId)
                            val actions = listOf(
                                playlistActionItem(PLAY_PLAYLIST_PREFIX + encode(playlistId), "Play playlist"),
                                playlistActionItem(SHUFFLE_PLAYLIST_PREFIX + encode(playlistId), "Shuffle playlist"),
                            )
                            LibraryResult.ofItemList(ImmutableList.copyOf(actions + tracks.map(::createMediaItem)), params)
                        } catch (e: Exception) {
                            Log.e("HarmonicastMedia", "Could not browse Plex playlist tracks", e)
                            LibraryResult.ofError(SessionError.ERROR_UNKNOWN)
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
                                    .setTitle("Harmonicast")
                                    .setMediaType(MediaMetadata.MEDIA_TYPE_PLAYLIST)
                                    .build()
                            )
                            .build(),
                        null
                    ))
                }
                return scope.future {
                    try {
                        val current = core.playback.snapshot().nowPlaying.song
                        if (current != null && current.id == mediaId) {
                            val song = current
                            LibraryResult.ofItem(createMediaItem(song), null)
                        } else {
                            val song = Song(mediaId, mediaId, "", "", 0, "")
                            LibraryResult.ofItem(createMediaItem(song), null)
                        }
                    } catch (e: Exception) {
                        Log.e("HarmonicastMedia", "onGetItem failed", e)
                        LibraryResult.ofError(SessionError.ERROR_UNKNOWN)
                    }
                }
            }

            override fun onAddMediaItems(
                mediaSession: MediaSession,
                controller: MediaSession.ControllerInfo,
                mediaItems: MutableList<MediaItem>
            ): ListenableFuture<MutableList<MediaItem>> {
                Log.d("HarmonicastMedia", "onAddMediaItems: ${mediaItems.size} items")
                if (mediaItems.any { it.mediaId.startsWith(PLAY_PLAYLIST_PREFIX) || it.mediaId.startsWith(SHUFFLE_PLAYLIST_PREFIX) }) {
                    return Futures.immediateFuture(mediaItems)
                }
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
                val actionId = mediaItems.singleOrNull()?.mediaId.orEmpty()
                if (actionId.startsWith(PLAY_PLAYLIST_PREFIX) || actionId.startsWith(SHUFFLE_PLAYLIST_PREFIX)) {
                    return scope.future {
                        val prefix = if (actionId.startsWith(SHUFFLE_PLAYLIST_PREFIX)) SHUFFLE_PLAYLIST_PREFIX else PLAY_PLAYLIST_PREFIX
                        val playlistId = java.net.URLDecoder.decode(actionId.removePrefix(prefix), "UTF-8")
                        var tracks = core.library.playlistTracks(playlistId)
                        if (prefix == SHUFFLE_PLAYLIST_PREFIX) tracks = tracks.shuffled()
                        core.queue.clear()
                        core.queue.addAll(tracks)
                        val selection = core.queue.dequeue()
                        currentIsAuto.set(false)
                        val item = selection.song?.let(::createMediaItem)
                        MediaSession.MediaItemsWithStartPosition(item?.let(::listOf) ?: emptyList(), 0, 0)
                    }
                }
                return super.onSetMediaItems(mediaSession, controller, mediaItems, startIndex, startPositionMs)
            }

            override fun onPlaybackResumption(
                mediaSession: MediaSession,
                controller: MediaSession.ControllerInfo,
            ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = scope.future {
                val state = core.playback.snapshot()
                val saved = state.nowPlaying.song?.let { song ->
                    val playable = if (runCatching { core.library.streamUrl(song) }.isSuccess) song
                        else core.library.track(song.id)
                    playable?.let(::createMediaItem)
                }
                if (saved != null) {
                    currentIsAuto.set(state.isAutoQueue)
                    MediaSession.MediaItemsWithStartPosition(
                        listOf(saved),
                        0,
                        (state.positionSeconds * 1_000).toLong().coerceAtLeast(0),
                    )
                } else {
                    val item = dequeueRandomItem()
                    MediaSession.MediaItemsWithStartPosition(item?.let(::listOf) ?: emptyList(), 0, 0)
                }
            }

            override fun onCustomCommand(
                session: MediaSession,
                controller: MediaSession.ControllerInfo,
                customCommand: SessionCommand,
                args: Bundle,
            ): ListenableFuture<SessionResult> {
                return scope.future {
                    try {
                        when (customCommand.customAction) {
                            COMMAND_PLAY_SIMILAR -> {
                                Log.d("HarmonicastMedia", "Android Auto requested Track Radio queue")
                                val added = core.queue.radio()
                                updateCustomLayout(added > 0)
                                refreshAndroidAutoQueue(refreshBrowser = true)
                                Log.d("HarmonicastMedia", "Queued $added Track Radio songs from Android Auto")
                                SessionResult(SessionResult.RESULT_SUCCESS, Bundle().apply { putInt("added", added) })
                            }
                            COMMAND_CLEAR_QUEUE -> {
                                Log.d("HarmonicastMedia", "Android Auto requested queue clear")
                                core.queue.clear()
                                updateCustomLayout(false)
                                refreshAndroidAutoQueue(refreshBrowser = true)
                                SessionResult(SessionResult.RESULT_SUCCESS)
                            }
                            CLAIM_PLAYBACK_ACTION -> {
                                Log.d("HarmonicastMedia", "Phone requested playback takeover")
                                core.playback.claim()
                                resumeSharedPlayback()
                                SessionResult(SessionResult.RESULT_SUCCESS)
                            }
                            else -> SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED)
                        }
                    } catch (e: Exception) {
                        Log.e("HarmonicastMedia", "Android Auto custom command failed", e)
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
                Log.d("HarmonicastMedia", "onSearch for: $query")
                scope.launch {
                    try {
                        val results = core.library.search(query)
                        Log.d("HarmonicastMedia", "Search results found: ${results.size}")
                        session.notifySearchResultChanged(browser, query, results.size, params)
                    } catch (e: Exception) {
                        Log.e("HarmonicastMedia", "Search failed", e)
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
                Log.d("HarmonicastMedia", "onGetSearchResult for: $query")
                return scope.future {
                    try {
                        val items = core.library.search(query).map(::createMediaItem)
                        Log.d("HarmonicastMedia", "Returning ${items.size} search items")
                        LibraryResult.ofItemList(ImmutableList.copyOf(items), params)
                    } catch (e: Exception) {
                        Log.e("HarmonicastMedia", "Get search result failed", e)
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
            setCustomLayout(customLayout(false))
        }.build()

        Log.d("HarmonicastMedia", "Session built successfully")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            RELOAD_PROFILE_ACTION -> reloadProfile()
            ENABLE_GUEST_CONTROL_ACTION -> enableGuestControl()
            DISABLE_GUEST_CONTROL_ACTION -> disableGuestControl()
        }
        return super.onStartCommand(intent, flags, startId)
    }

    private fun enableGuestControl() {
        if (api.profile.mode != HomeMode.PERSONAL_PLEX) {
            roomShareState.value = RoomShareState()
            return
        }
        if (roomShareState.value.enabled) return
        try {
            val gateway = GuestRoomGateway(this, core)
            guestRoomGateway = gateway
            val room = gateway.start()
            val nearby = NearbyRoomHost(this, core, room.roomCode)
            nearbyRoomHost = nearby
            roomShareState.value = room.copy(nearbyAvailable = nearby.start())
            Log.d("HarmonicastMedia", "Guest room enabled: ${roomShareState.value.roomCode}")
        } catch (e: Exception) {
            guestRoomGateway = null
            roomShareState.value = RoomShareState(error = "Could not start the same-Wi-Fi room controller")
            Log.e("HarmonicastMedia", "Could not enable guest room", e)
        }
    }

    private fun disableGuestControl() {
        guestRoomGateway?.stop()
        guestRoomGateway = null
        nearbyRoomHost?.stop()
        nearbyRoomHost = null
        roomShareState.value = RoomShareState()
        Log.d("HarmonicastMedia", "Guest room disabled")
    }

    private fun reloadProfile() {
        stopPositionSaving()
        webSocketGeneration += 1
        webSocketReconnectJob?.cancel()
        webSocketReconnectJob = null
        webSocket?.close()
        webSocket = null
        api = Api(getSharedPreferences("harmonicast", Context.MODE_PRIVATE))
        core = harmonicastCore(api)
        player.stop()
        player.clearMediaItems()
        previousMediaItem = null
        connectWebSocket()
        refreshAndroidAutoQueue(refreshBrowser = true)
        Log.d("HarmonicastMedia", "Playback profile reloaded: ${api.profile.mode}")
    }

    private fun connectWebSocket() {
        if (webSocketStopped || !api.profile.ready) return
        webSocketReconnectJob?.cancel()
        val generation = ++webSocketGeneration
        webSocket = core.observe(onEvent = { event ->
            try {
                Log.d("HarmonicastMedia", "Core event: $event")
                if (event == CoreEvent.FORCE_SKIP) {
                    advance("skip")
                } else if (event == CoreEvent.QUEUE_CHANGED) {
                    refreshAndroidAutoQueue()
                } else if (event == CoreEvent.PLAYER_SESSION_CHANGED) {
                    scope.launch {
                        try {
                            val isActive = core.playback.isActivePlayer()
                            if (androidAutoControllers.isNotEmpty() && !isActive) {
                                // Another client just claimed playback. A
                                // connected Android Auto host always wins it
                                // back, then recreates its local timeline.
                                claimAndroidAutoPlayback()
                            } else if (isActive) {
                                resumeSharedPlayback()
                            } else {
                                Log.d("HarmonicastMedia", "Another device took over playback — pausing")
                                player.pause()
                                stopPositionSaving()
                            }
                        } catch (e: Exception) {
                            Log.e("HarmonicastMedia", "Failed to check player status after session change", e)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("HarmonicastMedia", "Failed to parse WS message", e)
            }
        }, onDisconnected = {
            scope.launch {
                if (webSocketStopped || generation != webSocketGeneration) return@launch
                Log.w("HarmonicastMedia", "Command WebSocket disconnected; reconnecting")
                webSocket = null
                webSocketReconnectJob?.cancel()
                webSocketReconnectJob = scope.launch {
                    delay(1_000)
                    if (!webSocketStopped && generation == webSocketGeneration) {
                        connectWebSocket()
                    }
                }
            }
        })
    }

    private fun isAndroidAutoController(controller: MediaSession.ControllerInfo) =
        controller.packageName == "com.google.android.projection.gearhead"

    private fun claimAndroidAutoPlayback() {
        scope.launch {
            try {
                Log.d("HarmonicastMedia", "Android Auto connected — claiming playback")
                core.playback.claim()
                resumeSharedPlayback()
            } catch (e: Exception) {
                Log.e("HarmonicastMedia", "Android Auto could not claim playback", e)
            }
        }
    }

    /** Refresh Android Auto's stable browsable Request queue state. */
    private fun refreshAndroidAutoQueue(refreshBrowser: Boolean = false) {
        scope.launch {
            try {
                val songs = fetchQueueSongs()
                updateCustomLayout(songs.any { it.isRadio })
                // Do not invalidate the browsed Request queue here. DHU
                // reloads the current browse screen on every invalidation,
                // which prevents scrolling while a live queue is changing.
                // Its player “Up next” surface is independent and is kept
                // current by the timeline update below. Explicit queue actions
                // originating in Auto request one refresh so its cached
                // browser list promptly reflects a deliberate add or clear.
                if (refreshBrowser) {
                    mediaLibrarySession?.notifyChildrenChanged(QUEUE_ID, songs.size, null)
                }
            } catch (e: Exception) {
                Log.e("HarmonicastMedia", "Failed to refresh Android Auto queue", e)
            }
        }
    }

    private suspend fun fetchQueueSongs(): List<Song> = core.queue.songs()

    private suspend fun fetchPlexSong(id: String): Song? = try {
        core.library.track(id)
    } catch (e: Exception) {
        Log.w("HarmonicastMedia", "Could not resolve Plex metadata for $id", e)
        null
    }

    /**
     * A player claim switches the server's active session, but it does not
     * transfer the old device's ExoPlayer timeline. Recreate that timeline on
     * the newly active device from the shared now-playing state so Media3 has
     * an actual stream to control.
     */
    private suspend fun resumeSharedPlayback() {
        val state = core.playback.snapshot()
        val song = state.nowPlaying.song ?: return
        if (song.id.isBlank()) return

        currentIsAuto.set(state.isAutoQueue)
        val positionMs = (state.positionSeconds * 1_000).toLong().coerceAtLeast(0L)
        if (player.currentMediaItem?.mediaId != song.id) {
            player.setMediaItem(createMediaItem(song), positionMs)
            player.prepare()
        } else {
            player.seekTo(positionMs)
        }

        if (state.nowPlaying.isPlaying) {
            Log.d("HarmonicastMedia", "Resuming shared playback on this device")
            player.play()
        } else {
            player.pause()
        }
    }

    private fun customLayout(radioQueueActive: Boolean) = listOf(
        CommandButton.Builder(if (radioQueueActive) CommandButton.ICON_CHECK_CIRCLE_FILLED else CommandButton.ICON_RADIO)
            .setSessionCommand(PLAY_SIMILAR_COMMAND)
            .setDisplayName(if (radioQueueActive) "Radio queue ready" else "Queue Track Radio")
            .setSlots(CommandButton.SLOT_OVERFLOW)
            .build(),
        CommandButton.Builder(CommandButton.ICON_QUEUE_REMOVE)
            .setSessionCommand(CLEAR_QUEUE_COMMAND)
            .setDisplayName("Clear upcoming queue")
            .setSlots(CommandButton.SLOT_OVERFLOW)
            .build(),
    )

    private fun playlistActionItem(id: String, title: String) = MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata.Builder().setTitle(title).setDisplayTitle(title)
                .setIsPlayable(true).setIsBrowsable(false).setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC).build()
        ).build()

    private fun updateCustomLayout(radioQueueActive: Boolean) {
        mediaLibrarySession?.setCustomLayout(customLayout(radioQueueActive))
    }

    /**
     * The first item in the player timeline is already out of the server
     * queue. When ExoPlayer advances to its next item automatically, remove
     * that matching head from Harmonicast, record the completed play, and let
     * the resulting queue broadcast refresh the timeline again.
     */
    private fun consumeAutomaticQueueTransition(previous: MediaItem, current: MediaItem) {
        scope.launch {
            try {
                recordPlaybackEvent(previous, "complete", 1.0)
                core.playback.scrobble(previous.mediaId, submission = true)

                val response = core.queue.dequeue()
                val dequeued = response.song
                if (dequeued?.id != current.mediaId) {
                    Log.w("HarmonicastMedia", "Automatic transition did not match the shared queue head")
                }
                currentIsAuto.set(!response.isManual)
                syncCurrentPlaybackState(player.isPlaying)
            } catch (e: Exception) {
                Log.e("HarmonicastMedia", "Failed to consume Android Auto queue transition", e)
            }
        }
    }

    private fun syncCurrentPlaybackState(isPlaying: Boolean) {
        val item = player.currentMediaItem ?: return
        val songId = item.mediaId
        val title = item.mediaMetadata.title?.toString() ?: ""
        val artist = item.mediaMetadata.artist?.toString() ?: ""
        val album = item.mediaMetadata.albumTitle?.toString() ?: ""
        val coverArt = item.mediaMetadata.extras?.getString("harmonicast.coverArt").orEmpty()
        val isAuto = currentIsAuto.get()
        scope.launch {
            try {
                core.playback.publish(Song(songId, title, artist, album, coverArt = coverArt), isPlaying, isAuto)
            } catch (e: Exception) {
                Log.e("HarmonicastMedia", "Failed to sync play state", e)
            }
        }
    }

    private fun startPositionSaving() {
        stopPositionSaving()
        positionSaveJob = scope.launch {
            while (true) {
                kotlinx.coroutines.delay(2000)
                // ExoPlayer reports milliseconds; the Harmonicast API stores
                // seconds so browser and Android hosts can resume consistently.
                val positionSeconds = player.currentPosition / 1000.0
                if (positionSeconds >= 0) {
                    try {
                        core.playback.savePosition(positionSeconds)
                    } catch (e: Exception) {
                        Log.e("HarmonicastMedia", "Failed to save position", e)
                    }
                }
            }
        }
    }

    private fun stopPositionSaving() {
        positionSaveJob?.cancel()
        positionSaveJob = null
    }

    private fun playHistoryItem(item: HistoryItem, playWhenReady: Boolean) {
        currentIsAuto.set(item.isAuto)
        player.setMediaItem(item.mediaItem, 0)
        player.prepare()
        player.playWhenReady = playWhenReady
        syncCurrentPlaybackState(playWhenReady)
        scope.launch {
            try {
                core.playback.scrobble(item.mediaItem.mediaId, submission = false)
            } catch (e: Exception) {
                Log.e("HarmonicastMedia", "History scrobble failed", e)
            }
        }
    }

    private fun goBack(forcePrevious: Boolean = false) {
        scope.launch {
            if (changingTrack || player.currentMediaItem == null) return@launch
            val previous = playbackHistory.previous(player.currentPosition, forcePrevious)
            if (previous == null) {
                player.seekTo(0)
                if (player.playbackState == Player.STATE_ENDED) player.prepare()
            } else {
                playHistoryItem(previous, player.playWhenReady)
            }
            // Publish the reset immediately, including when playback is paused.
            try {
                core.playback.savePosition(0.0)
            } catch (e: Exception) {
                Log.e("HarmonicastMedia", "Failed to save previous-track position", e)
            }
        }
    }

    private fun advance(reason: String) {
        scope.launch {
            if (changingTrack) return@launch
            changingTrack = true
            try {
                val currentMediaItem = player.currentMediaItem
                if (currentMediaItem != null) {
                    val oldId = currentMediaItem.mediaId
                    val progress = if (player.duration > 0) {
                        (player.currentPosition.toFloat() / player.duration).coerceIn(0f, 1f)
                    } else 0f

                    if (reason == "ended") {
                        recordPlaybackEvent(currentMediaItem, "complete", 1.0)
                        core.playback.scrobble(oldId, submission = true)
                    } else {
                        recordPlaybackEvent(currentMediaItem, "skip", progress.toDouble())
                    }
                }

                val replay = playbackHistory.next()
                if (replay != null) {
                    playHistoryItem(replay, playWhenReady = true)
                    return@launch
                }

                val response = core.queue.dequeue()
                val song = response.song
                if (song != null) {
                    val isAuto = !response.isManual
                    currentIsAuto.set(isAuto)
                    core.playback.publish(song, isPlaying = true, isAutoQueue = isAuto)
                    core.playback.scrobble(song.id, submission = false)
                    // Keep the Media3 timeline to one current item. Its
                    // legacy Android Auto Queue screen otherwise jumps to the
                    // active item whenever Media3 republishes playback state.
                    // The shared Request queue remains the authoritative list
                    // of upcoming songs, and advance() loads its next song.
                    player.setMediaItem(createMediaItem(song), 0)
                    player.prepare()
                    player.play()
                } else {
                    currentIsAuto.set(false)
                    core.playback.publish(null, isPlaying = false)
                    player.stop()
                }
            } catch (e: Exception) {
                Log.e("HarmonicastMedia", "advance failed", e)
            } finally {
                changingTrack = false
            }
        }
    }

    private suspend fun recordPlaybackEvent(item: MediaItem, event: String, progress: Double) {
        val metadata = item.mediaMetadata
        try {
            core.playback.recordEvent(Song(item.mediaId, metadata.title?.toString().orEmpty(),
                metadata.artist?.toString().orEmpty(), metadata.albumTitle?.toString().orEmpty()), event, progress)
        } catch (e: Exception) {
            // Rating updates are important, but playback must still advance if
            // Plex is temporarily unavailable.
            Log.e("HarmonicastMedia", "Failed to record $event rating event", e)
        }
    }

    /** Enables the shared auto queue and returns its next playable track. */
    private suspend fun dequeueRandomItem(): MediaItem? = try {
        core.queue.enableAutomaticPlayback()
        val response = core.queue.dequeue()
        val next = response.song ?: return null
        currentIsAuto.set(!response.isManual)
        createMediaItem(next)
    } catch (e: Exception) {
        Log.e("HarmonicastMedia", "Failed to start random playback", e)
        null
    }

    private fun coverArtUri(coverArt: String): Uri? {
        if (coverArt.isEmpty()) return null
        return null
    }

    private fun createMediaItem(song: Song): MediaItem {
        val streamUri = Uri.parse(core.library.streamUrl(song))
        val artworkUri = core.library.artworkUrl(song)?.let(Uri::parse) ?: coverArtUri(song.coverArt)
        val metadataBuilder = MediaMetadata.Builder()
            .setExtras(Bundle().apply { putString("harmonicast.coverArt", song.coverArt) })
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
        disableGuestControl()
        webSocketStopped = true
        webSocketGeneration += 1
        webSocketReconnectJob?.cancel()
        webSocketReconnectJob = null
        webSocket?.close()
        webSocket = null
        mediaLibrarySession?.run {
            player.release()
            release()
            mediaLibrarySession = null
        }
        super.onDestroy()
    }
}
