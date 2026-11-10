package app.musik.android.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.compose.runtime.snapshotFlow
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.session.CacheBitmapLoader
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import app.musik.android.MainActivity
import app.musik.android.MusikApp
import app.musik.android.R
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

/**
 * Keeps playback alive in the background and gives the system its controls:
 * notification shade, lock screen, Bluetooth and wired headsets. The player itself
 * lives in [MusikApp]; the session wraps it.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private val scope = MainScope()

    private val likeCommand = SessionCommand(ACTION_LIKE, Bundle.EMPTY)
    private val dislikeCommand = SessionCommand(ACTION_DISLIKE, Bundle.EMPTY)

    companion object {
        const val ACTION_LIKE = "app.musik.LIKE"
        const val ACTION_DISLIKE = "app.musik.DISLIKE"
    }

    override fun onCreate() {
        super.onCreate()
        val app = application as MusikApp
        val player = app.player

        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // Covers come from the server with auth (or from the phone when saved).
        val bitmaps = CacheBitmapLoader(
            DataSourceBitmapLoader(
                MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor()),
                player.dataSourceFactory,
            ),
        )
        val s = MediaSession.Builder(this, player.sessionPlayer)
            .setSessionActivity(open)
            .setBitmapLoader(bitmaps)
            .setCallback(object : MediaSession.Callback {
                override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
                    val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                        .add(likeCommand)
                        .add(dislikeCommand)
                        .build()
                    return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                        .setAvailableSessionCommands(commands)
                        .build()
                }

                override fun onCustomCommand(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    customCommand: SessionCommand,
                    args: Bundle,
                ): ListenableFuture<SessionResult> {
                    when (customCommand.customAction) {
                        ACTION_LIKE -> player.like()
                        ACTION_DISLIKE -> player.dislikeAndSkip()
                    }
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
            })
            .build()
        session = s

        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this).build().apply {
                setSmallIcon(R.drawable.ic_notification)
            },
        )

        // ♥ in the notification and on the lock screen follows the current track's like.
        scope.launch {
            snapshotFlow { player.current?.id to player.isFavorite }
                .distinctUntilChanged()
                .collect { (_, favorite) -> s.setCustomLayout(buttons(favorite)) }
        }
    }

    private fun buttons(favorite: Boolean): ImmutableList<CommandButton> = ImmutableList.of(
        CommandButton.Builder()
            .setDisplayName(if (favorite) "Убрать из любимых" else "Нравится")
            .setIconResId(if (favorite) R.drawable.ic_heart_filled else R.drawable.ic_heart)
            .setSessionCommand(likeCommand)
            .build(),
        CommandButton.Builder()
            .setDisplayName("Не нравится, дальше")
            .setIconResId(R.drawable.ic_thumb_down)
            .setSessionCommand(dislikeCommand)
            .build(),
    )

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        // Swiped away while paused: nothing to keep alive.
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        // The player belongs to the app and outlives the service.
        session?.release()
        session = null
        super.onDestroy()
    }
}
