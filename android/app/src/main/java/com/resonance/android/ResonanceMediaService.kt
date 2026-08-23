package com.resonance.android

import android.app.PendingIntent
import android.content.Context
import android.net.Uri
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
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
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

class ResonanceMediaService : MediaLibraryService() {
    private lateinit var player: ExoPlayer
    private var mediaLibrarySession: MediaLibrarySession? = null
    private lateinit var api: Api
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

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
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    nextSong()
                }
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
                nextSong()
            }

            override fun seekToNextMediaItem() {
                nextSong()
            }
        }
        player = exoPlayer
        
        Log.d("ResonanceMedia", "Service created, base: ${api.base}, token length: ${api.token.length}")
        
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
                    .setMediaId("ROOT")
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
                if (parentId == "ROOT") {
                    Log.d("ResonanceMedia", "onGetChildren for ROOT")
                    return scope.future {
                        try {
                            val queueJson = api.json("queue")
                            Log.d("ResonanceMedia", "Fetched queue: $queueJson")
                            val array = JSONArray(queueJson)
                            val items = mutableListOf<MediaItem>()
                            for (i in 0 until array.length()) {
                                val o = array.getJSONObject(i)
                                val song = Song(o.optString("id"), o.optString("title"), o.optString("artist"), o.optString("album"), o.optInt("duration"), o.optString("coverArt"))
                                items.add(createMediaItem(song))
                            }
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
                return Futures.immediateFuture(LibraryResult.ofError(SessionError.ERROR_BAD_VALUE))
            }

            override fun onAddMediaItems(
                mediaSession: MediaSession,
                controller: MediaSession.ControllerInfo,
                mediaItems: MutableList<MediaItem>
            ): ListenableFuture<MutableList<MediaItem>> {
                Log.d("ResonanceMedia", "onAddMediaItems: ${mediaItems.size} items")
                return Futures.immediateFuture(mediaItems.map { 
                    val songId = it.mediaId
                    val streamUri = Uri.parse("${api.base}/api/stream/${java.net.URLEncoder.encode(songId, "UTF-8")}?token=${java.net.URLEncoder.encode(api.token, "UTF-8")}")
                    it.buildUpon()
                        .setUri(streamUri)
                        .setMediaMetadata(it.mediaMetadata.buildUpon()
                            .setIsPlayable(true)
                            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                            .build())
                        .build() 
                }.toMutableList())
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
        }.build()
        
        Log.d("ResonanceMedia", "Session built successfully")
    }

    fun nextSong() {
        scope.launch {
            try {
                val currentMediaItem = player.currentMediaItem
                if (currentMediaItem != null) {
                    val oldId = currentMediaItem.mediaId
                    val metadata = currentMediaItem.mediaMetadata
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
                }
                val response = JSONObject(api.json("queue/dequeue", "POST", JSONObject()))
                val nextObj = response.optJSONObject("song")
                if (nextObj != null) {
                    val song = Song(
                        nextObj.optString("id"),
                        nextObj.optString("title"),
                        nextObj.optString("artist"),
                        nextObj.optString("album"),
                        nextObj.optInt("duration"),
                        nextObj.optString("coverArt")
                    )
                    api.json(
                        "now-playing", "POST",
                        JSONObject()
                            .put("song", JSONObject().put("id", song.id).put("title", song.title).put("artist", song.artist).put("album", song.album))
                            .put("isPlaying", true)
                    )
                    val mediaItem = createMediaItem(song)
                    player.setMediaItem(mediaItem)
                    player.prepare()
                    player.play()
                } else {
                    api.json("now-playing", "POST", JSONObject().put("song", JSONObject.NULL).put("isPlaying", false))
                    player.stop()
                }
            } catch (e: Exception) {
                Log.e("ResonanceMedia", "nextSong failed", e)
            }
        }
    }

    private fun createMediaItem(song: Song): MediaItem {
        val streamUri = Uri.parse("${api.base}/api/stream/${java.net.URLEncoder.encode(song.id, "UTF-8")}?token=${java.net.URLEncoder.encode(api.token, "UTF-8")}")
        return MediaItem.Builder()
            .setMediaId(song.id)
            .setUri(streamUri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(song.title)
                    .setArtist(song.artist)
                    .setAlbumTitle(song.album)
                    .setDisplayTitle(song.title)
                    .setSubtitle(song.artist)
                    .setIsPlayable(true)
                    .setIsBrowsable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                    .build()
            )
            .setRequestMetadata(
                MediaItem.RequestMetadata.Builder()
                    .setMediaUri(streamUri)
                    .build()
            )
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = mediaLibrarySession

    override fun onDestroy() {
        mediaLibrarySession?.run {
            player.release()
            release()
            mediaLibrarySession = null
        }
        super.onDestroy()
    }
}
