# OCD Check

OCD Check is an Android app that reads a phone's SIM, network and carrier settings and compares them with [Open Carrier Data](https://github.com/open-carrier-data/open-carrier-data) (OCD). It is read-only. It changes nothing on the phone, and it has no internet permission, so it cannot send anything.

**Status: early and unpublished.** This is version 0.2.0: stage 2 of the plan (view only, normal app) plus stage 2b (the optional read-only Shizuku deep check), since OCD's round 17 the device records, and since rounds 18, 19 and 22 the IMS reader for Android's own IMS (stage 2c), the guided call test (stage 2d, outgoing call) and the hint for a `supported` summary next to `off` device records. It first ran on a phone on 2026-10-13 (Galaxy A50, Android 14, LIDL Connect SIM), and it is not on any store. The JVM tests pass and the debug build assembles. Only the owner's builds use it. That makes it a named consumer of OCD's data, not a public release. Its first public release fires OCD's trigger T1, and so does a public ROM that bundles it.

## What it does

1. **Reads the phone**, through public Android calls only:
   - **Your SIM:** network code (MCC/MNC), SIM name (SPN), Android carrier ID and specific carrier ID with their names, and GID1 if the permission is granted.
   - **Network now:** serving network code, roaming, the data and voice network types, and three 5G hints: standalone (SA) in use, the 5G icon (`TelephonyDisplayInfo`), and whether the phone reports a 5G signal.
   - **Carrier settings on this phone:** six CarrierConfig keys and nothing else: `carrier_volte_available_bool`, `carrier_wfc_ims_available_bool`, `carrier_vt_available_bool`, `vonr_enabled_bool`, `enabledMMS` and `carrier_nr_availabilities_int_array`. It also reads whether carrier-specific settings were applied at all.
   - **Mobile data connections:** the APN name and the APN types of each mobile data connection Android shows to apps.
2. **Looks the SIM up in OCD's data**, which ships inside the app. It shows the matching profiles, general to specific, and the most specific profile's value for every capability. Each value comes with OCD's own plain meaning from its README, "Limits to read before you ship". For VoLTE, Wi-Fi calling, VoNR and video calling, OCD's value is a summary over every phone model's carrier table; where OCD has a device record for this phone model, the app shows it next to that summary: "this phone model's own file says on" (see "Device records" below).
3. **Writes findings in plain language.** A finding compares a phone reading with an OCD value, for example "OCD's answer is supported; this phone's carrier settings have it off". A finding never says a feature works, that OCD is wrong, or that a carrier does not support something. Whether the phone is signed in for VoLTE or Wi-Fi calling is hidden from normal apps. The app says this needs the deep check and does not guess.
4. **Builds a report** you can read, then copy or share through Android's share sheet. The full text is on screen before Copy and Share appear.
5. **Optionally runs a deep check through Shizuku** (see below): whether the phone is signed in for VoLTE or Wi-Fi calling, on Samsung's IMS or on Android's own IMS (LineageOS and other AOSP-based builds), over which network, for which services, and the last sign-out reason; whether the network offers voice over IMS (VoPS); the Wi-Fi calling switch; whether the carrier's Wi-Fi calling server name resolves, in a lookup by the shell and in the phone's own lookup; whether Wi-Fi calling is held back after a failed attempt; and the phone's APN list. Without Shizuku the app works exactly as above.
6. **Offers a guided call test** with the deep check (see below): you call your SIM's own mailbox yourself, and the app records which network the call used (4G, 5G or Wi-Fi) and whether you heard the mailbox. The results stay in the app on this phone.

## Permissions, and why

| Permission | Kind | Used for |
|---|---|---|
| `READ_PHONE_STATE` | asked at run time, with an explanation first | GID1, the carrier settings, the data and voice network type, and the list of active SIMs. Android's dialog calls this "make and manage phone calls". The app makes no calls, and this permission shows it none; only the optional guided call test reads, through Shizuku, the state and network type of a call you place yourself. |
| `ACCESS_NETWORK_STATE` | normal | The APN name and types of the mobile data connections |

That is the whole list:
- **No `INTERNET`.** The app cannot send anything. A report leaves the phone only when you copy it or pick a target in the share sheet.
- **No other access:** no location, SMS, call log or contacts permission, no accessibility service and no `QUERY_ALL_PACKAGES`.
- **No third-party services:** no analytics, no Google Play services and no Firebase.

Without `READ_PHONE_STATE` the app still shows the SIM's network code, name and carrier IDs, and OCD's values for them.

Compose pulls in `androidx.emoji2`. Its startup step would look up an emoji font from a system font provider, which on most phones is Google Play services. The manifest removes that step.

The remaining startup entries come from androidx: `ProcessLifecycleInitializer`, and the profile installer with its receiver. That receiver is guarded by `android.permission.DUMP`, so only the system or adb can call it. It is not a permission the app requests.

The deep check adds three manifest entries, none of them a permission the app holds:
- **Shizuku's provider** (`rikka.shizuku.ShizukuProvider`). Shizuku hands its binder to the app through it. It is exported, as Shizuku requires, and guarded by `INTERACT_ACROSS_USERS_FULL`, so only the system and Shizuku's own server can call it.
- **A `<queries>` entry** for `moe.shizuku.privileged.api`, only to show whether the Shizuku app is installed.
- **Shizuku's own permission name.** Shizuku's provider library asks for `moe.shizuku.manager.permission.API_V23`. The Shizuku app defines it, not Android, and it grants nothing by itself; access comes only from Shizuku's own dialog. The Shizuku server finds client apps by this requested permission and sends them its binder, so the app keeps it. An earlier build removed it, and on the A50 the deep check then never saw Shizuku running.

To check a build yourself:

```bash
$ANDROID_HOME/build-tools/34.0.0/aapt2 dump permissions app/build/outputs/apk/debug/app-debug.apk
```

With the Shizuku libraries in, this prints `READ_PHONE_STATE`, `ACCESS_NETWORK_STATE` and Shizuku's `API_V23`, and nothing else. `ManifestTest` checks the same on the merged manifest of every build.

## What it never collects

The app never reads, stores, shows or reports the following. Most of them are also closed to normal apps by Android.
- IMSI, ICCID or their prefixes
- IMEI, TAC, serial number or Android ID
- any phone number
- location of any kind: GPS, cell IDs, tracking area, PCI/ARFCN, Wi-Fi names, IP addresses
- IMS or SIP identities
- the build fingerprint, build user or build host, or the baseband string
- APN usernames or passwords
- raw dumps or logs
- anything emergency-related, apart from two yes-or-no checks of the guided call test: whether Android calls the mailbox number an emergency number, and whether an emergency call started during the watch

An emergency data connection is skipped, and the app never dials. The guided call test shows only a short mailbox code from the app's own list, never an emergency or service number.

## What it never does

OCD's rule decisions of 2026-10-18 (round 18) allow passive checks only: with the phone's current settings, when you open the app or tap a check, shown on the phone. So the app never does the following:
- write a setting, a CarrierConfig override or an APN row;
- force or reset IMS;
- try another setting to see whether it works;
- switch the radio, Wi-Fi or airplane mode;
- place a call, or bring up an MMS connection;
- talk to the carrier's Wi-Fi calling server (ePDG). The deep check only looks up the server's name, and that lookup may send one ping;
- send anything by itself, or run a check in the background. There is no telemetry and no server, and it opens no issue for you. The one check that goes on while the app is not in front is the guided call test's watch: you start it, it reads the call state while you are in the phone app, and it ends when you tap Stop, after 15 minutes, or when the app closes;
- download data. OCD's data changes only with a new app release.

Its results never become, withhold or rank an OCD value. A report can only start a review of OCD's sources. A ROM may bundle the app read-only; such a build may add only `READ_PRECISE_PHONE_STATE`.

The owner's OCD Lab is a separate tool on a PC, in OCD's private repository (`tools/ocd_lab/`). It applies temporary CarrierConfig test overrides over ADB as root. It is not part of this app, and this app does none of it.

## What a normal app cannot see

Android hides these from normal apps:
- whether the phone is signed in for VoLTE, Wi-Fi calling or SMS over IMS (IMS registration), and its errors;
- the phone's APN list;
- whether the Wi-Fi network's DNS resolves the carrier's Wi-Fi calling server (ePDG). A normal app would need the internet permission for that;
- the network a call actually uses;
- GID2, and OCD profiles that match on ICCID or IMSI. The app lists these as "profiles this app cannot check".

The deep check below reads the IMS registration (on Samsung's IMS and on Android's own), the last sign-out reason on Android's IMS, the APN list and the ePDG name; the guided call test reads the network a call uses. Samsung's IMS errors, GID2 and the ICCID or IMSI matches stay out of reach.

## The deep check (Shizuku)

**Why it exists.** The question people ask most, "is my phone signed in for VoLTE or Wi-Fi calling?", is closed to normal apps. On stock Samsung phones only the shell user (adb) or root can read it. [Shizuku](https://shizuku.rikka.app/) lets an app run code as the shell user after the owner starts it through wireless debugging. The deep check uses that to repeat, on the phone, the read-only adb check the owner ran by hand on a Galaxy A50 (`tools/phone_check.py` in OCD's private repository). That check found the A50's Wi-Fi calling failure: the home network's DNS did not resolve the carrier's ePDG name.

**It is optional.** Without Shizuku, or without your OK in Shizuku's dialog, nothing in it runs, and the report says `"access_tier": "normal"`. The app asks Shizuku for access only when you tap "Allow in Shizuku", and runs the check only when you tap "Run deep check". With results, the report says `"access_tier": "shizuku"`.

**It is read-only.** The whole allowlist is eight commands, a closed Kotlin type (`core/DeepCheck.kt`, `ShellCommand`). Each builds its arguments from constants and two validated values: the SIM slot (0 to 3) and the home network code (5 or 6 ASCII digits). Nothing else can reach a process. For a 26202 SIM in the second slot (index 1), the commands are:

| Command | What it reads | What the app keeps |
|---|---|---|
| `dumpsys secims` | Samsung's IMS state | for this slot: signed in or not, Samsung's state word, the network of the registration (`RAT = 13` LTE, `18` Wi-Fi, `20` 5G), the services (calls, SMS and other known names), Samsung's carrier table name (MNO, for example `Vodafone_DE`) |
| `settings get system wifi_call_enable2` | Samsung's Wi-Fi calling switch for the slot (run only when Samsung's IMS was found) | on, off or not set |
| `ping -c1 -W2 epdg.epc.mnc002.mcc262.pub.3gppnetwork.org` | whether the carrier's standard ePDG name resolves | resolves, does not resolve or not known. Only the first output line is read |
| `content query --uri content://telephony/carriers --projection apn:type:protocol:roaming_protocol:carrier_enabled:mvno_type:edited --where numeric='26202'` | the APN rows for the home network | the listed columns, up to 40 rows |
| `dumpsys activity service com.android.phone` | Android telephony's own IMS state (run only when Samsung's IMS is not found) | for this slot's phone: signed in or not, Android's state word (`mImsMmTelRegistrationState`), the transport of the registration log's last sign-in, the services (calls, video calls, SMS), the code and name of the last sign-out reason (`ImsReasonInfo`, never its message), and the phone's own Wi-Fi calling switch (`isWfcEnabledByUser`) |
| `dumpsys telephony.registry` | the registry's last known state of this slot's phone | voice over IMS offered on LTE and on 5G (VoPS), and the transports on which the IMS data connection is connected, with LTE or 5G told apart. The guided call test reads the call state, the foreground call state, `mCallNetworkType` and whether `mOutgoingCallEmergencyNumber` changed |
| `logcat -d -v epoch -b main -b radio -s EpdgSelector:V IwlanDataService[1]:V EpdgTunnelManager[1]:V` | the lines of Android's IWLAN service in the log, every other tag silenced; `-d` only dumps (run only on Android's IMS) | the phone's own ePDG lookup (whether it asked for the standard name, whether it got an address, how long ago), whether the Wi-Fi calling tunnel is up, the error type of the last refusal, and whether a network back-off timer or Android's retry wait holds Wi-Fi calling back, and for how long |
| `dumpsys connectivity` | the connected Wi-Fi's DNS servers | only their class: in the local network (usually the router), outside it, mixed, none, or not on Wi-Fi |

How the output is handled:
- **Parsed where it is read.** Shizuku starts the app's `DeepCheckService` as the shell user (or root) in a process of its own. That process runs the commands and parses their output line by line. Each line is dropped once it has been looked at.
- **Only typed fields reach the app.** The result is enums, booleans, numbers and short strings checked for shape. IMSI, ICCID, IMEI, phone numbers, SIP URIs and IP addresses have no field to land in; an MNO or APN name with a run of 9 or more digits is dropped. The registry and the phone dump hold phone numbers, cell identities and the emergency number list, and the log holds server addresses; the parsers read none of them into a field. The app decodes the result strictly, so an unknown key or a wrong shape fails.
- **The APN query never asks for credentials.** It reads no user name, password, server, proxy, port or MMSC column, and not the MVNO match value (an SPN, GID or IMSI pattern). Emergency and deleted rows are left out.
- **The ePDG lookup touches the network.** It sends one DNS query through the phone's default network. If the name resolves, the command may send one ping to the carrier's server. The app itself still has no internet permission; the shell user does the lookup. The app records whether the default network was Wi-Fi and whether Private DNS was on, with `ACCESS_NETWORK_STATE`. The lookup is IPv4 only, as in `phone_check.py`, and a carrier may use another server name, so a failure is a strong hint, not proof.

The app never sends a write command. Shizuku's grant could write (`settings put`, `cmd phone`, `content insert`), so tests guard the code (`AllowlistTest`):
- every command in the allowlist, for every slot, is checked against a list of write verbs and shell characters; `dumpsys` may name only the four services above, and `logcat` must be exactly the dump above, never with an option that clears, resizes, rotates or writes a file;
- only `DeepCheckService.kt` may start a process, and only from a `ShellCommand`;
- the app may call only Shizuku's status, permission and user-service methods, never its binder wrapper, remote process or `exit`.

**Android's own IMS (round 18).** On a phone without Samsung's IMS, such as the S20 on LineageOS with PHH IMS, the deep check reads Android telephony's state. The parsers are ports of OCD Lab's (`tools/ocd_lab/lab_parsers.py` in OCD's private repository: `parse_aosp_ims`, `ims_with_pdn`, `parse_registry_phone`, `parse_iwlan_log` with `iwlan_summary`, and `wifi_dns_servers`), and the lab's fixtures from the S20 are their test inputs. The transport comes from where the IMS data connection is, not only from the IMS registration log. On 2026-10-19 the lab found the S20's log still saying Wi-Fi while the IMS data connection had moved to LTE half an hour earlier. When the data connection is connected on exactly one transport, that transport counts, and the log's is kept beside it (`registration_log_transport`) when the two differ.

**Findings.** The deep check adds findings in the same plain style: VoLTE, VoNR or Wi-Fi calling signed in, with a note when the registration log names another network; not signed in for calls over 4G, with possible causes where OCD's tables turn VoLTE on, or with the cell's own statement when it does not offer voice over IMS (VoPS); Wi-Fi calling switched on but not signed in. When the ePDG name does not resolve, in the shell's lookup or in the phone's own, the finding names the A50 fix: Private DNS such as `dns.google`, or fixing the router's DNS; with a resolver in the local network it says the router passes the lookup on. When a back-off holds Wi-Fi calling, a finding says by what and for about how long. "Signed in" means the phone registered with the carrier's IMS; it never says a call connects.

**Limits.**
- **The log rotates.** The main log buffer on the S20 holds about 256 KiB, a few minutes on a busy phone. The phone's own ePDG lookup shows only when it is still in the buffer; otherwise the app says "not in the log". PHH IMS's own Wi-Fi calling pause is logged under another tag and is not read.
- **Samsung's IMS errors and back-off stay out of reach.** Samsung's ePDG client does not use AOSP's IWLAN service.
- **One ePDG name.** The shell's lookup is IPv4 only and asks for the standard name; a carrier may use another.
- **Shizuku 11 or newer.** On a phone without root, Shizuku must be started again after every reboot.

## The guided call test (round 19)

OCD's round 19 lists "a guided call test in the app: read-only, allowlisted, observing the call type" as its third step. The app has the outgoing half. It needs the deep check, because the network a call uses is hidden from normal apps; `READ_PRECISE_PHONE_STATE`, which would show it, is for system apps only.

**What you do.**
1. The app shows your SIM's own mailbox number, for example 5500 for LIDL Connect. Tap Start.
2. Open your phone app and dial that number yourself. Listen until the mailbox answers, then hang up.
3. Come back, tap Stop, and say whether you heard the mailbox: yes, no, or not sure.

For Wi-Fi calling, round 19 counts a test only in airplane mode with Wi-Fi on. Set that yourself before you start; the app changes no setting. A Wi-Fi call made with airplane mode off is kept with a note saying so.

**Which number.** Only the mailbox numbers in `app/src/main/assets/call-targets.json`, a byte-identical copy of OCD Lab's `tools/ocd_lab/call_targets.json`. The SIM's name rule comes first, then its network code (lab `mailbox_for`). Before a number is shown, it must be a plain 3 to 6 digit short code. It must not be on a fixed list of emergency and public-service numbers (112, 110, 911, 116xxx and others) or start with 11, 19 or 0. Android must also not call it an emergency number (`TelephonyManager.isEmergencyNumber`, exact match, which needs no permission). If any check fails, or Android cannot answer, the app shows no number and offers no test. It never falls back to another number. The app never dials, answers or ends a call, and it opens no dialer.

**What it watches.** Start begins a watch in the deep check's process, separate from the app's. Once a second it runs `dumpsys telephony.registry` and keeps, for this slot, only the call state (idle, ringing, off-hook), the foreground call state (dialing, alerting, active and so on) and `mCallNetworkType`: 13 is LTE (VoLTE), 18 IWLAN (Wi-Fi calling), 20 NR (VoNR), and a 2G or 3G type is a call outside IMS. These are TelephonyManager's network types, as in OCD Lab's `call_type_reading`. The watch also notes whether `mOutgoingCallEmergencyNumber` changed. It runs in Shizuku's process so that it goes on while the app is in the background, and it stops at Stop, after 15 minutes, or when the app closes. It keeps counts and states only, never a sample list, a number or a time.

**What it records.** The direction: an outgoing call shows dialing or alerting, or goes off-hook from idle without ringing. It also records whether the call went active, the call network type (the one read while active, when there is one), how many calls the watch saw, and your answer. If an emergency call shows during the watch, the app keeps nothing from it and says so.

**Where results go.** A kept test goes into the app's private storage on this phone (`call-tests.json`, at most 50; the app has no backup and no internet permission). It holds the date, the phone model, code name, software family, Android version and IMS stack, the SIM's network code and name, the OCD profile that answered, the mailbox brand and short code, and the measurement. It is not in the report. Round 19 keeps such results apart as a tested status that never becomes an OCD value, and nothing here uploads. "Delete these results" removes the file. The screen says what was measured, for example "An outgoing call, call network type 13 (LTE): VoLTE (call over 4G), went active; you heard the mailbox." It never says a feature works: round 19's "seen working" also needs an incoming call, repeats and other people's reports.

## The report

The report is JSON with a fixed set of keys. `app.access_tier` is `normal`, or `shizuku` when the deep check's results are in. Its rules:
- **Content:** the SIM's network code, name and carrier IDs, GID1 only if you switch it on, the phone model and its code name (`Build.DEVICE`, which LineageOS uses to name device trees), the Android major version, the software family (`stock`, `lineageos` or `other`), the check results, and the app and data versions.
- **Findings** go in as codes, not sentences.
- **Device records** for this phone model go in under `ocd.device_records`, per capability, as `state`, `source`, `stack`, `newest_entry` and `this_stack` (whether the record holds for the software the phone runs).
- **Carrier settings** go in only as a per-key comparison with OCD's answer (`matches_ocd`), never as values.
- **Deep check results** go in only as typed fields. `ims_registration` is `needs_deep_check` without them. With them, it holds `source` (`samsung_ims`, `aosp_telephony`, `not_found` or `unreadable`), `registered`, `state`, `transport`, `services`, and on Samsung `mno`. On Android's IMS it adds `ims_pdn`, `registration_log_transport` (only when the log differs), `last_disconnect_code` and `last_disconnect_reason`. More blocks join it:
  - `network_offer`: `lte_vops` and `nr_vops`;
  - `wifi_calling`: `switch`, `epdg_name`, `lookup_network`, `private_dns_active`, `wifi_resolver`; on Android's IMS also `phone_lookup` (`resolved`, `no_address`, `other_name` or `not_in_log`), `phone_lookup_age` (`under_1h`, `under_1d` or `older`, never a time), `tunnel`, `last_refusal`, `held_back_by` and `held_back_minutes`;
  - `apn_list`: `readable`, `rows` with `apn`, `types`, `protocol`, `roaming_protocol`, `carrier_enabled`, `mvno_type` and `edited`, and `left_out`.
- **Guided call tests** are not in the report.
- **Dates:** the report has the check date, never a time.

Before Copy or Share is offered, every key and string goes through a fail-closed privacy scan (`core/PrivacyScan.kt`). It ports these patterns from OCD's private tools:
- `BLOCK_PATTERNS` from `sanitize_profiles.py`;
- `EXTRA_VALUE_PATTERNS` and the key scan from `field_observations.py`;
- `BLOCKED_RUNTIME_KEYS` from `import_android_runtime_snapshot.py`;
- the SIP/tel URI, IPv4 and IPv6 shapes of `phone_check.py`.

The scan also rejects any key that is not on the allowlist. If anything hits, Copy and Share are switched off, and the screen names the field and the rule but never the matched text. For example, a GID1 made only of digits looks like a long number and blocks the report until you switch GID1 off.

Example, built by the tests from real OCD profiles (a LIDL Connect SIM in a Galaxy A50 on stock software):

```json
{
    "schema": "ocd-check-report.v1",
    "app": { "name": "OCD Check", "version": "0.2.0", "access_tier": "normal" },
    "ocd_data": {
        "source_commit": "69c56e1f087073d2fa4c254d47cfacf0d736c4d4",
        "source_commit_date": "2026-10-10",
        "checks_through": "2026-07-14",
        "stale_after": "2027-01-10"
    },
    "checked_on": "2026-10-10",
    "phone_writes_performed": "none",
    "device": { "model": "SM-A505F", "codename": "a50", "android_major": 14, "rom_family": "stock" },
    "sim": {
        "home_mccmnc": "26202", "spn": "LIDL Connect",
        "android_carrier_id": 2397, "android_carrier_name": "Vodafone",
        "specific_carrier_id": 2397, "specific_carrier_name": "Vodafone",
        "gid1_status": "empty"
    },
    "network": {
        "serving_mccmnc": "26202", "roaming": false, "data_network": "lte", "voice_network": "lte",
        "five_g": "none", "five_g_icon": "none", "nr_signal_reported": false
    },
    "carrier_settings": {
        "readable": true, "identified_carrier": true,
        "keys": {
            "carrier_volte_available_bool": { "ocd_capability": "volte", "ocd_value": "conditional", "matches_ocd": null },
            "carrier_wfc_ims_available_bool": { "ocd_capability": "vowifi", "ocd_value": "supported", "matches_ocd": false },
            "...": "three more keys"
        }
    },
    "data_connections": [ { "apn": "web.vodafone.de", "serves": ["internet", "supl", "xcap"], "set_up_over": "lte" } ],
    "ims_registration": "needs_deep_check",
    "ocd": {
        "profiles": [ { "profile_id": "open.26202.8cebd7550c67", "display_name": "Vodafone", "match_keys": ["mccmnc"], "newest_entry": "2026-09" } ],
        "answer_from": "open.26202.8cebd7550c67",
        "capabilities": { "volte": "conditional", "vowifi": "supported", "vonr": "supported", "...": "seven more" },
        "not_checkable": [ { "profile_id": "open.26202.db9b35c48658", "needs": ["iccid"] } ]
    },
    "findings": [
        "spn_not_in_ocd", "hidden_selectors", "phone_off_ocd_conditional:volte",
        "phone_off_ocd_supported:vowifi", "phone_off_ocd_supported:vonr", "samsung_own_ims", "needs_deep_check"
    ]
}
```

With the deep check, the same SIM before the A50's DNS fix gives (abridged):

```json
{
    "app": { "name": "OCD Check", "version": "0.2.0", "access_tier": "shizuku" },
    "ims_registration": {
        "source": "samsung_ims", "registered": true, "state": "registered",
        "transport": "lte", "services": ["mmtel", "smsip"], "mno": "Vodafone_DE"
    },
    "wifi_calling": { "switch": "on", "epdg_name": "does_not_resolve", "lookup_network": "wifi", "private_dns_active": false },
    "apn_list": {
        "readable": true,
        "rows": [
            { "apn": "web.vodafone.de", "types": ["default", "supl", "xcap"], "protocol": "IPV4V6", "roaming_protocol": "IPV4V6", "carrier_enabled": true, "edited": "no" },
            { "apn": "IMS", "types": ["ims"], "protocol": "IPV4V6", "roaming_protocol": "IPV4V6", "carrier_enabled": true, "edited": "no" }
        ],
        "left_out": 0
    },
    "findings": [ "...", "volte_signed_in", "wifi_calling_not_signed_in_epdg_dns" ]
}
```

The app does not open an issue on GitHub for you. Round 9 allows a prefilled `wrong-or-missing-carrier-data` form, but it ties that to two open owner actions (O2 and legal point L2), so this version leaves it out.

## OCD data in the app

`app/src/main/assets/ocd-lookup.json.gzip` (about 390 KB) is built from a checkout of OCD's public repository by `tools/build_bundle.py`. The script reads these files and changes none of them:

| File | What the bundle keeps |
|---|---|
| `generated/android/lookup.json` | per profile: `profile_id`, `display_name`, `match`, `capabilities`, `newest_entry` |
| `generated/android/metadata.json` | the snapshot's `checks_through`, `stale_after` and `data_digest` |
| `README.md`, "Limits to read before you ship" | the plain meanings of the four capability values and five limit bullets: gates, missing switches, no phone verification, tables not tests, MVNO merge; with device records also the bullet that says what they are |
| `generated/devices/carrier-variants.json`, when the snapshot has it | OCD's device records, whole, after checking that they were built from the same `data_digest` as `lookup.json` |
| `generated/devices/android.json`, with device records | for each catalog device the records name, the device names (`Build.DEVICE`) and models (`Build.MODEL`) that no other catalog device lists |

The bundle also records the source commit and its date. The app shows the data date and warns after `stale_after`.

OCD's planned monthly releases, `data-YYYY.MM`, will support `lookup.json`, `metadata.json` and `carrier-variants.json` for apps (OCD's `docs/releases.md`). No release exists yet; the first is planned as `data-2026.11`. Until then the bundle comes from a `main` commit, which it records.

The app shows the Limits text exactly as the data snapshot ships it. Nothing about an operator's services is hard-coded.

To refresh the asset or check it:

```bash
python3 tools/build_bundle.py --public ../live-public          # writes the asset
python3 tools/build_bundle.py --public ../live-public --check  # compares only
```

The script refuses a public checkout with uncommitted changes in those files. The output is deterministic: sorted keys and no time in the gzip header. The asset is named `.gzip`, not `.gz`, because the Android build unpacks `.gz` assets and drops the suffix.

**Matching** ports `profile_matches`, `specificity` and `resolve` from OCD's `tools/resolve_carrier_profiles.py`:
- profiles come generic to specific, ordered by specificity, then `profile_id`;
- SPN is compared ignoring case;
- GID1 is compared as a prefix, ignoring case;
- the answer is the most specific profile's value, as `docs/consume.md` says.

Two differences from the resolver:
- **Two carrier IDs.** A SIM has a carrier ID and a specific carrier ID, and a profile's `android_carrier_ids` match when either one is listed. Android's CarrierConfig app compares `cid` the same way (`DefaultCarrierConfigService`, case `"cid"`). The Python resolver takes one ID.
- **Unreadable dimensions are kept aside.** A profile that needs a value the app cannot read (ICCID, IMSI, GID2, or GID1 without the permission) does not simply fail to match. It is listed as "cannot check". The matched stack is the same as the resolver's for the same inputs.

**Device records** (OCD's round 17) say what one phone model's own carrier file sets for VoLTE, Wi-Fi calling, video calling and VoNR on one carrier profile. OCD lists every device value it publishes, including those that agree with the carrier value. The app reads them for the most specific profile:
- the phone's keys are `samsung:` and `Build.MODEL` on a Samsung phone, and the one catalog device whose unique device names list `Build.DEVICE` or whose unique models list `Build.MODEL`, compared case-folded; a name two catalog devices share matches nothing;
- a record whose stack matches the phone's software (`stock`, or `lineageos` on a LineageOS build) reads "This phone model's own file sets VoLTE on for this SIM (one source: Samsung's carrier pack, stock firmware, newest entry 2025-02). OCD's carrier summary is conditional. Configuration, not a test.";
- a record of another stack reads "This model's stock firmware file ...", and adds that the phone runs another software whose own file may differ; it never says "this phone";
- without a record, the summary stays labelled as the carrier's, and the screen says "OCD publishes no file of this phone model for this SIM (none read, older than five years, or not bound to one model)"; a missing record is never "off" and never agreement;
- records built from another data digest than the bundle's are not used.

The app writes nothing from a record into the phone. The findings codes are `device_record_<state>:<capability>` and `device_record_other_stack_<state>:<capability>`.

OCD's round 22 lets a VoLTE, Wi-Fi calling or video calling summary read `supported` where its single-family off and at least one on are device records. So a `supported` summary can sit next to `off` records. Where it does on the most specific profile, the app says so with the correctness ruling's sentence and the count, for example "11 phone models' own files turn Video calling off for this SIM (one maker's or maintainer's files; OCD does not count that as proof)." (`core/LabelHints.kt`):
- under the summary, in the device-records lines, whether this model has a record or not;
- in the `phone_off_ocd_supported:<capability>` finding; the code stays the same;
- in this model's own `device_record_*` finding. When this model's own file is one of the `off` records, it adds that the model's file comes before OCD's summary.

Only the three switches step 2 can label count, and only records built from the bundle's own data. `LabelHintTest` checks it on O2 DE (`26203`) video calling, as round 22 orders, with the records the public `carrier-variants.json` holds and the label set to `supported` as step 2 makes it. Since 2026-10-19 the bundled asset is from public `8cb64d63` (#280), the first publish with step-2 labels: O2 DE (`26203`) video calling now reads `supported` in the bundle, so the hint shows on a phone with an O2 SIM.

The bundled data is OCD's. The OCD contributors waive their own rights in it under CC0 1.0; see OCD's `DATA-LICENSE.md`.

## Planned

These steps follow OCD's rule decisions. Each stays read-only and adds no permission.
- **The rest of the guided call test** (round 19). The outgoing mailbox call is built (see above). Still to come: the incoming call, where someone calls you and both sides confirm they heard each other; the call state on the normal tier, without Shizuku; and, in debug builds, a ledger draft for the owner. A measured outgoing and incoming call with confirmed audio is the evidence OCD's tested layer would count.

Active verification, trying other settings on the phone, was rejected in round 18. Round 23, being judged on 2026-10-19, reconsiders it for the app. Until it rules, the app stays as described here.

## Build

You need:
- a JDK, 17 or newer (built here with Temurin 21);
- an Android SDK with platform `android-36` and build-tools `34.0.0`;
- the included Gradle wrapper (Gradle 8.14.3).

The deep check uses the Shizuku API library, `dev.rikka.shizuku:api` and `:provider` 13.1.5 from Maven Central (MIT licence). Shizuku itself is a separate app, not bundled.

```bash
export JAVA_HOME=/path/to/jdk
echo "sdk.dir=/path/to/android-sdk" > local.properties
./gradlew testDebugUnitTest assembleDebug
python3 -m unittest discover -s tools -p 'test_*.py'
```

The APK is `app/build/outputs/apk/debug/app-debug.apk`. CI (`.github/workflows/test.yml`) runs the unit tests, lint, the debug build and the bundle script's tests on every pull request.

**Version pins.** The project uses Android Gradle Plugin 8.7.3, the newest line that still builds with build-tools 34.0.0; AGP 8.8 and later need build-tools 35. `gradle.properties` silences AGP 8.7's warning about compileSdk 36, and `android.builder.sdkDownload=false` stops the build from fetching SDK packages on its own. The androidx versions match that AGP:
- Compose BOM 2025.06.00
- activity-compose 1.10.1
- lifecycle 2.9.1

To move to newer libraries, install build-tools 35 or later, raise AGP, and then raise the libraries.

**Tests.** JVM tests sit under `app/src/test`. They use a fixture of 17 real profiles from `lookup.json` (`app/src/test/resources/fixture-bundle.json`):
- `MatcherTest`: the 26202 base profile, LIDL Connect, the Vodafone.de SPN, the 1&1 GID1 profile, the Telekom stack from OCD's README example, Tracfone's specific carrier ID, and ICCID, IMSI and GID2 profiles;
- `FindingsTest`: the comparisons, no German MMS claim, no "works" or "OCD is wrong" wording, the deep-check finding, and the deep check's findings (the A50 DNS case, signed in over 4G or Wi-Fi, not signed in over 4G), and on Android's IMS the S20 with the LIDL SIM on 2026-10-19 (signed in over LTE while the log says Wi-Fi, the phone's own lookup empty, Android's retry wait), a cell without VoPS, and the wording rules;
- `ReportTest`: the key allowlist, the redaction and fail-closed scan, the GID1 switch, date only, both access tiers, a Shizuku report built from dumps full of identifiers, and a report from Android's IMS with the new fields;
- `DeepParsersTest`: the deep check's parsers on `phone_check.py`'s Samsung IMS fixture, ping's first line, the Wi-Fi calling switch and APN rows in the `content` tool's format, with made-up identifiers mixed in that must not come out;
- `AospParsersTest`: the AOSP IMS reader's parsers on OCD Lab's fixtures (`app/src/test/resources/lab/`, copied from `tools/ocd_lab/fixtures/` at private `main` `0f21d239`): the registry with and without a SIM, the IMS data connection beside a stale log, the call network types, the two-phone IMS dump, the back-off and selector-failure logs, the live log shape of 2026-10-19, the Wi-Fi resolver classes, and the whole deep check on LineageOS with identifiers mixed in;
- `CallTestTest`: the bundled mailbox table, the emergency and service-number guard with poisoned tables and Android's answer, the watch summaries for outgoing, incoming, Wi-Fi, 5G and 2G calls, the emergency discard, and the stored wording;
- `LabelHintTest`: round 22's hint on O2 DE video calling (`app/src/test/resources/fixture-o2-step2.json`) for the S20 without a record, a model whose own file is off, one whose file is on, and the cases without a hint;
- `AllowlistTest`: the guards above;
- `BundleTest`: the committed asset parses;
- `ManifestTest`: only the two permissions and no `INTERNET`, in the app's manifest and in the merged one, and every exported component but the launcher behind a system-only permission.

`tools/test_build_bundle.py` tests the bundle script.

## Layout

| Path | Contents |
|---|---|
| `app/src/main/java/.../core/` | plain Kotlin, no Android imports: bundle model, matcher, findings, report, privacy scan, radio names, the deep check's allowlist, parsers (`DeepParsers.kt` for Samsung, `AospParsers.kt` for Android's own IMS) and findings, the call test (`CallTest.kt`) and round 22's hint (`LabelHints.kt`) |
| `app/src/main/java/.../phone/PhoneReader.kt` | every Android read, reduced to the allowlisted fields at once |
| `app/src/main/java/.../phone/ShizukuAccess.kt` | Shizuku status and permission, starting the deep check, and the call test's watch |
| `app/src/main/java/.../phone/DeepCheckService.kt` | the Shizuku user service: runs the allowlist as the shell user, returns typed results, and runs the call test's watch |
| `app/src/main/java/.../phone/CallTestPhone.kt` | the call test's app-side reads (mailbox table, Android's emergency answer, airplane mode) and its private storage |
| `app/src/main/java/.../ui/` | one Compose activity, one scrolling screen, a ViewModel |
| `app/src/main/assets/ocd-lookup.json.gzip` | the bundled OCD data |
| `app/src/main/assets/call-targets.json` | OCD Lab's mailbox table, copied unchanged |
| `tools/build_bundle.py` | builds the asset from OCD's public checkout |

## Licence

The app's code is under the Apache License 2.0 (`LICENSE`). The bundled carrier data comes from Open Carrier Data, see above.
