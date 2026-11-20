package com.example.audio

import android.app.PendingIntent
import android.content.Intent
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.example.EcouteApplication
import com.example.MainActivity
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

@UnstableApi
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        val app = applicationContext as EcouteApplication
        val controller = app.playbackController
        val player = BridgeMediaPlayer(controller)

        val launchIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(pendingIntent)
            .build()

        serviceScope.launch {
            controller.state.collect {
                player.notifyStateChanged()
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
        serviceScope.cancel()
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }

    private class BridgeMediaPlayer(
        private val controller: AudioPlaybackController
    ) : SimpleBasePlayer(Looper.getMainLooper()) {

        fun notifyStateChanged() {
            invalidateState()
        }

        override fun getState(): State {
            val st = controller.state.value
            val isPlaying = st.status == PlaybackStatus.PLAYING
            val playbackState = when (st.status) {
                PlaybackStatus.IDLE, PlaybackStatus.ERROR -> STATE_IDLE
                PlaybackStatus.BUFFERING -> STATE_BUFFERING
                PlaybackStatus.PLAYING, PlaybackStatus.PAUSED -> STATE_READY
            }

            val commands = Player.Commands.Builder()
                .addAll(
                    COMMAND_PLAY_PAUSE,
                    COMMAND_STOP,
                    COMMAND_SEEK_TO_NEXT,
                    COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                    COMMAND_SEEK_TO_PREVIOUS,
                    COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                    COMMAND_GET_CURRENT_MEDIA_ITEM,
                    COMMAND_GET_METADATA
                )
                .build()

            val metadata = MediaMetadata.Builder()
                .setTitle(st.currentSentenceText.ifBlank { "Écoute French Studio" })
                .setArtist(st.bookTitle.ifBlank { st.activeModelName })
                .setAlbumTitle(st.chapterTitle)
                .build()

            val mediaItem = MediaItemData.Builder("ecoute_current")
                .setMediaItem(
                    MediaItem.Builder()
                        .setMediaId("ecoute_current")
                        .setMediaMetadata(metadata)
                        .build()
                )
                .setMediaMetadata(metadata)
                .build()

            return State.Builder()
                .setAvailableCommands(commands)
                .setPlayWhenReady(isPlaying || st.status == PlaybackStatus.BUFFERING, PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
                .setPlaybackState(playbackState)
                .setPlaylist(listOf(mediaItem))
                .setCurrentMediaItemIndex(0)
                .build()
        }

        override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
            if (playWhenReady) controller.resume() else controller.pause()
            return Futures.immediateVoidFuture()
        }

        override fun handleStop(): ListenableFuture<*> {
            controller.stop()
            return Futures.immediateVoidFuture()
        }

        override fun handleSeek(
            mediaItemIndex: Int,
            positionMs: Long,
            seekCommand: Int
        ): ListenableFuture<*> {
            when (seekCommand) {
                COMMAND_SEEK_TO_NEXT, COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> controller.nextSentence()
                COMMAND_SEEK_TO_PREVIOUS, COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> controller.previousSentence()
            }
            return Futures.immediateVoidFuture()
        }
    }
}
