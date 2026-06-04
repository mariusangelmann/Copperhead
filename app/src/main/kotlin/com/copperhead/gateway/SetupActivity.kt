package com.copperhead.gateway

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.copperhead.gateway.databinding.ActivitySetupBinding
import com.copperhead.gateway.util.BatteryOptimizer
import com.copperhead.gateway.util.MagiskModule
import com.copperhead.gateway.util.Preferences
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlin.concurrent.thread

/**
 * First-run onboarding. Guides through 5 steps:
 *   1. Permissions
 *   2. Notifications
 *   3. Battery optimization
 *   4. Magisk module (detect, install, reboot)
 *   5. Root battery hardening (only if root available)
 *
 * Completed steps collapse to a single line. Progress bar tracks completion.
 * Every step is optional -- skip goes straight to the main screen.
 */
class SetupActivity : AppCompatActivity() {
    companion object {
        private const val PERMISSION_REQUEST = 200
        private const val BATTERY_REQUEST = 201
    }

    private lateinit var binding: ActivitySetupBinding
    private lateinit var prefs: Preferences
    private var hasRoot = false
    private var hardeningApplied = false
    private var moduleState = MagiskModule.ModuleState.NO_ROOT

    private val runtimePermissions = arrayOf(
        Manifest.permission.CALL_PHONE,
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.READ_PHONE_NUMBERS,
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.SEND_SMS,
        Manifest.permission.RECEIVE_SMS,
        Manifest.permission.READ_SMS,
        Manifest.permission.READ_CALL_LOG,
        Manifest.permission.WRITE_CALL_LOG,
        Manifest.permission.ANSWER_PHONE_CALLS,
        Manifest.permission.BLUETOOTH_CONNECT
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySetupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = Preferences(this)

        if (prefs.setupCompleted) {
            openMain()
            return
        }

        setupButtons()
        refreshStates()
    }

    override fun onResume() {
        super.onResume()
        refreshStates()
    }

    private fun setupButtons() {
        // Step 1: Permissions
        binding.btnGrantPermissions.setOnClickListener {
            val needed = runtimePermissions.filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }
            if (needed.isNotEmpty()) {
                ActivityCompat.requestPermissions(this, needed.toTypedArray(), PERMISSION_REQUEST)
            } else {
                Toast.makeText(this, "All permissions granted", Toast.LENGTH_SHORT).show()
            }
        }

        // Step 2: Notifications
        binding.btnGrantNotifications.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(
                        this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), PERMISSION_REQUEST
                    )
                } else {
                    Toast.makeText(this, "Notifications already enabled", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "Enabled by default on this Android version", Toast.LENGTH_SHORT).show()
            }
        }

        // Step 3: Battery
        binding.btnDisableBatteryOpt.setOnClickListener {
            if (BatteryOptimizer.isIgnoringBatteryOptimizations(this)) {
                Toast.makeText(this, "Already unrestricted", Toast.LENGTH_SHORT).show()
            } else {
                try {
                    @Suppress("DEPRECATION")
                    startActivityForResult(BatteryOptimizer.getBatterySettingsIntent(), BATTERY_REQUEST)
                } catch (_: Exception) {
                    Toast.makeText(this, "Open Settings > Battery > Copperhead > Unrestricted", Toast.LENGTH_LONG).show()
                }
            }
        }

        // Step 4: Magisk Module
        binding.btnInstallModule.setOnClickListener {
            when (moduleState) {
                MagiskModule.ModuleState.NOT_INSTALLED -> {
                    binding.btnInstallModule.isEnabled = false
                    binding.btnInstallModule.text = getString(R.string.setup_module_installing)
                    thread {
                        val result = MagiskModule.install(this@SetupActivity)
                        runOnUiThread {
                            binding.btnInstallModule.isEnabled = true
                            when (result) {
                                is MagiskModule.InstallResult.Success -> {
                                    Toast.makeText(this, "Module installed", Toast.LENGTH_SHORT).show()
                                }
                                is MagiskModule.InstallResult.Error -> {
                                    Toast.makeText(this, "Install failed: ${result.message}", Toast.LENGTH_LONG).show()
                                }
                            }
                            refreshStates()
                        }
                    }
                }
                MagiskModule.ModuleState.DISABLED -> {
                    thread {
                        MagiskModule.enable()
                        runOnUiThread { refreshStates() }
                    }
                }
                MagiskModule.ModuleState.NEEDS_REBOOT -> {
                    showRebootDialog()
                }
                MagiskModule.ModuleState.NO_ROOT -> {
                    refreshStates()
                }
                MagiskModule.ModuleState.ACTIVE -> { }
            }
        }

        binding.btnReboot.setOnClickListener {
            showRebootDialog()
        }

        // Step 5: Root hardening
        binding.btnApplyRoot.setOnClickListener {
            binding.btnApplyRoot.isEnabled = false
            binding.btnApplyRoot.text = "Applying..."
            thread {
                val whitelisted = BatteryOptimizer.whitelistWithRoot()
                val processProtected = BatteryOptimizer.protectProcess()
                hardeningApplied = whitelisted || processProtected

                runOnUiThread {
                    binding.btnApplyRoot.isEnabled = true
                    if (hardeningApplied) {
                        Toast.makeText(this, "Root protections applied", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "Some commands failed — try granting su access", Toast.LENGTH_SHORT).show()
                    }
                    refreshStates()
                }
            }
        }

        // Continue / Skip
        binding.btnContinue.setOnClickListener {
            prefs.setupCompleted = true
            openMain()
        }

        binding.btnSkip.setOnClickListener {
            prefs.setupCompleted = true
            openMain()
        }
    }

    private fun showRebootDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.setup_reboot_confirm_title)
            .setMessage(R.string.setup_reboot_confirm_message)
            .setPositiveButton("Reboot") { _, _ ->
                thread { MagiskModule.reboot() }
            }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun refreshStates() {
        // --- Step 1: Permissions ---
        val permissionsGranted = runtimePermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        val permCount = runtimePermissions.count {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        updateStep(
            binding.stepPermissionsIndicator, binding.stepPermissionsStatus,
            binding.stepPermissionsBadge, binding.stepPermissionsBody,
            permissionsGranted, "$permCount/${runtimePermissions.size}"
        )
        if (permissionsGranted) {
            binding.btnGrantPermissions.text = getString(R.string.setup_done)
            binding.btnGrantPermissions.isEnabled = false
        }

        // --- Step 2: Notifications ---
        val notifsOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else true
        updateStep(
            binding.stepNotifIndicator, binding.stepNotifStatus,
            binding.stepNotifBadge, binding.stepNotifBody,
            notifsOk, if (notifsOk) "Enabled" else "Disabled"
        )
        if (notifsOk) {
            binding.btnGrantNotifications.text = getString(R.string.setup_done)
            binding.btnGrantNotifications.isEnabled = false
        }

        // --- Step 3: Battery ---
        val batteryOk = BatteryOptimizer.isIgnoringBatteryOptimizations(this)
        updateStep(
            binding.stepBatteryIndicator, binding.stepBatteryStatus,
            binding.stepBatteryBadge, binding.stepBatteryBody,
            batteryOk, if (batteryOk) "Unrestricted" else "Restricted"
        )
        if (batteryOk) {
            binding.btnDisableBatteryOpt.text = getString(R.string.setup_done)
            binding.btnDisableBatteryOpt.isEnabled = false
        }

        // --- Step 4 & 5: Magisk Module + Root Hardening (async) ---
        thread {
            moduleState = MagiskModule.getState(this@SetupActivity)
            hasRoot = BatteryOptimizer.isRootAvailable()

            runOnUiThread {
                // Step 3: OEM battery hint (moved here from old root step)
                val oemHint = BatteryOptimizer.getOemBatteryKillerName()
                if (oemHint != null) {
                    binding.stepBatteryOemHint.visibility = View.VISIBLE
                    binding.stepBatteryOemHint.text =
                        "This device has $oemHint. Disable its restrictions for Copperhead."
                }

                // Step 4: Magisk Module
                val moduleOk = moduleState == MagiskModule.ModuleState.ACTIVE
                val grantedCount = MagiskModule.grantedPrivilegedCount(this)
                val totalPriv = MagiskModule.PRIVILEGED_PERMISSIONS.size

                val statusText = when (moduleState) {
                    MagiskModule.ModuleState.ACTIVE -> "$grantedCount/$totalPriv granted"
                    MagiskModule.ModuleState.NEEDS_REBOOT -> "Reboot needed"
                    MagiskModule.ModuleState.NOT_INSTALLED -> "Not installed"
                    MagiskModule.ModuleState.DISABLED -> "Disabled"
                    MagiskModule.ModuleState.NO_ROOT -> "No root"
                }

                updateStep(
                    binding.stepModuleIndicator, binding.stepModuleStatus,
                    binding.stepModuleBadge, binding.stepModuleBody,
                    moduleOk, statusText
                )

                when (moduleState) {
                    MagiskModule.ModuleState.ACTIVE -> {
                        binding.btnInstallModule.text = getString(R.string.setup_done)
                        binding.btnInstallModule.isEnabled = false
                        binding.btnReboot.visibility = View.GONE
                        binding.stepModuleManualHint.visibility = View.GONE
                        binding.stepModuleDesc.text = getString(R.string.setup_module_active)
                    }
                    MagiskModule.ModuleState.NEEDS_REBOOT -> {
                        binding.btnInstallModule.text = getString(R.string.setup_reboot_now)
                        binding.btnInstallModule.isEnabled = true
                        binding.btnReboot.visibility = View.VISIBLE
                        binding.stepModuleManualHint.visibility = View.GONE
                        binding.stepModuleDesc.text = getString(R.string.setup_module_installed)
                    }
                    MagiskModule.ModuleState.NOT_INSTALLED -> {
                        binding.btnInstallModule.text = getString(R.string.setup_install_module)
                        binding.btnInstallModule.isEnabled = true
                        binding.btnReboot.visibility = View.GONE
                        binding.stepModuleManualHint.visibility = View.GONE
                        binding.stepModuleDesc.text = getString(R.string.setup_module_desc)
                    }
                    MagiskModule.ModuleState.DISABLED -> {
                        binding.btnInstallModule.text = getString(R.string.setup_module_enable)
                        binding.btnInstallModule.isEnabled = true
                        binding.btnReboot.visibility = View.GONE
                        binding.stepModuleManualHint.visibility = View.GONE
                        binding.stepModuleDesc.text = getString(R.string.setup_module_disabled)
                    }
                    MagiskModule.ModuleState.NO_ROOT -> {
                        binding.btnInstallModule.text = getString(R.string.setup_module_check_again)
                        binding.btnInstallModule.isEnabled = true
                        binding.btnReboot.visibility = View.GONE
                        binding.stepModuleManualHint.visibility = View.VISIBLE
                        binding.stepModuleDesc.text = getString(R.string.setup_module_no_root)
                    }
                }

                // Step 5: Root hardening (only shown when root is available)
                if (hasRoot) {
                    binding.cardRootHardening.visibility = View.VISIBLE
                    updateStep(
                        binding.stepHardeningIndicator, binding.stepHardeningStatus,
                        binding.stepHardeningBadge, binding.stepHardeningBody,
                        hardeningApplied, if (hardeningApplied) "Applied" else "Not applied"
                    )
                    if (hardeningApplied) {
                        binding.btnApplyRoot.text = getString(R.string.setup_done)
                        binding.btnApplyRoot.isEnabled = false
                    }
                } else {
                    binding.cardRootHardening.visibility = View.GONE
                }

                updateProgress()
            }
        }

        // Update progress (without root — async callback will update again)
        updateProgress()
    }

    private fun updateStep(
        indicator: View, status: TextView,
        badge: TextView, body: View,
        ok: Boolean, statusText: String
    ) {
        indicator.setBackgroundResource(
            if (ok) R.drawable.bg_status_indicator else R.drawable.bg_status_indicator_off
        )
        status.text = statusText
        badge.setBackgroundResource(
            if (ok) R.drawable.bg_step_badge_done else R.drawable.bg_step_badge
        )
        if (ok) {
            badge.text = "\u2713"
        }
        // Collapse completed steps
        body.visibility = if (ok) View.GONE else View.VISIBLE
    }

    private fun updateProgress() {
        val permsDone = runtimePermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        val notifsDone = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else true
        val batteryDone = BatteryOptimizer.isIgnoringBatteryOptimizations(this)
        val moduleDone = moduleState == MagiskModule.ModuleState.ACTIVE

        val totalSteps = if (hasRoot) 5 else 4
        var completed = 0
        if (permsDone) completed++
        if (notifsDone) completed++
        if (batteryDone) completed++
        if (moduleDone) completed++
        if (hasRoot && hardeningApplied) completed++

        binding.progressText.text = getString(R.string.setup_progress, completed, totalSteps)
        binding.progressBar.progress = if (totalSteps > 0) (completed * 100) / totalSteps else 0

        if (completed >= totalSteps) {
            binding.btnContinue.text = getString(R.string.setup_finish)
        } else {
            binding.btnContinue.text = getString(R.string.setup_continue)
        }
    }

    private fun openMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshStates()
    }

    @Deprecated("Use registerForActivityResult")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        refreshStates()
    }
}
