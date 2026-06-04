package com.copperhead.gateway.util

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Probes the device's audio HAL surface so we can decide whether path B
 * (direct tinyalsa write to a modem PCM node) is feasible. Reads:
 *
 *  - /dev/snd/ — character devices for each ALSA card/PCM combination
 *  - /proc/asound/cards — registered ALSA cards (names hint at modem ID)
 *  - /proc/asound/pcm — every PCM stream with capture/playback direction
 *  - /vendor/etc/audio_policy*.xml — vendor's published routing tables
 *
 * Requires root for /vendor reads on some devices.
 */
object AudioDiagnostics {

    fun probe(context: Context? = null): String = buildString {
        appendLine("# Copperhead Audio HAL probe")
        appendLine()

        section("AudioDeviceInfo outputs (TYPE_TELEPHONY = 18 is the BCP-style cellular uplink path)") {
            if (context == null) {
                appendLine("(context not provided — skipped)")
            } else {
                val am = context.getSystemService(AudioManager::class.java)
                val outputs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                if (outputs.isEmpty()) {
                    appendLine("(no output devices reported)")
                } else {
                    for (d in outputs) {
                        val typeName = deviceTypeName(d.type)
                        val tag = if (d.type == AudioDeviceInfo.TYPE_TELEPHONY) "  ← TELEPHONY UPLINK!" else ""
                        appendLine("type=${d.type} ($typeName), id=${d.id}, productName='${d.productName}'$tag")
                    }
                    val hasTel = outputs.any { it.type == AudioDeviceInfo.TYPE_TELEPHONY }
                    appendLine()
                    if (hasTel) {
                        appendLine(">>> VERDICT: SIP → GSM injection via setPreferredDevice(TYPE_TELEPHONY) should work on this device.")
                    } else {
                        appendLine(">>> VERDICT: No TYPE_TELEPHONY device exposed. BCP-style injection won't work.")
                        appendLine(">>> Options: VOICE_UPLINK claim trick, tinyalsa direct write, or speakerphone-loopback.")
                    }
                }
            }
        }

        section("/proc/asound/cards") {
            shell("cat /proc/asound/cards 2>&1")
        }

        section("/proc/asound/pcm") {
            shell("cat /proc/asound/pcm 2>&1")
        }

        section("/dev/snd/ listing") {
            shell("ls -la /dev/snd/ 2>&1")
        }

        section("tinypcminfo / tinymix availability") {
            shell("which tinypcminfo tinymix tinyplay tinycap 2>&1; tinymix 2>&1 | head -3")
        }

        section("audio_policy_configuration.xml (vendor)") {
            shell(
                "su -c 'cat /vendor/etc/audio_policy_configuration.xml " +
                "/vendor/etc/audio/audio_policy_configuration.xml " +
                "/system/etc/audio_policy.conf 2>/dev/null | head -200' 2>&1"
            )
        }

        section("dumpsys media.audio_flinger — Mixer threads") {
            shell("dumpsys media.audio_flinger 2>&1 | grep -E 'Output thread|Input thread|Mixer|name' | head -40")
        }

        section("dumpsys media.audio_policy — Voice call output info") {
            shell(
                "dumpsys media.audio_policy 2>&1 | " +
                "grep -A3 -iE 'voice_call|in_call|telephony|incall' | head -60"
            )
        }

        section("ro.* audio properties") {
            shell("getprop | grep -iE 'audio|telephony|modem|voice' 2>&1")
        }

        section("Magisk modules & SELinux") {
            shell(
                "getenforce 2>&1; " +
                "su -c 'magisk --list 2>&1; magisk -v 2>&1' 2>&1"
            )
        }

        appendLine("# End of report.")
        appendLine("# To assess path B (tinyalsa direct write), look at /proc/asound/pcm:")
        appendLine("# each line is 'card,device : id : … : playback N : capture N'.")
        appendLine("# The modem uplink is usually one of the highest-numbered playback nodes,")
        appendLine("# often labelled 'Voice', 'VoiceMMode', 'MM_FE_VOICE' or similar.")
    }

    private fun deviceTypeName(t: Int): String = when (t) {
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "BUILTIN_EARPIECE"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "BUILTIN_SPEAKER"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "WIRED_HEADSET"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "WIRED_HEADPHONES"
        AudioDeviceInfo.TYPE_LINE_ANALOG -> "LINE_ANALOG"
        AudioDeviceInfo.TYPE_LINE_DIGITAL -> "LINE_DIGITAL"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "BLUETOOTH_SCO"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "BLUETOOTH_A2DP"
        AudioDeviceInfo.TYPE_HDMI -> "HDMI"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB_DEVICE"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB_HEADSET"
        AudioDeviceInfo.TYPE_TELEPHONY -> "TELEPHONY"
        AudioDeviceInfo.TYPE_BUS -> "BUS"
        AudioDeviceInfo.TYPE_REMOTE_SUBMIX -> "REMOTE_SUBMIX"
        else -> "type=$t"
    }

    private fun StringBuilder.section(title: String, body: StringBuilder.() -> Unit) {
        appendLine("## $title")
        appendLine("```")
        body()
        appendLine("```")
        appendLine()
    }

    private fun StringBuilder.shell(cmd: String) {
        try {
            val proc = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
            val out = BufferedReader(InputStreamReader(proc.inputStream)).readText().trim()
            proc.waitFor()
            if (out.isNotBlank()) appendLine(out)
            else appendLine("(no output)")
        } catch (e: Exception) {
            appendLine("(error: ${e.message})")
        }
    }
}
