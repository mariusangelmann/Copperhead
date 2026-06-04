package com.copperhead.gateway.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import com.copperhead.gateway.MainActivity
import com.copperhead.gateway.util.BatteryOptimizer
import com.copperhead.gateway.R
import com.copperhead.gateway.bridge.CallBridge
import com.copperhead.gateway.bridge.SmsBridge
import com.copperhead.gateway.gsm.GsmCallManager
import com.copperhead.gateway.guardian.GuardianService
import com.copperhead.gateway.guardian.IGuardian
import com.copperhead.gateway.sip.SipConfig
import com.copperhead.gateway.sip.SipEngine
import com.copperhead.gateway.sms.SmsHandler
import com.copperhead.gateway.util.Preferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Foreground service that keeps the gateway running.
 * Manages the lifecycle of SIP engine, GSM call manager, SMS handler, and bridges.
 */
class GatewayService : Service() {
    companion object {
        private const val TAG = "GatewayService"
        private const val CHANNEL_ID = "copperhead_gateway"
        private const val NOTIFICATION_ID = 1
        private const val LOG_BUFFER_MAX = 1000
        const val ACTION_START = "com.copperhead.gateway.START"
        const val ACTION_STOP = "com.copperhead.gateway.STOP"
        const val ACTION_RELOAD_CONFIG = "com.copperhead.gateway.RELOAD_CONFIG"

        var instance: GatewayService? = null
            private set
    }

    private val logTimestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
    private val logBuffer = ArrayDeque<String>()
    private val logLock = Any()

    lateinit var sipEngine: SipEngine
        private set
    lateinit var gsmCallManager: GsmCallManager
        private set
    lateinit var smsHandler: SmsHandler
        private set
    lateinit var callBridge: CallBridge
        private set
    lateinit var smsBridge: SmsBridge
        private set

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private val logListeners = CopyOnWriteArrayList<(String) -> Unit>()
    private var previousUncaughtHandler: Thread.UncaughtExceptionHandler? = null

    // ── Guardian sidecar (separate :guardian process) ─────────────────
    // Holds a strong reference to an "anchor" Binder owned by THIS process.
    // The guardian linkToDeath's on the remote stub of this binder; when
    // our process dies the kernel notifies the guardian, which ends the
    // cellular call. The reference must remain reachable for the entire
    // gateway lifetime — if GC collects it, the guardian sees a dead
    // binder immediately and falsely concludes we crashed.
    private val deathAnchor = Binder()
    private var guardian: IGuardian? = null
    private var guardianBound = false
    @Volatile private var lastActiveCount = 0
    private val guardianConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder) {
            val g = IGuardian.Stub.asInterface(service)
            guardian = g
            try {
                g.startWatching(deathAnchor)
                // Replay current state — a bridge may already exist by the
                // time the (async) bind completes, otherwise the guardian
                // would falsely believe no call is active.
                g.setHasActiveCall(lastActiveCount > 0)
                Log.i(TAG, "Guardian linked — death anchor armed, active=$lastActiveCount")
            } catch (t: Throwable) {
                Log.e(TAG, "Guardian startWatching failed", t)
            }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            guardian = null
            Log.w(TAG, "Guardian disconnected")
        }
    }

    var isRunning = false
        private set

    var startedAt: Long = 0L
        private set

    /**
     * Adds a listener AND replays buffered history to it so newly-attached UIs
     * see everything since service start, not only events that arrive after.
     */
    fun addLogListener(listener: (String) -> Unit) {
        synchronized(logLock) {
            logBuffer.forEach { listener(it) }
        }
        logListeners.add(listener)
    }
    fun removeLogListener(listener: (String) -> Unit) { logListeners.remove(listener) }

    fun getLogSnapshot(): List<String> = synchronized(logLock) { logBuffer.toList() }

    fun clearLogBuffer() {
        synchronized(logLock) { logBuffer.clear() }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        Log.i(TAG, "GatewayService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopGateway()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RELOAD_CONFIG -> {
                if (isRunning) {
                    log("[NET] Reloading SIP config…")
                    stopGateway()
                    startGateway()
                    updateNotification("Reloaded - connecting...")
                }
            }
            else -> {
                startForeground(NOTIFICATION_ID, buildNotification("Starting..."))
                startGateway()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopGateway()
        instance = null
        Log.i(TAG, "GatewayService destroyed")
        super.onDestroy()
    }

    private fun startGateway() {
        if (isRunning) return

        val prefs = Preferences(this)
        val configs = prefs.getSipConfigs()

        if (configs.isEmpty()) {
            log("[NET] ✗ No SIP accounts configured — open settings to add one")
            updateNotification("No SIP accounts configured")
            return
        }

        installCrashHandler()
        bindGuardian()

        // Initialize components
        sipEngine = SipEngine()
        gsmCallManager = GsmCallManager(this)
        smsHandler = SmsHandler(this)
        callBridge = CallBridge(
            sipEngine,
            gsmCallManager,
            context = this,
            useSpeakerphoneLoopback = prefs.speakerphoneLoopback,
            forwardExtension = prefs.forwardExtension,
            muteDeviceMic = prefs.muteDeviceMic
        )
        smsBridge = SmsBridge(sipEngine, smsHandler, forwardExtension = prefs.forwardExtension)

        // Set up logging
        sipEngine.onLog = { msg -> log(msg) }
        callBridge.onLog = { msg -> log(msg) }
        smsBridge.onLog = { msg -> log(msg) }

        // Tell the guardian whenever Copperhead enters or leaves the "has an
        // active bridged call" state. The guardian uses this to gate its
        // death-recipient: if we die with this flag true, it ends the call.
        callBridge.onActiveCountChange = { count ->
            lastActiveCount = count
            try { guardian?.setHasActiveCall(count > 0) } catch (t: Throwable) {
                Log.w(TAG, "guardian.setHasActiveCall failed", t)
            }
        }

        // Start components
        gsmCallManager.start()
        smsHandler.start()

        // Register for SIP registration status
        sipEngine.onRegistrationChanged = { registered ->
            val status = if (registered) "Registered" else "Not registered"
            updateNotification("SIP: $status")
        }

        // Start SIP with first config (TODO: support multiple accounts)
        val sipConfig = configs.first()
        sipEngine.start(sipConfig)

        // Start bridges
        callBridge.start()
        smsBridge.start()

        // Acquire wakelocks to prevent sleep
        acquireLocks()

        // Best-effort root hardening (does nothing if no root)
        kotlin.concurrent.thread {
            BatteryOptimizer.protectProcess()
        }

        isRunning = true
        startedAt = System.currentTimeMillis()
        log("[NET] Gateway started — ${configs.size} account(s) loaded")
        updateNotification("Running - connecting...")
    }

    private fun stopGateway() {
        if (!isRunning) return

        callBridge.stop()
        smsBridge.stop()
        sipEngine.stop()
        gsmCallManager.stop()
        smsHandler.stop()

        releaseLocks()
        // Tell the guardian we're shutting down cleanly so it doesn't see
        // our subsequent process exit as a crash with an active bridge.
        try { guardian?.setHasActiveCall(false) } catch (_: Throwable) {}
        unbindGuardian()
        uninstallCrashHandler()

        isRunning = false
        startedAt = 0L
        log("[NET] Gateway stopped")
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // User swiped Copperhead from recents. Foreground service may keep
        // running but the user clearly intended to dismiss the app, and an
        // active cellular leg should not survive that. Tear down calls now.
        Log.w(TAG, "Task removed — emergency teardown of active calls")
        if (::callBridge.isInitialized) {
            try { callBridge.stop() } catch (t: Throwable) { Log.e(TAG, "onTaskRemoved teardown failed", t) }
        }
        super.onTaskRemoved(rootIntent)
    }

    /**
     * Wrap the default uncaught-exception handler so that any JVM crash on
     * any thread first hangs up the cellular leg (closing the mic-to-modem
     * path) before the OS kills the process. Chains to the previous handler
     * so Android's normal crash UI still fires.
     *
     * Does NOT cover native crashes (SIGSEGV/SIGABRT in JNI, e.g. the G.722
     * codec). For that we'd need a sigaction handler in C; the call to end
     * the call would have to go via a sidecar process with linkToDeath.
     */
    private fun installCrashHandler() {
        if (previousUncaughtHandler != null) return
        previousUncaughtHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                Log.e(TAG, "FATAL on ${thread.name}: ${throwable.javaClass.simpleName}: ${throwable.message} — emergency hangup", throwable)
                if (::callBridge.isInitialized) {
                    try { callBridge.stop() } catch (_: Throwable) {}
                }
            } finally {
                val prev = previousUncaughtHandler
                if (prev != null) prev.uncaughtException(thread, throwable)
                else android.os.Process.killProcess(android.os.Process.myPid())
            }
        }
    }

    private fun uninstallCrashHandler() {
        if (previousUncaughtHandler != null) {
            Thread.setDefaultUncaughtExceptionHandler(previousUncaughtHandler)
            previousUncaughtHandler = null
        }
    }

    private fun bindGuardian() {
        if (guardianBound) return
        val intent = Intent(this, GuardianService::class.java)
        guardianBound = bindService(intent, guardianConnection, Context.BIND_AUTO_CREATE)
        if (!guardianBound) Log.e(TAG, "Guardian bind failed — death-pact NOT armed")
    }

    private fun unbindGuardian() {
        if (!guardianBound) return
        try { unbindService(guardianConnection) } catch (t: Throwable) {
            Log.w(TAG, "guardian unbind failed", t)
        }
        guardianBound = false
        guardian = null
    }

    private fun acquireLocks() {
        try {
            val pm = getSystemService(PowerManager::class.java)
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "copperhead:gateway"
            ).apply { acquire() }
        } catch (e: Exception) {
            Log.w(TAG, "Could not acquire wake lock", e)
        }

        try {
            val wm = applicationContext.getSystemService(WifiManager::class.java)
            wifiLock = wm.createWifiLock(
                WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                "copperhead:gateway"
            ).apply { acquire() }
        } catch (e: Exception) {
            Log.w(TAG, "Could not acquire WiFi lock", e)
        }
    }

    private fun releaseLocks() {
        try { wakeLock?.release() } catch (_: Exception) {}
        wakeLock = null
        try { wifiLock?.release() } catch (_: Exception) {}
        wifiLock = null
    }

    private fun log(message: String) {
        val ts = logTimestamp.format(Date())
        val line = "[$ts] $message"
        Log.i(TAG, line)
        synchronized(logLock) {
            logBuffer.addLast(line)
            while (logBuffer.size > LOG_BUFFER_MAX) logBuffer.removeFirst()
        }
        logListeners.forEach { it(line) }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Copperhead Gateway",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "GSM-SIP Gateway service"
            setShowBadge(false)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(status: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, GatewayService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_phone_call)
            .setContentTitle("Copperhead Gateway")
            .setContentText(status)
            .setContentIntent(openIntent)
            .addAction(Notification.Action.Builder(
                null, "Stop", stopIntent
            ).build())
            .setOngoing(true)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun updateNotification(status: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(status))
    }
}
