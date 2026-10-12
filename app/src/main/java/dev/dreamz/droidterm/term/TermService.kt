package dev.dreamz.droidterm.term

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import dev.dreamz.droidterm.R
import dev.dreamz.droidterm.adb.AdbTools
import dev.dreamz.droidterm.adb.WirelessDebugging
import dev.dreamz.droidterm.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the process (and so every terminal tab and the adb server) alive in the
 * background, and runs the "pair this phone" flow: the pairing code is typed into a notification so the
 * Wireless debugging dialog in Settings can stay open.
 */
class TermService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wireless: WirelessDebugging? = null
    private var pairJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_SESSIONS, "Running sessions", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_PAIRING, "Wireless debugging pairing", NotificationManager.IMPORTANCE_HIGH))
        running = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_SESSIONS, sessionsNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        when (intent?.action) {
            ACTION_EXIT -> {
                Sessions.closeAll(); stopPairing(); stopSelf()
            }
            ACTION_PAIR_START -> startPairing()
            ACTION_PAIR_STOP -> stopPairing()
            ACTION_PAIR_REPLY -> {
                val code = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_CODE)?.toString()
                if (code != null) onCode(code)
            }
            else -> if (Sessions.tabs.value.isEmpty() && wireless == null) stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        stopPairing()
        scope.cancel()
        super.onDestroy()
    }

    private fun sessionsNotification(): Notification {
        val n = Sessions.tabs.value.size
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val exit = PendingIntent.getService(this, 1, Intent(this, TermService::class.java).setAction(ACTION_EXIT), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CH_SESSIONS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("DroidTerm")
            .setContentText(if (n == 1) "1 session running" else "$n sessions running")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Exit", exit).build())
            .build()
    }

    // ---- Pairing this phone with its own Wireless debugging ----

    private fun startPairing() {
        if (wireless == null) {
            wireless = WirelessDebugging(this).also { wd ->
                wd.start()
                scope.launch {
                    wd.endpoints.collect { if (pairJob == null) postPairing(pairingPrompt()) }
                }
            }
        }
        _pairing.value = "Waiting for the pairing code"
        postPairing(pairingPrompt())
    }

    private fun pairingPrompt(): String {
        val port = wireless?.localPairing()?.port
        return if (port != null) "Pairing dialog found (port $port). Type the 6-digit code here."
        else "Open Wireless debugging → Pair device with pairing code, then type the 6-digit code here."
    }

    private fun stopPairing() {
        pairJob?.cancel(); pairJob = null
        wireless?.stop(); wireless = null
        getSystemService(NotificationManager::class.java).cancel(NOTIF_PAIRING)
        _pairing.value = null
    }

    private fun onCode(input: String) {
        // Accepts "123456", "37123 123456" (port code) or "192.168.1.5:37123 123456".
        val parts = input.trim().split(Regex("\\s+"))
        val code = parts.last()
        var host = "127.0.0.1"
        var port = wireless?.localPairing()?.port
        if (parts.size >= 2) {
            val hp = parts[0]
            if (hp.contains(':')) { host = hp.substringBeforeLast(':'); port = hp.substringAfterLast(':').toIntOrNull() }
            else port = hp.toIntOrNull()
        }
        if (port == null) {
            postPairing("Pairing dialog not found yet. Keep it open, or reply with \"port code\".", reply = true)
            return
        }
        pairJob?.cancel()
        pairJob = scope.launch {
            postPairing("Pairing with $host:$port…", reply = false)
            _pairing.value = "Pairing…"
            val r = AdbTools.pair(host, port, code)
            Log.i("TermService", "pair: ${r.exitCode} ${r.output}")
            if (!r.ok || !r.output.contains("paired", true)) {
                pairJob = null
                postPairing("Pairing failed: ${r.output.lines().lastOrNull().orEmpty()}\nType a new code to retry.", reply = true)
                _pairing.value = "Pairing failed: ${r.output}"
                return@launch
            }
            postPairing("Paired. Connecting…", reply = false)
            var connect: WirelessDebugging.Endpoint? = null
            repeat(20) {
                connect = wireless?.localConnect()
                if (connect != null) return@repeat
                delay(500)
            }
            val c = connect
            val msg = if (c != null) {
                val cr = AdbTools.connect("127.0.0.1", c.port)
                if (AdbTools.looksSuccessful(cr)) "Paired and connected (127.0.0.1:${c.port}). Open DroidTerm → ADB to start a shell."
                else "Paired. Connect failed: ${cr.output}"
            } else "Paired. Wireless debugging port not found; use Connect in DroidTerm → ADB."
            _pairing.value = msg
            pairJob = null
            postPairing(msg, reply = false, done = true)
            wireless?.stop(); wireless = null
        }
    }

    private fun postPairing(text: String, reply: Boolean = true, done: Boolean = false) {
        val nm = getSystemService(NotificationManager::class.java)
        val b = Notification.Builder(this, CH_PAIRING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Pair this phone (adb)")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setOnlyAlertOnce(true)
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
        if (done) b.setAutoCancel(true) else b.setOngoing(true)
        if (reply && !done) {
            val ri = RemoteInput.Builder(KEY_CODE).setLabel("Pairing code").build()
            val pi = PendingIntent.getService(
                this, 2, Intent(this, TermService::class.java).setAction(ACTION_PAIR_REPLY),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            b.addAction(Notification.Action.Builder(null, "Enter code", pi).addRemoteInput(ri).build())
            val cancel = PendingIntent.getService(this, 3, Intent(this, TermService::class.java).setAction(ACTION_PAIR_STOP), PendingIntent.FLAG_IMMUTABLE)
            b.addAction(Notification.Action.Builder(null, "Cancel", cancel).build())
        }
        nm.notify(NOTIF_PAIRING, b.build())
    }

    companion object {
        private const val CH_SESSIONS = "sessions"
        private const val CH_PAIRING = "pairing"
        private const val NOTIF_SESSIONS = 1
        private const val NOTIF_PAIRING = 2
        private const val KEY_CODE = "code"
        const val ACTION_SYNC = "sync"
        const val ACTION_EXIT = "exit"
        const val ACTION_PAIR_START = "pair_start"
        const val ACTION_PAIR_STOP = "pair_stop"
        const val ACTION_PAIR_REPLY = "pair_reply"

        @Volatile private var running = false
        private val _pairing = MutableStateFlow<String?>(null)
        /** Status line of the notification pairing flow, null when not pairing. */
        val pairing: StateFlow<String?> = _pairing.asStateFlow()

        /** Starts/refreshes or stops the foreground service to match the open tabs. */
        fun sync(context: Context) {
            val want = Sessions.tabs.value.isNotEmpty()
            try {
                if (want || running) context.startForegroundService(Intent(context, TermService::class.java).setAction(ACTION_SYNC))
            } catch (e: Exception) {
                Log.w("TermService", "can't start service now", e)
            }
        }

        fun startPairing(context: Context) {
            context.startForegroundService(Intent(context, TermService::class.java).setAction(ACTION_PAIR_START))
        }
    }
}
