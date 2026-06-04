# Copperhead — Android GSM-SIP Gateway

> **Self-hosted open-source replacement for hardware GSM gateways like Dinstar, GoIP, Yeastar TG, and OpenVox.** Turns any rooted Android phone into a cellular ↔ SIP bridge for Asterisk, FreePBX, FreeSWITCH, or Kamailio.

Dual-SIM. Two-way voice. Two-way SMS. Wideband G.722 audio. Self-contained SIP stack. ISC-licensed. No telemetry, no accounts, no cloud.

![Platform: Android 9+](https://img.shields.io/badge/Android-9.0%2B-3DDC84?logo=android&logoColor=white)
![Language: Kotlin](https://img.shields.io/badge/Kotlin-100%25-7F52FF?logo=kotlin&logoColor=white)
![License: ISC](https://img.shields.io/badge/License-ISC-blue.svg)
![Status: feature-complete](https://img.shields.io/badge/status-feature--complete-success)
![Contributions: closed](https://img.shields.io/badge/contributions-closed-lightgrey)
![Built with: AI](https://img.shields.io/badge/built%20with-AI-orange)

---

> [!WARNING]
> **Heads up — this is a personal project, almost entirely AI-generated.**
>
> I designed and steered Copperhead, but the vast majority of the Kotlin, native C, AIDL, the layout XML, and this README itself were written by Claude. I've tested the happy paths on my own hardware (Pixel 7 Pro, my own FreePBX) and the things in this README are things I actually use. But I have *not* line-by-line audited everything, and "looks right" is not the same as "is right" when it comes to code that processes real phone calls, SMS, and audio.
>
> **No warranty, no guarantees. There are almost certainly bugs and possibly security issues I haven't noticed.** Treat this like any other AI-generated code on the internet: read it before you run it, especially before you put it on a phone with a real SIM in it. If you find something broken or sketchy, your fork is the place to fix it — see [Project status](#project-status).

Copperhead lets a rooted Android phone act as a **GSM trunk for your VoIP PBX**. The phone registers as a normal SIP extension on Asterisk / FreePBX / FreeSWITCH / Kamailio, and then:

- When your PBX dials an outside number routed through Copperhead, the phone places the call over its cellular SIM.
- When a cellular call arrives on the SIM, Copperhead forwards it to your PBX as an inbound SIP call.
- SMS flows both directions too — incoming SMS becomes a SIP `MESSAGE`, outgoing `MESSAGE` becomes an SMS.

The result is functionally equivalent to a single-channel rack-mount GSM gateway, for $0 in extra hardware. If you already have an old phone in a drawer, you have a GSM trunk.

## Why people use it

**Replace expensive GSM gateway hardware.** A 1-channel Dinstar UC100 is ~$200. A GoIP-1 is ~$120. A Yeastar TG800 is ~$700. Copperhead does the 1-channel job on hardware most people already own. Run multiple phones in parallel if you need multiple cellular channels.

**Free cellular trunk for a home / homelab PBX.** Drop a cheap unlimited-minutes prepaid SIM (Mint, Visible, Tello in the US; o2 Prepaid, Aldi Talk, congstar in Germany) into the phone and your Asterisk gets a free outbound trunk for landline numbers and a real inbound mobile number.

**Cellular failover for SIP/VoIP.** When your primary SIP provider has an outage, route through the GSM gateway instead. Cellular networks rarely go down at the same time as Internet trunks.

**Wifi-Calling alternative you control.** When your carrier's official Wifi Calling implementation is missing, broken, or geo-blocked on your device, point SIP through Tailscale or a VPN and you've got carrier-independent Wifi calling on top of your own cellular SIM.

**Bring your mobile number into your PBX.** Give people one number to call (your cellular). Your PBX rings the right person at the right time — Slack notification, IVR, voicemail, follow-me — without forwarding charges from your carrier.

**SIP testing rig.** Need a real GSM endpoint to test PBX call flows, IVR, SMS, billing? Use Copperhead instead of an expensive test bed.

**SMS gateway for IoT and 2FA.** SMS-only mode runs without root. Receive 2FA codes, alarm triggers, M2M messages into your PBX or send programmatic SMS from dialplan / AMI / ARI.

## How Copperhead compares to commercial GSM gateways

|  | **Copperhead** | Dinstar UC100 | GoIP-1 | Yeastar TG800 |
|---|---|---|---|---|
| Channels per unit | 1 (run N phones) | 1 | 1 | 8 |
| Hardware cost | $0 (any rooted phone) | ~$200 | ~$120 | ~$700 |
| Dual-SIM | ✅ | — | — | — |
| Inbound + outbound voice | ✅ | ✅ | ✅ | ✅ |
| SMS in / out | ✅ | ✅ | ✅ | ✅ |
| Wideband audio (G.722) | ✅ | — (G.711 only) | — | — |
| Caller-ID propagation (PAI/RPID) | ✅ | partial | partial | ✅ |
| Source available | ✅ ISC | proprietary | proprietary | proprietary |
| Vendor lock-in | none | firmware-only | firmware-only | firmware-only |
| Idle power draw | ~2–3 W (phone) | ~5 W | ~3 W | ~12 W |
| Mobility | portable | rack / shelf | rack / shelf | rack |

If you need 30+ concurrent channels, buy a Yeastar. For 1–4 channels in a homelab or small office, Copperhead on phones is hard to beat on price-per-channel.

## How it works

```
                       SIP / UDP                                  GSM cellular
   Asterisk / FreePBX  <─────────────►  Copperhead Android  <────────────────►  Cell tower
                       INVITE / 200 OK                       Android Telecom
                       ACK / BYE                             InCallService
                       RTP  (G.722 / G.711 / G.711a)         AudioRecord + AudioTrack
                       MESSAGE                               BroadcastReceiver / SmsManager
                       OPTIONS, REGISTER                     SubscriptionManager (dual SIM)
```

**Inbound call (PBX → cellular):** Asterisk dials `PJSIP/+15551234567@copperhead`. Copperhead's SIP engine receives the INVITE, extracts the destination number, places the GSM call on the configured SIM via the Android Telecom InCallService API, and bridges audio between the RTP stream and the modem's call audio path.

**Outbound call (cellular → PBX):** A cellular call rings the phone. Copperhead auto-answers it, places a SIP INVITE to a pre-configured forward extension on the PBX (e.g. `999`), and bridges audio. The original caller's number is carried in `P-Asserted-Identity` and `Remote-Party-ID` so PBX CDR shows the real cellular caller, not the gateway extension.

**SMS:** Incoming SMS arrives via `SMS_RECEIVED` broadcast, gets wrapped in a SIP `MESSAGE` request with `text/plain` body, and is delivered to the configured forward extension. Outgoing SIP `MESSAGE` requests are sent via `SmsManager.sendTextMessage` (or `sendMultipartTextMessage` for long bodies) on the chosen SIM.

## Features

- **Bidirectional voice bridging** — SIP ↔ cellular, both call directions, with audio routed through the modem's call uplink/downlink
- **Bidirectional SMS** — SIP `MESSAGE` ↔ Android `SmsManager`, including multi-part SMS
- **Dual-SIM support** — bind each SIP account to a specific slot, or pick "any" and use the device default
- **Wideband audio** — G.722 (16 kHz) preferred; falls back to G.711 µ-law / A-law (8 kHz) when the peer can't do wideband
- **DTMF** — RFC 2833 `telephone-event` payload type
- **Digest authentication** — MD5, handles both `401 Unauthorized` and `407 Proxy Authentication Required`, supports `qop=auth`
- **Self-contained SIP stack** — no PJSIP, no Sofia-SIP, no reSIProcate; just Kotlin
- **Foreground service** — auto-restart on crash (START_STICKY), boot-on-boot receiver, wake/wifi locks
- **Privacy hardening** — mic is muted before the cellular leg connects so room audio can't leak during SIP negotiation; cellular call is auto-ended on app crash via a dedicated sidecar process with `IBinder.linkToDeath`; orphan-call cleanup runs on restart; optional system-wide mic kill switch via `SensorPrivacyManager` (hardware-level disconnect on Pixel 7+)
- **Caller-ID preservation** — `P-Asserted-Identity` (RFC 3325) + Cisco `Remote-Party-ID` propagate the original cellular caller's number through the PBX
- **Outbound-proxy / SBC support** — single host:port field routes all SIP through it without modifying message headers
- **Real-time wire logging** — toggleable full SIP message trace + per-bridge audio jitter / underrun / RTP rate stats

## Requirements

- **Android 9.0 (API 28) or newer**
- **Root** via [Magisk](https://github.com/topjohnwu/Magisk) — needed for the audio bridge and several signature-restricted telephony permissions. **SMS-only operation works without root.**
- **One or two SIM cards** with active service
- **A SIP server** reachable over IP (any of: Asterisk, FreePBX, FreeSWITCH, Kamailio, OpenSIPS)

### Device compatibility

**Only confirmed working on Pixel 7 Pro.** The audio-injection path Copperhead uses — creating an `AudioTrack` on the `AudioDeviceInfo.TYPE_TELEPHONY` output device so the audio is fed straight into the cellular uplink — exists on a small subset of newer Pixel phones because Google exposes that output for Google Dialer's call-screening feature. The technique itself was demonstrated by [chenxiaolong/BCP](https://github.com/chenxiaolong/BCP) (a tech-demo app that plays an audio file to the other party during a call). Copperhead uses the same mechanism in the same direction — toward the remote caller — but with a SIP RTP stream as the audio source instead of a file on disk. Full credit to that project; without its proof-of-concept the audio bridge here wouldn't exist.

Other phones — including most non-Pixel Androids and even some other Pixel generations — don't expose `TYPE_TELEPHONY` to userspace. On those, Copperhead falls back to the legacy `STREAM_VOICE_CALL` + `VOICE_UPLINK` claim trick, which works on a fraction of HALs and not on others. Test before relying on it.

If you want guaranteed audio bridging on a non-Pixel-7-Pro device, expect to do per-device research and possibly write a Magisk-deployed native helper that pokes `/dev/snd/pcmC*D*p` via tinyalsa directly.

### Why root is needed

These permissions are signature-only on stock Android. The included Magisk module installs Copperhead as a priv-app and grants:

| Permission | Used for |
|---|---|
| `CAPTURE_AUDIO_OUTPUT` | Capturing the GSM call audio stream for RTP encoding |
| `MODIFY_PHONE_STATE` | Programmatic call answer/hangup/end-on-crash |
| `READ_PRIVILEGED_PHONE_STATE` | Subscription, SIM slot and ICC info |
| `READ_PRECISE_PHONE_STATE` | Detailed call state transitions |
| `MANAGE_ONGOING_CALLS` | Take over the in-call audio path for the bridged leg |
| `MANAGE_SENSOR_PRIVACY` | Drive the system-wide mic kill switch (Android 12+) |

Without these, the audio bridge cannot route between modem and RTP. If you only need SMS, skip Magisk entirely.

## Installation

1. **Build the Magisk module**
   ```sh
   cd magisk
   ./build.sh
   ```
   Output: `magisk/copperhead-gateway-magisk.zip`.

2. **Flash via Magisk Manager** → *Modules → Install from storage* → pick the zip → reboot.

3. **Build and install the APK**
   ```sh
   ./gradlew assembleDebug
   adb install app/build/outputs/apk/debug/app-debug.apk
   ```

4. **Open the app.** The onboarding wizard walks through permissions, battery optimization, notification access, and root verification.

5. **Configure your SIP account** — server, username, password, port, optional outbound proxy. Pick a SIM slot or leave on "any".

6. **Tap Start Gateway.** A persistent notification shows registration status. The service survives app closure and auto-starts at boot.

## Asterisk configuration

### pjsip.conf

```ini
[copperhead]
type=endpoint
transport=transport-udp
context=from-gsm-gateway
disallow=all
allow=g722,ulaw,alaw
auth=copperhead-auth
aors=copperhead

[copperhead-auth]
type=auth
auth_type=userpass
username=copperhead
password=your-password

[copperhead-aor]
type=aor
max_contacts=1
remove_existing=yes
```

### extensions.conf

```ini
; Outbound: dial any number through the cellular gateway
[outbound-gsm]
exten => _X.,1,Dial(PJSIP/${EXTEN}@copperhead)

; Inbound: cellular calls arrive on the gateway and get routed
[from-gsm-gateway]
exten => _X.,1,NoOp(GSM call from ${CALLERID(num)})
 same => n,Dial(PJSIP/100,30)  ; ring extension 100 for 30 s
 same => n,Voicemail(100@default,u)
 same => n,Hangup()
```

### SMS via dialplan

Incoming SMS arrives as a SIP `MESSAGE`. Handle it with Asterisk's `message()` and `MessageSend()`:

```ini
[from-gsm-gateway]
exten => _X.,1,NoOp(SMS from ${MESSAGE(from)} : ${MESSAGE(body)})
 same => n,MessageSend(pjsip:100,${MESSAGE(from)})
 same => n,Hangup()
```

## FreePBX configuration

1. **Applications → Extensions → Add Extension → Chan PJSIP Extension**
2. **User Extension:** pick a number (e.g. `999`)
3. **Display Name:** `Copperhead Gateway`
4. **Secret:** the password you'll enter in the Copperhead app
5. Under **Advanced → Codecs**, set order to `g722, ulaw, alaw`
6. **Connectivity → Trunks → Add Trunk → Chan PJSIP** pointing at the same extension (or use the extension directly via `Local/<ext>@from-internal`)
7. **Connectivity → Outbound Routes** matching the patterns you want sent over GSM (e.g. `_1NXXNXXXXXX` for US 11-digit), trunk = the GSM trunk you just made
8. **Connectivity → Inbound Routes** — point the cellular DID at whatever destination should ring (ring group, IVR, queue, extension)
9. In Copperhead: SIP server = your FreePBX IP, username/password = the extension number / secret, "Forward to extension" = whoever should receive inbound GSM calls (e.g. an extension, a ring group ID like `600`, or `*43` for echo test if you're debugging)

That's the full happy path — no edits to chan_pjsip.conf or extensions.conf required.

## Networking

### Outbound proxy / SBC

If the SIP server isn't directly reachable (NAT, firewall, an SBC in front), set the **Outbound Proxy** field to `host:port`. All SIP packets get routed through it without altering the `Contact` / `Via` headers, which is the right behavior for most SBC setups.

### Tailscale / WireGuard (recommended for remote PBX)

Install [Tailscale](https://tailscale.com) on both the phone and the PBX. Each gets a stable `100.x.x.x` IP on the mesh VPN — no port forwarding, no STUN, no public SIP exposure. Set the SIP server field to the PBX's Tailscale address. This is the simplest remote setup.

WireGuard works identically — Copperhead doesn't care about the underlying transport, only that the SIP server's IP is reachable.

### Local IP display

When running, the status card shows the phone's local IP and SIP port (e.g. `192.168.1.42:58501`). Send SIP `MESSAGE` requests directly to that address for testing.

## SIP protocol support

The built-in SIP stack is intentionally small and covers exactly what GSM gateway operation needs.

| Layer | Supported |
|---|---|
| Methods (UAC) | REGISTER, INVITE, ACK, BYE, CANCEL, MESSAGE, OPTIONS |
| Methods (UAS) | INVITE, ACK, BYE, CANCEL, MESSAGE, OPTIONS |
| Auth | Digest MD5, `qop=auth`, both 401 and 407 challenges |
| Audio | G.722 (16 kHz wideband), G.711 µ-law / A-law (8 kHz), 20 ms ptime |
| DTMF | RFC 2833 telephone-event |
| Caller-ID | P-Asserted-Identity (RFC 3325), Remote-Party-ID (Cisco) |
| Transport | UDP |
| NAT | `rport`, outbound proxy mode |
| Re-auth | Per-request CSeq increment, dialog-correct ACK CSeq |

The G.722 codec is the **Asterisk / spandsp public-domain reference implementation by Steve Underwood**, ITU test-vector conformant, compiled as a small JNI library via the NDK.

## Privacy and crash safety

Cellular gateways have an unusual threat model: the device's microphone is physically connected to a modem that has its own audio path. If the bridging app dies mid-call, the modem keeps that audio path open and the remote caller hears the room. Copperhead has five layers of defence:

1. **Mic mute before answer** — `AudioManager.isMicrophoneMute = true` and `MODE_IN_CALL` are set *before* the cellular leg is answered, closing the room-audio window that would otherwise exist while SIP negotiates.
2. **JVM uncaught-exception handler** — any Kotlin/Java crash on any thread hangs up the cellular leg before the process dies.
3. **Guardian sidecar process** — a separate `:guardian` process holds an `IBinder.linkToDeath` recipient on a Binder owned by the main process. When the main process dies for *any* reason (JVM exception, native SIGSEGV in the JNI codec, SIGKILL by the OOM-killer, swipe-from-recents), the kernel binder driver fires the death notification and the guardian calls `TelecomManager.endCall()` to terminate the cellular leg.
4. **Orphan-call cleanup on restart** — bridged calls are tagged with a Telecom extra. On every Copperhead start, `InCallService.onCallAdded` checks for already-active calls bearing that tag; any found are immediately disconnected as orphans from a dead previous instance.
5. **System-wide mic kill switch** (opt-in) — a Settings toggle drives `SensorPrivacyManager.setSensorPrivacy(MICROPHONE, true)`, the same state controlled by the Quick Settings "Mic access" tile. When engaged, *every* mic capture on the device returns silence — including cellular calls, voice assistants, and recorders — and on Pixel 7+ devices the audio codec physically decouples the mic capsule from the audio bus. State persists across reboots, Copperhead crashes, and Force-stop. The user can still toggle it off via Quick Settings (intentional: this is a defence against background software, not against the person holding the phone). Emergency calls (911 / 112 / 110 / 119) bypass it on stock AOSP. Requires Android 12+ and the `MANAGE_SENSOR_PRIVACY` priv-app permission.

Together layers 1–4 cover everything except **user-initiated Force Stop in Settings** — Android explicitly marks the package as `STOPPED` after Force Stop, which suppresses auto-restart, alarms, and Telecom re-binding by design. Layer 5 is the only mechanism that survives Force Stop too (because it's owned by `SensorPrivacyService` in `system_server`, not by Copperhead's process), but it's a manual switch the user has to engage before the threat scenario, not an automatic per-call mute.

## Project structure

```
app/src/main/
├── kotlin/com/copperhead/gateway/
│   ├── sip/
│   │   ├── SipEngine.kt       SIP UDP transport, registration, dialog handling
│   │   ├── SipMessage.kt      Parser and builder
│   │   ├── SipAuth.kt         RFC 2617 digest
│   │   ├── SipCall.kt         Call session state machine
│   │   ├── SipConfig.kt       Account configuration
│   │   ├── RtpStream.kt       RTP encode/decode, jitter handling
│   │   └── G722Codec.kt       JNI binding to native G.722
│   ├── gsm/
│   │   ├── GsmCallService.kt  Android InCallService
│   │   └── GsmCallManager.kt  Telecom Call tracking, dual-SIM selection
│   ├── sms/
│   │   ├── SmsReceiver.kt     SMS_RECEIVED broadcast handler
│   │   └── SmsHandler.kt      Send / multi-part SMS via SmsManager
│   ├── bridge/
│   │   ├── CallBridge.kt      Bidirectional voice bridging state machine
│   │   └── SmsBridge.kt       SIP MESSAGE ↔ SMS
│   ├── audio/
│   │   └── AudioBridge.kt     PCM capture/playback, mic guard, jitter buffer
│   ├── guardian/
│   │   ├── GuardianService.kt Sidecar process for linkToDeath death-pact
│   │   └── IGuardian.aidl     Cross-process IPC contract
│   ├── service/
│   │   ├── GatewayService.kt  Foreground service lifecycle, crash handler
│   │   └── BootReceiver.kt    Auto-start on boot
│   └── util/
│       └── Preferences.kt     SIP account + settings storage
└── cpp/
    ├── g722_encode.c          Reference G.722 encoder
    ├── g722_decode.c          Reference G.722 decoder
    └── g722_jni.c             JNI glue

magisk/
├── module.prop                Module metadata
├── install.sh                 Magisk install script
├── system/etc/permissions/    Priv-app permission allowlist
└── build.sh                   Builds the flashable zip
```

## Building

### Debug APK

```sh
./gradlew assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`

### Release APK (configure signing in `app/build.gradle.kts` first)

```sh
./gradlew assembleRelease
```

### Magisk module

```sh
cd magisk && ./build.sh
```

Output: `magisk/copperhead-gateway-magisk.zip`

## Debugging

The app's log panel shows SIP registration, call events, RTP rate, jitter buffer depth, and audio routing decisions in real time. For packet-level debugging, run `ngrep` on the PBX:

```sh
ngrep -W byline -d any port 5060
```

For full SIP message dumps from Copperhead itself, the `SipEngine.WIRE_TRACE` flag emits every send/receive verbatim into the log under `[SIP-TX]` / `[SIP-RX]` markers.

## Project status

**Copperhead is feature-complete and unmaintained.** It does what this README describes; it is published under ISC for anyone who finds it useful.

- **No issues will be triaged.** If you hit a bug, fix it in your fork.
- **No pull requests will be reviewed or merged.** The repository is published as-is.
- **No discussions, no roadmap, no support.** Use the source.
- **Forks are encouraged.** Take this codebase, rename it, ship it, sell it — ISC allows all of that. If you build something useful on top of it, link back so others can find your fork.

Why this stance: the author is one person who built Copperhead for personal use, polished it for release, and is moving on. Maintaining a public OSS project sustainably is a separate full-time hobby — that's not happening here. Publishing the code is meant as a *gift*, not a *promise*.

## License

[ISC](LICENSE). Use it for anything — personal, commercial, embedded, repackaged. No warranty of any kind. The code is provided as-is.

## Related projects and prior art

- [chenxiaolong/BCP](https://github.com/chenxiaolong/BCP) — **the source of the Pixel uplink-injection technique Copperhead depends on.** BCP is a tech-demo that plays an audio file to the other party during a call by writing to the `TYPE_TELEPHONY` output device on rooted Pixels with `MODIFY_PHONE_STATE`. Copperhead does the same thing with SIP RTP audio instead of a local file — but BCP's proof-of-concept is what made the cellular-uplink leg of this gateway possible. GPLv3 licensed, well worth reading.
- [telon-org/react-native-gsm-sip-gateway](https://github.com/telon-org/react-native-gsm-sip-gateway) — React Native implementation of the same overall concept. Copperhead is an independent ground-up rewrite in native Kotlin with dual-SIM, SMS, a self-contained SIP stack, wideband audio, and crash-safety hardening; not a port.
- [topjohnwu/Magisk](https://github.com/topjohnwu/Magisk) — the root solution Copperhead depends on for priv-app installation.

---

**Keywords for search:** Android GSM gateway, Android SIP gateway, Asterisk GSM trunk, FreePBX cellular trunk, FreePBX GSM gateway, dual-SIM SIP bridge, open-source GoIP alternative, Dinstar alternative, Yeastar TG alternative, OpenVox alternative, cellular trunk for VoIP, self-hosted GSM gateway, Android Telecom InCallService SIP, GSM-SIP bridge with SMS, Wifi calling alternative, Tailscale SIP, Asterisk Wifi calling, FreeSWITCH cellular gateway, Kamailio GSM bridge, Magisk Android telephony app, AT command alternative GSM, prepaid SIM Asterisk trunk, Kotlin SIP stack, G.722 Android.
