package com.shortsmaker.proof

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

sealed class AppState {
    object Idle : AppState()
    data class Running(val stage: Stage, val overall: Float, val message: String, val etaSec: Long?) : AppState()
    data class Finished(val result: JobResult) : AppState()
    data class Failed(val message: String, val detail: String?) : AppState()
}

/** Keeps the job alive independent of the screen. */
@UnstableApi
object AppController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow<AppState>(AppState.Idle)
    val state: StateFlow<AppState> = _state
    private var job: Job? = null
    private var pipeline: ShortsPipeline? = null
    private var lastNotify = 0L

    fun restoreLast(context: Context) {
        if (_state.value !is AppState.Idle) return
        val dirs = File(context.filesDir, "outputs").listFiles()?.sortedByDescending { it.lastModified() } ?: return
        for (d in dirs) JobResult.load(d)?.let { _state.value = AppState.Finished(it); return }
    }

    fun start(context: Context, input: String, settings: Settings) {
        if (job?.isActive == true) return
        val app = context.applicationContext
        val p = ShortsPipeline(app)
        pipeline = p
        val started = System.currentTimeMillis()
        _state.value = AppState.Running(Stage.LINK, 0f, "Starting...", null)
        ContextCompat.startForegroundService(app, Intent(app, ProcessingService::class.java))
        job = scope.launch {
            try {
                val result = p.run(input, settings) { stage, frac, msg ->
                    val overall = (stage.startsAt + stage.weight * frac.coerceIn(0f, 1f)).coerceIn(0f, 1f)
                    val elapsed = (System.currentTimeMillis() - started) / 1000
                    val eta = if (overall > 0.04f && elapsed > 20) (elapsed * (1 - overall) / overall).toLong() else null
                    _state.value = AppState.Running(stage, overall, msg, eta)
                    notify(app, "${stage.label} - ${(overall * 100).toInt()}%")
                }
                _state.value = AppState.Finished(result)
            } catch (e: CancellationException) {
                _state.value = AppState.Idle
            } catch (e: CancelledException) {
                _state.value = AppState.Idle
            } catch (e: ProcessingException) {
                _state.value = AppState.Failed(YouTubeSource.friendly(e), null)
            } catch (e: OutOfMemoryError) {
                _state.value = AppState.Failed("The phone ran out of memory. Close other apps, or choose the smaller 'tiny' speech model in Settings, then try again.", null)
            } catch (e: Throwable) {
                val trace = e.stackTraceToString().take(2500)
                try { File(app.filesDir, "last_error.txt").writeText(trace) } catch (_: Exception) {}
                _state.value = AppState.Failed("Something unexpected went wrong (${e.javaClass.simpleName}). Tap Try again. If it keeps happening, send the details below.", trace)
            } finally {
                app.stopService(Intent(app, ProcessingService::class.java))
                pipeline = null
            }
        }
    }

    fun cancel() {
        pipeline?.cancelNative()
        job?.cancel()
    }

    fun reset() { if (job?.isActive != true) _state.value = AppState.Idle }

    private fun notify(app: Context, text: String) {
        val now = System.currentTimeMillis()
        if (now - lastNotify < 1500) return
        lastNotify = now
        try {
            (app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(ProcessingService.NOTIF_ID, ProcessingService.build(app, text))
        } catch (_: Exception) {}
    }
}

class ProcessingService : Service() {
    companion object {
        const val NOTIF_ID = 42
        private const val CHANNEL = "shorts_progress"

        fun build(ctx: Context, text: String): Notification {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "Shorts progress", NotificationManager.IMPORTANCE_LOW))
            }
            val open = android.app.PendingIntent.getActivity(
                ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                android.app.PendingIntent.FLAG_IMMUTABLE)
            return NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("Shorts Maker is working")
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(true)
                .build()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(this, NOTIF_ID, build(this, "Starting..."), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
