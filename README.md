# OCD Check

OCD Check is an Android app that reads a phone's SIM, network and carrier settings and compares them with [Open Carrier Data](https://github.com/open-carrier-data/open-carrier-data) (OCD). It is read-only. It changes nothing on the phone, and it has no internet permission, so it cannot send anything.

**Status: early and unpublished.** This is version 0.1.0: stage 2 of the plan (view only, normal app) plus stage 2b (the optional read-only Shizuku deep check). It first ran on a phone on 2026-10-13 (Galaxy A50, Android 14, LIDL Connect SIM), and it is not on any store. The JVM tests pass and the debug build assembles.

## What it does

1. **Reads the phone**, through public Android calls only:
   - **Your SIM:** network code (MCC/MNC), SIM name (SPN), Android carrier ID and specific carrier ID with their names, and GID1 if the permission is granted.
   - **Network now:** serving network code, roaming, the data and voice network types, and three 5G hints: standalone (SA) in use, the 5G icon (`TelephonyDisplayInfo`), and whether the phone reports a 5G signal.
   - **Carrier settings on this phone:** six CarrierConfig keys and nothing else: `carrier_volte_available_bool`, `carrier_wfc_ims_available_bool`, `carrier_vt_available_bool`, `vonr_enabled_bool`, `enabledMMS` and `carrier_nr_availabilities_int_array`. It also reads whether carrier-specific settings were applied at all.
   - **Mobile data connections:** the APN name and the APN types of each mobile data connection Android shows to apps.
2. **Looks the SIM up in OCD's data**, which ships inside the app. It shows the matching profiles, general to specific, and the most specific profile's value for every capability. Each value comes with OCD's own plain meaning from its README, "Limits to read before you ship".
3. **Writes findings in plain language.** A finding compares a phone reading with an OCD value, for example "OCD's answer is supported; this phone's carrier settings have it off". A finding never says a feature works, that OCD is wrong, or that a carrier does not support something. Whether the phone is signed in for VoLTE or Wi-Fi calling is hidden from normal apps. The app says this needs the deep check and does not guess.
4. **Builds a report** you can read, then copy or share through Android's share sheet. The full text is on screen before Copy and Share appear.
5. **Optionally runs a deep check through Shizuku** (see below): whether the phone is signed in for VoLTE or Wi-Fi calling on Samsung's IMS, Samsung's Wi-Fi calling switch, whether the carrier's Wi-Fi calling server name resolves, and the phone's APN list. Without Shizuku the app works exactly as above.

## Permissions, and why

| Permission | Kind | Used for |
|---|---|---|
| `READ_PHONE_STATE` | asked at run time, with an explanation first | GID1, the carrier settings, the data and voice network type, and the list of active SIMs. Android's dialog calls this "make and manage phone calls". The app makes no calls and sees no calls. |
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
- anything emergency-related

An emergency data connection is skipped, and the app never dials.

## What a normal app cannot see

Android hides these from normal apps:
- whether the phone is signed in for VoLTE, Wi-Fi calling or SMS over IMS (IMS registration), and its errors;
- the phone's APN list;
- whether the Wi-Fi network's DNS resolves the carrier's Wi-Fi calling server (ePDG). A normal app would need the internet permission for that;
- the network a call actually uses;
- GID2, and OCD profiles that match on ICCID or IMSI. The app lists these as "profiles this app cannot check".

The deep check below reads the IMS registration (on Samsung), the APN list and the ePDG name. IMS errors, the call network, GID2 and the ICCID or IMSI matches stay out of reach.

## The deep check (Shizuku)

**Why it exists.** The question people ask most, "is my phone signed in for VoLTE or Wi-Fi calling?", is closed to normal apps. On stock Samsung phones only the shell user (adb) or root can read it. [Shizuku](https://shizuku.rikka.app/) lets an app run code as the shell user after the owner starts it through wireless debugging. The deep check uses that to repeat, on the phone, the read-only adb check the owner ran by hand on a Galaxy A50 (`tools/phone_check.py` in OCD's private repository). That check found the A50's Wi-Fi calling failure: the home network's DNS did not resolve the carrier's ePDG name.

**It is optional.** Without Shizuku, or without your OK in Shizuku's dialog, nothing in it runs, and the report says `"access_tier": "normal"`. The app asks Shizuku for access only when you tap "Allow in Shizuku", and runs the check only when you tap "Run deep check". With results, the report says `"access_tier": "shizuku"`.

**It is read-only.** The whole allowlist is four commands, a closed Kotlin type (`core/DeepCheck.kt`, `ShellCommand`). Each builds its arguments from constants and two validated values: the SIM slot (0 to 3) and the home network code (5 or 6 ASCII digits). Nothing else can reach a process. For a 26202 SIM in the second slot (index 1), the commands are:

| Command | What it reads | What the app keeps |
|---|---|---|
| `dumpsys secims` | Samsung's IMS state | for this slot: signed in or not, Samsung's state word, the network of the registration (`RAT = 13` LTE, `18` Wi-Fi, `20` 5G), the services (calls, SMS and other known names), Samsung's carrier table name (MNO, for example `Vodafone_DE`) |
| `settings get system wifi_call_enable2` | Samsung's Wi-Fi calling switch for the slot (run only when Samsung's IMS was found) | on, off or not set |
| `ping -c1 -W2 epdg.epc.mnc002.mcc262.pub.3gppnetwork.org` | whether the carrier's standard ePDG name resolves | resolves, does not resolve or not known. Only the first output line is read |
| `content query --uri content://telephony/carriers --projection apn:type:protocol:roaming_protocol:carrier_enabled:mvno_type:edited --where numeric='26202'` | the APN rows for the home network | the listed columns, up to 40 rows |

How the output is handled:
- **Parsed where it is read.** Shizuku starts the app's `DeepCheckService` as the shell user (or root) in a process of its own. That process runs the commands and parses their output line by line. Each line is dropped once it has been looked at.
- **Only typed fields reach the app.** The result is enums, booleans and short strings checked for shape. IMSI, ICCID, IMEI, phone numbers, SIP URIs and IP addresses have no field to land in; an MNO or APN name with a run of 9 or more digits is dropped. The app decodes the result strictly, so an unknown key or a wrong shape fails.
- **The APN query never asks for credentials.** It reads no user name, password, server, proxy, port or MMSC column, and not the MVNO match value (an SPN, GID or IMSI pattern). Emergency and deleted rows are left out.
- **The ePDG lookup touches the network.** It sends one DNS query through the phone's default network. If the name resolves, the command may send one ping to the carrier's server. The app itself still has no internet permission; the shell user does the lookup. The app records whether the default network was Wi-Fi and whether Private DNS was on, with `ACCESS_NETWORK_STATE`. The lookup is IPv4 only, as in `phone_check.py`, and a carrier may use another server name, so a failure is a strong hint, not proof.

The app never sends a write command. Shizuku's grant could write (`settings put`, `cmd phone`, `content insert`), so tests guard the code (`AllowlistTest`):
- every command in the allowlist, for every slot, is checked against a list of write verbs and shell characters;
- only `DeepCheckService.kt` may start a process, and only from a `ShellCommand`;
- the app may call only Shizuku's status, permission and user-service methods, never its binder wrapper, remote process or `exit`.

**Findings.** The deep check adds findings in the same plain style: VoLTE, VoNR or Wi-Fi calling signed in; not signed in for calls over 4G, with possible causes where OCD's tables turn VoLTE on; Wi-Fi calling switched on but not signed in. When the ePDG name also does not resolve, the finding names the A50 fix: Private DNS such as `dns.google`, or fixing the router's DNS. "Signed in" means the phone registered with the carrier's IMS; it never says a call connects.

**Limits.**
- **IMS state on Samsung only.** The project's study of Android access (`research/ocd-check-app-20261010/notes/android-access.md`) names `dumpsys phone` for other phones. In the AOSP 16 source, `dumpsys phone` prints which IMS service is bound but not whether it is registered. The registration line is in the DataNetworkController dump, which comes from `dumpsys activity service com.android.phone`. That source is not in the notes and no phone output for it is at hand, so other phones show "no Samsung IMS service" and the app does not guess.
- **No call network type, IMS error codes or Wi-Fi calling back-off.** These need `dumpsys telephony.registry` or logs, which hold phone numbers and cell IDs.
- **Shizuku 11 or newer.** On a phone without root, Shizuku must be started again after every reboot.

## The report

The report is JSON with a fixed set of keys. `app.access_tier` is `normal`, or `shizuku` when the deep check's results are in. Its rules:
- **Content:** the SIM's network code, name and carrier IDs, GID1 only if you switch it on, the phone model and its code name (`Build.DEVICE`, which LineageOS uses to name device trees), the Android major version, the software family (`stock`, `lineageos` or `other`), the check results, and the app and data versions.
- **Findings** go in as codes, not sentences.
- **Carrier settings** go in only as a per-key comparison with OCD's answer (`matches_ocd`), never as values.
- **Deep check results** go in only as typed fields. `ims_registration` is `needs_deep_check` without them. With them, it holds `source`, `registered`, `state`, `transport`, `services` and `mno`. Two more blocks join it: `wifi_calling` (`switch`, `epdg_name`, `lookup_network`, `private_dns_active`) and `apn_list` (`readable`, `rows` with `apn`, `types`, `protocol`, `roaming_protocol`, `carrier_enabled`, `mvno_type` and `edited`, and `left_out`).
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
    "app": { "name": "OCD Check", "version": "0.1.0", "access_tier": "normal" },
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
    "app": { "name": "OCD Check", "version": "0.1.0", "access_tier": "shizuku" },
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

`app/src/main/assets/ocd-lookup.json.gzip` (about 175 KB) is built from a checkout of OCD's public repository by `tools/build_bundle.py`. The script reads three files and changes none of them:

| File | What the bundle keeps |
|---|---|
| `generated/android/lookup.json` | per profile: `profile_id`, `display_name`, `match`, `capabilities`, `newest_entry` |
| `generated/android/metadata.json` | the snapshot's `checks_through`, `stale_after` and `data_digest` |
| `README.md`, "Limits to read before you ship" | the plain meanings of the four capability values and five limit bullets: gates, missing switches, no phone verification, tables not tests, MVNO merge |

The bundle also records the source commit and its date. The app shows the data date and warns after `stale_after`.

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

The bundled data is OCD's. The OCD contributors waive their own rights in it under CC0 1.0; see OCD's `DATA-LICENSE.md`.

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

The APK is `app/build/outputs/apk/debug/app-debug.apk`.

**Version pins.** The project uses Android Gradle Plugin 8.7.3, the newest line that still builds with build-tools 34.0.0; AGP 8.8 and later need build-tools 35. `gradle.properties` silences AGP 8.7's warning about compileSdk 36, and `android.builder.sdkDownload=false` stops the build from fetching SDK packages on its own. The androidx versions match that AGP:
- Compose BOM 2025.06.00
- activity-compose 1.10.1
- lifecycle 2.9.1

To move to newer libraries, install build-tools 35 or later, raise AGP, and then raise the libraries.

**Tests.** JVM tests sit under `app/src/test`. They use a fixture of 17 real profiles from `lookup.json` (`app/src/test/resources/fixture-bundle.json`):
- `MatcherTest`: the 26202 base profile, LIDL Connect, the Vodafone.de SPN, the 1&1 GID1 profile, the Telekom stack from OCD's README example, Tracfone's specific carrier ID, and ICCID, IMSI and GID2 profiles;
- `FindingsTest`: the comparisons, no German MMS claim, no "works" or "OCD is wrong" wording, the deep-check finding, and the deep check's findings (the A50 DNS case, signed in over 4G or Wi-Fi, not signed in over 4G);
- `ReportTest`: the key allowlist, the redaction and fail-closed scan, the GID1 switch, date only, both access tiers, and a Shizuku report built from dumps full of identifiers;
- `DeepParsersTest`: the deep check's parsers on `phone_check.py`'s Samsung IMS fixture, ping's first line, the Wi-Fi calling switch and APN rows in the `content` tool's format, with made-up identifiers mixed in that must not come out;
- `AllowlistTest`: the guards above;
- `BundleTest`: the committed asset parses;
- `ManifestTest`: only the two permissions and no `INTERNET`, in the app's manifest and in the merged one, and every exported component but the launcher behind a system-only permission.

`tools/test_build_bundle.py` tests the bundle script.

## Layout

| Path | Contents |
|---|---|
| `app/src/main/java/.../core/` | plain Kotlin, no Android imports: bundle model, matcher, findings, report, privacy scan, radio names, and the deep check's allowlist, parsers and findings |
| `app/src/main/java/.../phone/PhoneReader.kt` | every Android read, reduced to the allowlisted fields at once |
| `app/src/main/java/.../phone/ShizukuAccess.kt` | Shizuku status and permission, and starting the deep check |
| `app/src/main/java/.../phone/DeepCheckService.kt` | the Shizuku user service: runs the allowlist as the shell user and returns typed results |
| `app/src/main/java/.../ui/` | one Compose activity, one scrolling screen, a ViewModel |
| `app/src/main/assets/ocd-lookup.json.gzip` | the bundled OCD data |
| `tools/build_bundle.py` | builds the asset from OCD's public checkout |

## Licence

The app's code is under the Apache License 2.0 (`LICENSE`). The bundled carrier data comes from Open Carrier Data, see above.
