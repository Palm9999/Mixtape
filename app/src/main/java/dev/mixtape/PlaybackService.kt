package dev.mixtape

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import android.service.notification.NotificationListenerService
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** The app's few long-lived objects. Screens and the service reach them through [Application]. */
class MixtapeApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val dao by lazy { MixtapeDb.get(this).dao() }
    val spotify by lazy { SpotifyAuth(this) }
    val youtube by lazy { YouTubeAuth(this) }
    val sync by lazy { Sync(dao) }
    val player by lazy { Player(this, dao) }

    /** Works off the matching backlog under WorkManager, so it carries on after Mixtape is closed; safe to call often. */
    fun matchInBackground() {
        if (!spotify.signedIn) return
        val work = OneTimeWorkRequestBuilder<MatchWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        // KEEP: a run already queued or under way is left alone; it retries until nothing is unmatched.
        WorkManager.getInstance(this).enqueueUniqueWork("match", ExistingWorkPolicy.KEEP, work)
    }
}

class MatchWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as MixtapeApp
        if (!app.spotify.signedIn) return Result.success()
        // matchBacklog stops quietly on a network error or a 429; retry (with backoff) while tracks are left.
        val done = runCatching { app.sync.matchBacklog { app.spotify.token() }; app.dao.unmatchedYouTube().isEmpty() }.getOrDefault(false)
        return if (done) Result.success() else Result.retry()
    }
}

/** Exists only so Android lets Mixtape read YouTube Music's media session (notification access). */
class NotificationListener : NotificationListenerService()

/** Keeps the process, and with it the App Remote connection and the queue engine, alive with the screen off. */
class PlaybackService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val player get() = (application as MixtapeApp).player

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "Playback", NotificationManager.IMPORTANCE_LOW))
        val first = notification(player.state.value)
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID, first, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        else startForeground(ID, first)
        scope.launch {
            // Position ticks do not change the notification, so only react to what it shows.
            player.state.map { Triple(it.current, it.playing, it.active) }.distinctUntilChanged().collect { (_, _, active) ->
                if (!active) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else {
                    getSystemService(NotificationManager::class.java).notify(ID, notification(player.state.value))
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            TOGGLE -> player.toggle()
            NEXT -> player.next()
            PREVIOUS -> player.previous()
            STOP -> player.stop()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(s: PlayerState): Notification {
        fun action(icon: Int, title: String, action: String) = Notification.Action.Builder(
            Icon.createWithResource(this, icon), title,
            PendingIntent.getService(this, action.hashCode(), Intent(this, PlaybackService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE),
        ).build()
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val via = when (s.via) { SPOTIFY -> "Spotify"; YOUTUBE -> "YouTube Music"; else -> null }
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_mixtape)
            .setContentTitle(s.current?.title ?: "Mixtape")
            .setContentText(listOfNotNull(s.current?.artist, via).joinToString(" · "))
            .setContentIntent(open)
            .setOngoing(s.playing)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(action(android.R.drawable.ic_media_previous, "Previous", PREVIOUS))
            .addAction(
                if (s.playing || s.loading) action(android.R.drawable.ic_media_pause, "Pause", TOGGLE)
                else action(android.R.drawable.ic_media_play, "Play", TOGGLE)
            )
            .addAction(action(android.R.drawable.ic_media_next, "Next", NEXT))
            .addAction(action(android.R.drawable.ic_menu_close_clear_cancel, "Stop", STOP))
            .setStyle(Notification.MediaStyle().setShowActionsInCompactView(0, 1, 2))
            .build()
    }

    private companion object {
        const val CHANNEL = "playback"
        const val ID = 1
        const val TOGGLE = "dev.mixtape.TOGGLE"
        const val NEXT = "dev.mixtape.NEXT"
        const val PREVIOUS = "dev.mixtape.PREVIOUS"
        const val STOP = "dev.mixtape.STOP"
    }
}
