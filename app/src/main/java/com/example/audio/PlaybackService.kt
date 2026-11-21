package com.example.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.example.EcouteApplication
import com.example.MainActivity
import com.example.R
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

@UnstableApi
class PlaybackService : MediaSessionService() {

    companion object {
        private const val CHANNEL_ID = "ecoute_playback"
        private const val NOTIFICATION_ID = 4201
    }

    private var mediaSession: MediaSession? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var sawActivePlayback = false
    private var launchPendingIntent: PendingIntent? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val app = applicationContext as EcouteApplication
        val controller = app.playbackController
        val player = BridgeMediaPlayer(controller)

        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        launchPendingIntent = pendingIntent

        val session = MediaSession.Builder(this, player)
            .setSessionActivity(pendingIntent)
            .build()
        mediaSession = session
        addSession(session)

        serviceScope.launch {
            controller.state.collect { st ->
                player.notifyStateChanged()
                when (st.status) {
                    PlaybackStatus.BUFFERING,
                    PlaybackStatus.PLAYING,
                    PlaybackStatus.PAUSED -> {
                        sawActivePlayback = true
                        updateOrStartForegroundNotification(st)
                    }
                    PlaybackStatus.IDLE,
                    PlaybackStatus.ERROR -> {
                        if (sawActivePlayback) {
                            sawActivePlayback = false
                            runCatching {
                                ServiceCompat.stopForeground(this@PlaybackService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                            }
                            stopSelf()
                        }
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = applicationContext as EcouteApplication
        promoteToForeground(app.playbackController.state.value)
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
        serviceScope.cancel()
        runCatching {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        }
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "تشغيل الصوت والقراءة",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "إشعار التحكم في تشغيل الصوت المحلي"
                    setShowBadge(false)
                }
                manager.createNotificationChannel(channel)
            }
        }
    }

    private fun promoteToForeground(st: PlaybackUiState) {
        val notification = buildPlaybackNotification(st)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        } else {
            0
        }
        runCatching {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
        }
    }

    private fun updateOrStartForegroundNotification(st: PlaybackUiState) {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager != null) {
            runCatching {
                manager.notify(NOTIFICATION_ID, buildPlaybackNotification(st))
            }.onFailure {
                promoteToForeground(st)
            }
        } else {
            promoteToForeground(st)
        }
    }

    private fun buildPlaybackNotification(st: PlaybackUiState): Notification {
        val title = st.bookTitle.ifBlank { "Écoute" }
        val statusLabel = when (st.status) {
            PlaybackStatus.BUFFERING -> "جاري تحضير الصوت محلياً..."
            PlaybackStatus.PLAYING -> st.currentSentenceText.ifBlank { "قيد الاستماع" }
            PlaybackStatus.PAUSED -> "متوقف مؤقتاً • ${st.currentSentenceText}".trim()
            PlaybackStatus.ERROR -> st.errorMessage ?: "تعذر التشغيل"
            PlaybackStatus.IDLE -> "Écoute جاهز للاستماع"
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(statusLabel)
            .setContentIntent(launchPendingIntent)
            .setOngoing(st.status == PlaybackStatus.PLAYING || st.status == PlaybackStatus.BUFFERING)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
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
