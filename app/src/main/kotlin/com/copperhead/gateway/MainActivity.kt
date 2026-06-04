package com.copperhead.gateway

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.transition.AutoTransition
import androidx.transition.TransitionManager
import com.copperhead.gateway.databinding.ActivityMainBinding
import com.copperhead.gateway.gsm.GsmCallManager
import com.copperhead.gateway.service.GatewayService
import com.copperhead.gateway.sip.SipConfig
import com.copperhead.gateway.util.MicGuard
import com.copperhead.gateway.util.Preferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Preferences
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
    private var logLines = 0
    // How many lines we keep in the inline tail preview (full log lives in LogActivity).
    private val previewMaxLines = 14
    private val previewLines = ArrayDeque<String>()

    // Collapsible SIP section state
    private var sipExpanded = false

    // Pulse animation for status indicator
    private var pulseAnimator: ObjectAnimator? = null

    // Uptime counter
    private val uptimeHandler = Handler(Looper.getMainLooper())
    private val uptimeRunnable = object : Runnable {
        override fun run() {
            updateUptime()
            uptimeHandler.postDelayed(this, 1000)
        }
    }

    private val logListener: (String) -> Unit = { line ->
        runOnUiThread {
            appendLogLine(line)
            updateStatus()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)

        prefs = Preferences(this)
        setupUI()
        setupSipCollapse()
        loadConfig()
        updateStatus()

        // Staggered entrance animation on fresh launch
        if (savedInstanceState == null) {
            animateEntrance()
        }
    }

    override fun onResume() {
        super.onResume()
        // Mirror the service's authoritative log buffer rather than diffing.
        previewLines.clear()
        logLines = 0
        binding.logText.text = ""
        binding.logLineCount.text = ""
        GatewayService.instance?.addLogListener(logListener)
        updateStatus()
        loadSimInfo()
        // Re-sync mic-block switch in case the user flipped it via Quick
        // Settings while we were paused.
        refreshMicGuardSwitch()
    }

    override fun onPause() {
        GatewayService.instance?.removeLogListener(logListener)
        stopUptimeCounter()
        stopPulse()
        super.onPause()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_setup -> {
                openSetup()
                true
            }
            R.id.action_audio_probe -> {
                runAudioProbe()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun runAudioProbe() {
        appendLog(getString(R.string.audio_probe_running))
        kotlin.concurrent.thread(isDaemon = true) {
            val report = com.copperhead.gateway.util.AudioDiagnostics.probe(this@MainActivity)
            runOnUiThread {
                val clip = getSystemService(android.content.ClipboardManager::class.java)
                clip.setPrimaryClip(
                    android.content.ClipData.newPlainText("Copperhead audio HAL probe", report)
                )
                Toast.makeText(this, getString(R.string.audio_probe_copied), Toast.LENGTH_LONG).show()
                // Surface a few key lines in the log too
                report.lineSequence().filter {
                    it.contains(Regex("Voice|voice|incall|in_call|VoiceMMode|MM_FE", RegexOption.IGNORE_CASE))
                }.take(10).forEach { appendLog("[PROBE] $it") }
                appendLog("[PROBE] Full report copied — paste it back to the conversation.")
            }
        }
    }

    // ── Entrance Animation ──────────────────────────────────────────

    private fun animateEntrance() {
        val cards = listOf(
            binding.heroCard,
            binding.simCard,
            binding.sipCard,
            binding.settingsCard,
            binding.logCard
        )
        cards.forEachIndexed { index, view ->
            view.alpha = 0f
            view.translationY = 40f
            view.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(400)
                .setStartDelay((index * 80).toLong())
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }

    // ── Pulse Animation ─────────────────────────────────────────────

    private fun startPulse() {
        if (pulseAnimator?.isRunning == true) return
        pulseAnimator = ObjectAnimator.ofFloat(binding.statusIndicator, View.ALPHA, 1f, 0.3f).apply {
            duration = 1200
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            start()
        }
    }

    private fun stopPulse() {
        pulseAnimator?.cancel()
        pulseAnimator = null
        binding.statusIndicator.alpha = 1f
    }

    // ── Uptime Counter ──────────────────────────────────────────────

    private fun startUptimeCounter() {
        uptimeHandler.removeCallbacks(uptimeRunnable)
        uptimeHandler.post(uptimeRunnable)
    }

    private fun stopUptimeCounter() {
        uptimeHandler.removeCallbacks(uptimeRunnable)
    }

    private fun updateUptime() {
        val startTime = GatewayService.instance?.startedAt ?: return
        if (startTime == 0L) return
        val elapsed = System.currentTimeMillis() - startTime
        val seconds = (elapsed / 1000) % 60
        val minutes = (elapsed / 60000) % 60
        val hours = elapsed / 3600000
        binding.statUptime.text = when {
            hours > 0 -> "${hours}h ${minutes}m"
            minutes > 0 -> "${minutes}m ${seconds}s"
            else -> "${seconds}s"
        }
    }

    // ── Collapsible SIP Section ─────────────────────────────────────

    private fun setupSipCollapse() {
        // Start collapsed if config exists, expanded if not
        val config = prefs.getSipConfig(0)
        sipExpanded = config == null
        updateSipCollapseState(animated = false)

        binding.sipAccountHeader.setOnClickListener {
            sipExpanded = !sipExpanded
            updateSipCollapseState(animated = true)
        }
    }

    private fun updateSipCollapseState(animated: Boolean) {
        if (animated) {
            TransitionManager.beginDelayedTransition(
                binding.sipCard,
                AutoTransition().apply { duration = 250 }
            )
            binding.sipCollapseIcon.animate()
                .rotation(if (sipExpanded) 180f else 0f)
                .setDuration(200)
                .start()
        } else {
            binding.sipCollapseIcon.rotation = if (sipExpanded) 180f else 0f
        }

        if (sipExpanded) {
            binding.sipAccountBody.visibility = View.VISIBLE
            binding.sipAccountSummary.visibility = View.GONE
        } else {
            binding.sipAccountBody.visibility = View.GONE
            updateSipSummary()
        }
    }

    private fun updateSipSummary() {
        val config = prefs.getSipConfig(0)
        if (config != null) {
            binding.sipAccountSummary.text = "${config.username}@${config.domain}"
            binding.sipAccountSummary.visibility = View.VISIBLE
        } else {
            binding.sipAccountSummary.visibility = View.GONE
        }
    }

    // ── UI Setup ────────────────────────────────────────────────────

    private fun setupUI() {
        // Toggle gateway
        binding.toggleButton.setOnClickListener {
            val service = GatewayService.instance
            if (service?.isRunning == true) {
                stopGateway()
            } else {
                startGateway()
            }
        }

        // SIM slot dropdown
        val simOptions = arrayOf("Any", "SIM 1", "SIM 2")
        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, simOptions)
        binding.spinnerSimSlot.setAdapter(adapter)

        // Save account
        binding.btnSaveAccount.setOnClickListener { saveAccount() }

        // Settings switches
        binding.switchAutoStart.isChecked = prefs.autoStartEnabled
        binding.switchAutoStart.setOnCheckedChangeListener { _, checked ->
            prefs.autoStartEnabled = checked
        }

        binding.switchForwardIncoming.isChecked = prefs.forwardIncomingCalls
        binding.switchForwardIncoming.setOnCheckedChangeListener { _, checked ->
            prefs.forwardIncomingCalls = checked
        }

        binding.switchSpeakerphoneLoopback.isChecked = prefs.speakerphoneLoopback
        binding.switchSpeakerphoneLoopback.setOnCheckedChangeListener { _, checked ->
            prefs.speakerphoneLoopback = checked
            appendLog("[CFG] Speakerphone loopback ${if (checked) "ENABLED" else "DISABLED"} — restart gateway to apply")
            if (GatewayService.instance?.isRunning == true) {
                val reloadIntent = Intent(this, GatewayService::class.java).apply {
                    action = GatewayService.ACTION_RELOAD_CONFIG
                }
                startService(reloadIntent)
            }
        }

        binding.switchMuteDeviceMic.isChecked = prefs.muteDeviceMic
        binding.switchMuteDeviceMic.setOnCheckedChangeListener { _, checked ->
            prefs.muteDeviceMic = checked
            appendLog("[CFG] Device microphone ${if (checked) "MUTED (gateway mode)" else "LIVE (handset mode)"} — applies to next call")
            if (GatewayService.instance?.isRunning == true) {
                val reloadIntent = Intent(this, GatewayService::class.java).apply {
                    action = GatewayService.ACTION_RELOAD_CONFIG
                }
                startService(reloadIntent)
            }
        }

        // System-wide mic kill switch. Reflects the actual SensorPrivacyManager
        // state — not stored in our prefs, because the user can toggle it
        // elsewhere (Quick Settings) and we want the switch to show truth.
        wireMicGuardSwitch()

        // Clear log
        binding.btnClearLog.setOnClickListener {
            previewLines.clear()
            logLines = 0
            binding.logText.text = ""
            binding.logLineCount.text = ""
            GatewayService.instance?.clearLogBuffer()
        }

        // Tap the inline preview to open the full-screen log viewer.
        binding.logPreviewContainer.setOnClickListener {
            startActivity(Intent(this, LogActivity::class.java))
        }
    }

    private fun loadConfig() {
        val config = prefs.getSipConfig(0)
        if (config != null) {
            binding.editSipServer.setText(config.domain)
            binding.editSipUsername.setText(config.username)
            binding.editSipPassword.setText(config.password)
            binding.editSipPort.setText(config.port.toString())
            val simText = when (config.simSlot) {
                0 -> "SIM 1"
                1 -> "SIM 2"
                else -> "Any"
            }
            binding.spinnerSimSlot.setText(simText, false)
            binding.editOutboundProxy.setText(config.outboundProxy ?: "")
        }
        binding.editForwardExtension.setText(prefs.forwardExtension)
    }

    private fun saveAccount() {
        val server = binding.editSipServer.text?.toString()?.trim() ?: ""
        val username = binding.editSipUsername.text?.toString()?.trim() ?: ""
        val password = binding.editSipPassword.text?.toString()?.trim() ?: ""
        val port = binding.editSipPort.text?.toString()?.toIntOrNull() ?: 5060
        val simSlot = when (binding.spinnerSimSlot.text?.toString()) {
            "SIM 1" -> 0
            "SIM 2" -> 1
            else -> -1
        }

        if (server.isBlank() || username.isBlank()) {
            Toast.makeText(this, "Server and username are required", Toast.LENGTH_SHORT).show()
            return
        }

        val proxy = binding.editOutboundProxy.text?.toString()?.trim()?.ifBlank { null }

        val config = SipConfig(
            displayName = username,
            username = username,
            password = password,
            domain = server,
            port = port,
            simSlot = simSlot,
            outboundProxy = proxy
        )

        val forwardExt = binding.editForwardExtension.text?.toString()?.trim().orEmpty()
        prefs.forwardExtension = forwardExt

        prefs.saveSipConfig(0, config)
        Toast.makeText(this, getString(R.string.account_saved), Toast.LENGTH_SHORT).show()
        val proxyInfo = if (proxy != null) " via $proxy" else ""
        val fwdInfo = if (forwardExt.isNotBlank()) " → ext $forwardExt" else " (⚠ no forward extension)"
        appendLog("[CFG] Saved $username@$server:$port$proxyInfo (SIM ${if (simSlot < 0) "Any" else simSlot})$fwdInfo")

        // If the gateway is already running, the SipEngine has cached the OLD
        // config — it will keep re-registering to the old domain until reloaded.
        if (GatewayService.instance?.isRunning == true) {
            val reloadIntent = Intent(this, GatewayService::class.java).apply {
                action = GatewayService.ACTION_RELOAD_CONFIG
            }
            startService(reloadIntent)
            appendLog("[CFG] Live config change — reloading gateway…")
            binding.root.postDelayed({ updateStatus() }, 500)
        }

        // Auto-collapse after saving
        sipExpanded = false
        updateSipCollapseState(animated = true)
    }

    // ── Gateway Control ─────────────────────────────────────────────

    private fun startGateway() {
        val configs = prefs.getSipConfigs()
        if (configs.isEmpty()) {
            Toast.makeText(this, getString(R.string.no_accounts), Toast.LENGTH_SHORT).show()
            return
        }

        val intent = Intent(this, GatewayService::class.java).apply {
            action = GatewayService.ACTION_START
        }
        // startForegroundService schedules onStartCommand on the main looper,
        // so it has NOT run yet when this call returns. Post the listener
        // attach to the same looper queue — it will run AFTER onCreate +
        // onStartCommand, and the service's log buffer will replay everything
        // that happened up to that point.
        startForegroundService(intent)
        binding.root.post {
            GatewayService.instance?.addLogListener(logListener)
            updateStatus()
        }
    }

    private fun stopGateway() {
        val intent = Intent(this, GatewayService::class.java).apply {
            action = GatewayService.ACTION_STOP
        }
        startService(intent)
        binding.root.postDelayed({ updateStatus() }, 500)
    }

    // ── Status Update ───────────────────────────────────────────────

    private fun updateStatus() {
        val service = GatewayService.instance
        val running = service?.isRunning == true

        if (running) {
            // Green indicator + pulse
            binding.statusIndicator.setBackgroundResource(R.drawable.bg_status_indicator)
            startPulse()

            // Live badge
            binding.statusBadge.text = getString(R.string.status_badge_live)
            binding.statusBadge.setBackgroundResource(R.drawable.bg_status_badge_live)

            // Toggle button
            binding.toggleButton.text = getString(R.string.stop_gateway)

            // Stats row visible + uptime counting
            binding.statsRow.visibility = View.VISIBLE
            startUptimeCounter()

            val sipEngine = service?.sipEngine
            val registered = sipEngine?.isRegistered == true
            val localAddr = sipEngine?.let { "${it.localIp}:${it.localPort}" } ?: ""

            if (registered) {
                binding.statusText.text = getString(R.string.status_registered)
                binding.sipStatusText.visibility = View.VISIBLE
                binding.sipStatusText.text = "Connected to ${prefs.getSipConfig(0)?.domain ?: "SIP server"}"
                binding.heroSubtext.text = "Local: $localAddr"
                binding.heroSubtext.visibility = View.VISIBLE
                binding.statSip.text = "OK"
                binding.statBridge.text = "Ready"
            } else {
                binding.statusText.text = getString(R.string.status_running)
                binding.sipStatusText.visibility = View.VISIBLE
                binding.sipStatusText.text = "Connecting\u2026 | $localAddr"
                binding.heroSubtext.text = getString(R.string.hero_connecting_hint)
                binding.heroSubtext.visibility = View.VISIBLE
                binding.statSip.text = "\u2026"
                binding.statBridge.text = "Wait"
            }
        } else {
            // Red indicator, no pulse
            binding.statusIndicator.setBackgroundResource(R.drawable.bg_status_indicator_off)
            stopPulse()

            // Offline badge
            binding.statusBadge.text = getString(R.string.status_badge_offline)
            binding.statusBadge.setBackgroundResource(R.drawable.bg_status_badge_offline)

            // Status text
            binding.statusText.text = getString(R.string.gateway_offline)
            binding.toggleButton.text = getString(R.string.start_gateway)
            binding.sipStatusText.visibility = View.GONE
            binding.heroSubtext.text = getString(R.string.hero_offline_hint)
            binding.heroSubtext.visibility = View.VISIBLE

            // Hide stats
            binding.statsRow.visibility = View.GONE
            stopUptimeCounter()
        }
    }

    // ── SIM Info ────────────────────────────────────────────────────

    private fun loadSimInfo() {
        binding.simContainer.removeAllViews()

        try {
            val gsmManager = GsmCallManager.instance ?: GsmCallManager(this).also { it.start() }
            val sims = gsmManager.getSimInfoList()

            if (sims.isEmpty()) {
                val tv = TextView(this).apply {
                    text = "No SIM cards detected"
                    setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
                    alpha = 0.5f
                }
                binding.simContainer.addView(tv)
            } else {
                for (sim in sims) {
                    val view = LayoutInflater.from(this).inflate(R.layout.item_sim, binding.simContainer, false)
                    view.findViewById<TextView>(R.id.simSlotLabel).text = "${sim.slotIndex + 1}"
                    view.findViewById<TextView>(R.id.simName).text = sim.displayName
                    view.findViewById<TextView>(R.id.simCarrier).text = buildString {
                        append(sim.carrierName)
                        if (sim.number.isNotBlank()) append(" \u2022 ${sim.number}")
                    }
                    binding.simContainer.addView(view)
                }
            }
        } catch (e: SecurityException) {
            val tv = TextView(this).apply {
                text = "Permission required to read SIM info"
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
                alpha = 0.5f
            }
            binding.simContainer.addView(tv)
        }
    }

    // ── Log ─────────────────────────────────────────────────────────

    /** For lines that already carry a timestamp (from GatewayService). */
    private fun appendLogLine(line: String) {
        logLines++
        previewLines.addLast(line)
        while (previewLines.size > previewMaxLines) previewLines.removeFirst()
        binding.logText.text = previewLines.joinToString("\n")
        binding.logLineCount.text = "$logLines lines"
    }

    /** For UI-side events that don't go through the service. */
    private fun appendLog(message: String) {
        appendLogLine("[${dateFormat.format(Date())}] $message")
    }

    private fun wireMicGuardSwitch() {
        val supported = MicGuard.isSupported(this)
        binding.switchBlockMicSystemWide.isEnabled = supported
        if (!supported) {
            binding.switchBlockMicSystemWide.text = getString(R.string.block_mic_unsupported)
            binding.switchBlockMicSystemWide.setOnCheckedChangeListener(null)
            binding.switchBlockMicSystemWide.isChecked = false
            return
        }
        binding.switchBlockMicSystemWide.setOnCheckedChangeListener(null)
        binding.switchBlockMicSystemWide.isChecked = MicGuard.isBlocked(this)
        binding.switchBlockMicSystemWide.setOnCheckedChangeListener { sw, checked ->
            val ok = MicGuard.setBlocked(this, checked)
            if (!ok) {
                // Roll the switch back without re-firing this listener.
                sw.setOnCheckedChangeListener(null)
                sw.isChecked = !checked
                wireMicGuardSwitch()
                Toast.makeText(this, "Permission denied — needs priv-app via Magisk", Toast.LENGTH_LONG).show()
                return@setOnCheckedChangeListener
            }
            appendLog("[CFG] System mic kill switch ${if (checked) "ENGAGED (all apps muted)" else "DISENGAGED"}")
        }
    }

    private fun refreshMicGuardSwitch() {
        if (!MicGuard.isSupported(this)) return
        val actual = MicGuard.isBlocked(this)
        if (binding.switchBlockMicSystemWide.isChecked != actual) {
            binding.switchBlockMicSystemWide.setOnCheckedChangeListener(null)
            binding.switchBlockMicSystemWide.isChecked = actual
            wireMicGuardSwitch()
        }
    }

    fun openSetup() {
        prefs.setupCompleted = false
        startActivity(Intent(this, SetupActivity::class.java))
    }
}
