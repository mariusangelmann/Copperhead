package com.copperhead.gateway.util

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * Manages detection, installation, and status of the Magisk module
 * that grants privileged permissions to the gateway app.
 *
 * The module installs a permission whitelist XML to
 * /system/etc/permissions/privapp-permissions-copperhead.xml
 * via Magisk's overlay at /data/adb/modules/copperhead-gateway/.
 */
object MagiskModule {
    private const val TAG = "MagiskModule"
    private const val MODULE_PATH = "/data/adb/modules/copperhead-gateway"
    private const val PRIV_APP_DIR_NAME = "CopperheadGateway"
    /** Path inside the Magisk overlay that surfaces as /system/priv-app/CopperheadGateway. */
    private const val PRIV_APP_OVERLAY_DIR = "/data/adb/modules/copperhead-gateway/system/priv-app/CopperheadGateway"

    val PRIVILEGED_PERMISSIONS = arrayOf(
        "android.permission.READ_LOGS",
        "android.permission.CAPTURE_AUDIO_OUTPUT",
        "android.permission.READ_PRECISE_PHONE_STATE",
        "android.permission.MODIFY_PHONE_STATE",
        "android.permission.REGISTER_CALL_PROVIDER",
        "android.permission.BIND_INCALL_SERVICE",
        "android.permission.READ_PRIVILEGED_PHONE_STATE",
        "android.permission.INTERACT_ACROSS_USERS",
        "android.permission.MANAGE_ONGOING_CALLS"
    )

    private val MODULE_ASSETS = listOf(
        "module.prop",
        "system/etc/permissions/privapp-permissions-copperhead.xml",
        "sepolicy.rule"
    )

    enum class ModuleState {
        /** Permissions granted -- module is working. */
        ACTIVE,
        /** Module files on disk but permissions not yet effective (needs reboot). */
        NEEDS_REBOOT,
        /** Root available but module not installed. Can auto-install. */
        NOT_INSTALLED,
        /** Module installed but has a "disable" file. */
        DISABLED,
        /** No root access. Must flash manually via Magisk Manager. */
        NO_ROOT
    }

    /**
     * Determine current module state. Must be called off the main thread.
     */
    fun getState(context: Context): ModuleState {
        if (arePrivilegedPermissionsGranted(context) && isPrivApp(context)) {
            return ModuleState.ACTIVE
        }

        if (!BatteryOptimizer.isRootAvailable()) {
            return ModuleState.NO_ROOT
        }

        val modulePropExists = runRootTest("test -f $MODULE_PATH/module.prop")
        if (!modulePropExists) {
            return ModuleState.NOT_INSTALLED
        }

        val disableFileExists = runRootTest("test -f $MODULE_PATH/disable")
        if (disableFileExists) {
            return ModuleState.DISABLED
        }

        return ModuleState.NEEDS_REBOOT
    }

    /**
     * Returns true if the app is installed as /system/priv-app/. Without this,
     * the privapp-permissions XML is ignored by Android no matter what
     * permissions it lists.
     */
    fun isPrivApp(context: Context): Boolean {
        val sourceDir = context.applicationInfo.sourceDir
        return sourceDir.startsWith("/system/priv-app/") ||
                sourceDir.startsWith("/system_ext/priv-app/") ||
                sourceDir.startsWith("/product/priv-app/") ||
                sourceDir.startsWith("/vendor/priv-app/")
    }

    /** The currently running APK path. Used to seed the priv-app overlay. */
    fun currentApkPath(context: Context): String = context.applicationInfo.sourceDir

    fun arePrivilegedPermissionsGranted(context: Context): Boolean {
        return PRIVILEGED_PERMISSIONS.all {
            context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun grantedPrivilegedCount(context: Context): Int {
        return PRIVILEGED_PERMISSIONS.count {
            context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }
    }

    sealed class InstallResult {
        object Success : InstallResult()
        data class Error(val message: String) : InstallResult()
    }

    /**
     * Extract module assets from APK and copy to Magisk modules directory via root.
     * Must be called off the main thread.
     */
    fun install(context: Context): InstallResult {
        try {
            val cacheDir = File(context.cacheDir, "magisk_module")
            cacheDir.deleteRecursively()

            for (assetPath in MODULE_ASSETS) {
                val destFile = File(cacheDir, assetPath)
                destFile.parentFile?.mkdirs()
                context.assets.open("magisk/$assetPath").use { input ->
                    FileOutputStream(destFile).use { output ->
                        input.copyTo(output)
                    }
                }
            }

            val mkdirOk = runAsRoot(
                "mkdir -p $MODULE_PATH/system/etc/permissions && " +
                "mkdir -p $PRIV_APP_OVERLAY_DIR"
            )
            if (!mkdirOk) {
                cleanup(cacheDir)
                return InstallResult.Error("Failed to create module directories (su denied?)")
            }

            for (assetPath in MODULE_ASSETS) {
                val srcFile = File(cacheDir, assetPath)
                val destPath = "$MODULE_PATH/$assetPath"
                val cpOk = runAsRoot("cp ${srcFile.absolutePath} $destPath")
                if (!cpOk) {
                    cleanup(cacheDir)
                    return InstallResult.Error("Failed to copy $assetPath")
                }
            }

            // Deploy the currently running APK into the priv-app overlay so
            // that after reboot Android picks it up from /system/priv-app/.
            // Without this, the privapp-permissions XML is silently ignored
            // because the package isn't classified as a priv-app.
            val runningApk = context.applicationInfo.sourceDir
            val apkDest = "$PRIV_APP_OVERLAY_DIR/$PRIV_APP_DIR_NAME.apk"
            val apkCopyOk = runAsRoot("cp '$runningApk' '$apkDest'")
            if (!apkCopyOk) {
                cleanup(cacheDir)
                return InstallResult.Error(
                    "Failed to copy APK to priv-app overlay ($runningApk → $apkDest)"
                )
            }

            runAsRoot("chown -R 0:0 $MODULE_PATH")
            runAsRoot("find $MODULE_PATH -type d -exec chmod 0755 {} +")
            runAsRoot("find $MODULE_PATH -type f -exec chmod 0644 {} +")
            runAsRoot("rm -f $MODULE_PATH/disable")

            cleanup(cacheDir)

            Log.i(TAG, "Module installed to $MODULE_PATH with APK at $apkDest")
            return InstallResult.Success
        } catch (e: Exception) {
            Log.e(TAG, "Module install failed", e)
            return InstallResult.Error(e.message ?: "Unknown error")
        }
    }

    fun enable(): Boolean {
        return runAsRoot("rm -f $MODULE_PATH/disable")
    }

    fun reboot(): Boolean {
        return runAsRoot("reboot")
    }

    private fun runRootTest(command: String): Boolean {
        return try {
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            val exit = proc.waitFor()
            proc.destroy()
            exit == 0
        } catch (_: Exception) {
            false
        }
    }

    private fun runAsRoot(command: String): Boolean {
        return try {
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            val exit = proc.waitFor()
            proc.destroy()
            exit == 0
        } catch (e: Exception) {
            Log.d(TAG, "Root command failed: $command", e)
            false
        }
    }

    private fun cleanup(dir: File) {
        try { dir.deleteRecursively() } catch (_: Exception) {}
    }
}
