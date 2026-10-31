# OcdCarrierSettings

The second app in this repository: **OcdCarrierSettingsDataset**, Open Carrier Data's CarrierSettings. It serves each SIM's carrier config from OCD's dataset in place of Google's carrier files, and installs OCD's APN list. Telephony binds it at boot and at every SIM event.

It is a separate app from OCD Check (`../app`), and the two are never one APK (OCD rule round 37, item 6). Each has its own package, key and build system:

| | OcdCarrierSettingsDataset | OCD Check |
|---|---|---|
| Package | `com.android.carriersettings` | `io.github.open_carrier_data.check` (`.owner` for the owner build) |
| Built by | Soong, in a ROM tree (`Android.bp` here) | Gradle (`../app`) |
| Signed by | the ROM builder's platform key | the ROM builder's own key, or the owner's |
| Runs as | privileged system_ext app, directBootAware, own process | system or normal app |
| Network | none: no INTERNET, no UI, no checker code | pulls OCD's data, tests, reports |

The app is the dataset's only writer, through `content://com.android.carriersettings.ocddata`. Their protocol is in [`../protocol`](../protocol/README.md), and both sides test against it.

## Where it comes from

This folder moved here in one commit from the owner's ROM tree for the Galaxy S20: **vendor/extra `main` at `64c73cbf1698c8eed5b55d057287d308dc32acff`** (the cut): round 32's APN candidate trials (`cfa8ee1`) on top of `131caf9`, and `Engine.update` no longer `internal` (see [Soong and Gradle](#soong-and-gradle-in-one-repository)). The first cut, `cfa8ee1`, is tag `carriersettings/v1.0.0`. No history came with it.

[`cut.json`](cut.json) lists every file with its git blob at the cut, and `check_cut.py` (run in CI) holds the folder to it: 35 files are byte for byte the cut's. The rest:

| File | Change | Why |
|---|---|---|
| `Android.bp` | the writer genrules fail when the writer is unset and accept `none`; the definitions of what stays in the ROM tree are gone | no default writer (SM5); the ROM keeps its dataset, `ocd-build.json`, build record and the legacy `.pb` module |
| `ocd/res/values/ocd_dataset.xml` | the base writer values are an empty list and `none`, not the owner build's | no default writer. The genrules always overlay both, so with the owner's settings the linked `resources.arsc` is byte-identical to the cut's (checked with aapt2) |
| `check_build.sh`, `check_build_res.py` | read the ROM's data folder and its `device-override.json`; the writer check has no default | the S20's values and paths stay in its tree |
| `check_upstream.sh` | the legacy module's code is compared only with `LEGACY_DIR` | that module stays in the ROM tree |
| `ocd/test/run.sh` | runs the contract test, and since `carriersettings/v1.1.0` the golden vectors | round 37's writer-to-provider contract; rule round 45 |
| `ocd/src/.../ocd/ServingIndex.kt`, `ocd/test/src/BundleTest.kt` | the serving index carries each profile's sources (schema `ocd-index/2`); the test that the index, the streamed baked files and the dataset serve the same bundle | `carriersettings/v1.0.2`: without the sources no group A read admitted a profile's own value from the index (see [Tags](#tags)) |
| `ocd/src/.../ocd/Matcher.kt`, `OcdAndroid.kt`, `Serving.kt`, `ocd/test/src/VectorsTest.kt` (comment), added `ocd/test/src/GoldenVectorsTest.kt` | OCD's resolution order: Android's weights, read for carrier-ID selectors from the phone's carrier ID table (`content://carrier_id/all`), then the written tie rule, instead of specificity and ID | `carriersettings/v1.1.0`, OCD rule round 45, item 3 (see [Tags](#tags)) |
| added | `ocd/test/src/ProtocolContractTest.kt`, `check_cut.py`, `check_path_independent.sh`, `cut.json`, this README | |
| moved | `sepolicy/lineageos/platform_app.te` is vendor/extra's `sepolicy/private/platform_app.te` | LineageOS only, added only by a LineageOS build |

The manifest, the resources, the Telephony overlay, the protos and the permission files are unchanged, and so are the sources (`ocd/src`) apart from `ServingIndex.kt` since `carriersettings/v1.0.2` and `Matcher.kt`, `OcdAndroid.kt` and `Serving.kt` since `carriersettings/v1.1.0`.

These stay in the ROM tree, because they belong to the ROM or the device:
- the baked dataset and the build record (`ocd-carriersettings.json`);
- `ocd-build.json`, the device's cell and, only after that device family's own first use (SM10), its emergency data test declaration;
- the writer, `OCD_WRITER_CERT` and `OCD_WRITER_PACKAGE`;
- the device's `carrier_config_override` overlay and the ROM family's sepolicy lines;
- Google's `.pb` files and the legacy `.pb` module `OcdCarrierSettings`, until they are retired.

**Status.** Since round 37's step 3 (2026-10-30) the owner's S20 builds the provider from this folder, pinned to a provider tag (build E of 2026-10-30 ran `carriersettings/v1.0.1`), and vendor/extra holds no copy of it any more. Step 3's build from here was checked against the build from vendor/extra's copy before that copy went:
- `check_build.sh` passed;
- the provider APK's classes, manifest and resources are equal apart from the signature, and the dataset files are byte-identical;
- on the S20 with o2 and with LIDL, Telephony bound `com.android.carriersettings`, config was served, the dataset was installed, IMS registered, and calls over LTE and over Wi-Fi calling stayed up past 15 s.

This folder is now the only source of the provider, and [rom-builders.md](https://github.com/open-carrier-data/open-carrier-data/blob/main/docs/rom-builders.md) points here.

**Known limits of the code as moved** (a code change needs its own reviewed pull request, so none rode in with the move):
- The service applies LineageOS's fixed key (`imsvoice.audio_evs_support_bool` false) on every ROM, as LineageOS's CarrierService does.
- `ocd-build.json` fits a phone only with ROM family `lineageos` and `ro.lineage.build.version` as its line. On another ROM no measured record is served and the emergency gate stays full.

## Add it to a ROM

**1. The project.** In a local manifest, pinned to a provider tag (see [Tags](#tags)):

```xml
<remote name="ocd" fetch="https://github.com/open-carrier-data" />
<project name="ocd-android" path="packages/apps/OcdCarrierSettings" remote="ocd"
         revision="refs/tags/carriersettings/v1.0.2" />
```

The path can be any; the examples below use this one. Module names are global in Soong, so a tree never holds this project and a second copy of the module (such as an older vendor tree's) at the same time.

**2. The package.** In the product makefile:

```make
PRODUCT_PACKAGES += OcdCarrierSettingsDataset
```

It brings `OcdCarrierSettingsTelephonyOverlay` (points Telephony at `com.android.carriersettings`), its privapp and default-permission files, and the three modules of step 4. It overrides `CarrierSettings`, so LineageOS's CarrierSettings and its copy of Google's files leave the image.

**3. The writer, or none.** In `BoardConfig.mk` (or a file it includes). There is no default: a build with the provider and without these lines fails.

```make
# The SHA-256 of your own OCD app build's signing certificate, 64 lowercase hex digits as
# `apksigner verify --print-certs` prints it without colons, and its package. Or none for both.
OCD_WRITER_CERT := <64 hex digits, or none>
OCD_WRITER_PACKAGE := io.github.open_carrier_data.check
$(call soong_config_set,ocd,writer_cert,$(OCD_WRITER_CERT))
$(call soong_config_set,ocd,writer_package,$(OCD_WRITER_PACKAGE))
```

The package must be `io.github.open_carrier_data.check` (the `rom` build type of OCD Check, signed by you) or `io.github.open_carrier_data.check.owner`. With `none` for both, no app can write the dataset: the provider serves the baked dataset and reports the writer as absent. Set both to `none` together; `check_build.sh` fails on a mix.

**4. What the ROM tree supplies.** The provider requires three modules, defined in your tree, for example next to your device files:

```
prebuilt_etc {
    name: "ocd_carrier_dataset",
    system_ext_specific: true,
    relative_install_path: "OcdCarrierSettings/dataset",
    srcs: ["dataset/*.json", "dataset/*.xml"],
}

prebuilt_etc {
    name: "ocd_carrier_build",
    system_ext_specific: true,
    relative_install_path: "OcdCarrierSettings",
    src: "ocd-build.json",
    filename_from_src: true,
}

prebuilt_etc {
    name: "ocd_carrier_settings_record",
    system_ext_specific: true,
    relative_install_path: "OcdCarrierSettings",
    src: "ocd-carriersettings.json",
    filename_from_src: true,
}
```

- `dataset/` holds the files of one validated OCD `main` commit or a release's commit, with their `pin.json` (role `build`), as OCD's exporter bakes them (SM6). `ocd/test/verify-baked.sh DATASET_DIR OCD_BUILD_JSON` runs the phone's own reader over them before you commit the pin. A general pin tool for other ROM trees is still to come.
- `ocd-build.json` names your device and ROM line. Without your own first use of the emergency data test on that device family (SM10), leave the `emergency_data_test` part out:

  ```json
  {"schema": "ocd-build/1", "device": {"codename": "<Build.DEVICE>", "model": "<Build.MODEL>"},
   "rom": {"family": "lineageos", "line": "<ro.lineage.build.version>"},
   "ims_stack": {"id": "aosp_imsstack", "fork": null, "package": "com.android.imsstack"}}
  ```
- `ocd-carriersettings.json` is the build record: the pin, the dataset digests and the served values that changed since your previous build. Nothing reads it at runtime.

**5. LineageOS only.** The provider keys measured records by `ro.lineage.build.version`, which LineageOS lets only a few domains read. A LineageOS build adds:

```make
SYSTEM_EXT_PRIVATE_SEPOLICY_DIRS += packages/apps/OcdCarrierSettings/carriersettings/sepolicy/lineageos
```

Another ROM leaves it out: the type it names exists only on LineageOS, and without it the build would fail.

**6. The device's own fixes** stay in your device tree and apply last, after OCD's values, also when the dataset fails to load (SM3). On LineageOS that is a `carrier_config_override.xml` in an overlay of `CarrierSettingsConfig` in `com.android.carriersettings`; the provider keeps LineageOS's overlayable for it (`res/`). On AOSP it is CarrierConfig's `vendor.xml` overlay, which the provider reads from `com.android.carrierconfig`.

**7. After the build,** before you flash:

```bash
OCD_WRITER_CERT=... OCD_WRITER_PACKAGE=... \
  packages/apps/OcdCarrierSettings/carriersettings/check_build.sh out/target/product/<device> <your ROM data folder>
```

The data folder holds `dataset/`, `ocd-build.json` and optionally `device-override.json`, `{"overlay": "<overlay APK name>", "values": {...}}` with exactly what your override sets. The check fails on a second carrier data source, a dataset or `ocd-build.json` that differs from the tree's, a writer that is not the build parameter, a privapp file with other permissions, APN rows that differ from `/product/etc/apns-conf.xml`, and a device override the image does not read, on LineageOS or AOSP.

OCD's ship minimum SM1 to SM12 and the on-device checks before a build ships are in [rom-builders.md](https://github.com/open-carrier-data/open-carrier-data/blob/main/docs/rom-builders.md).

## Soong and Gradle in one repository

- Soong reads only `carriersettings/Android.bp`. It finds every `Android.bp` in a tree by itself, so no top-level `Android.bp` is needed, and none should be added: anything at the root would put the Gradle app in reach of a glob.
- The Gradle app has no `Android.bp` or `Android.mk`. `app/.find-ignore` makes Soong's file finder skip `app/` entirely (`build/soong/ui/build/finder.go` prunes a directory holding `.find-ignore` or `.out-dir`). Such a marker must never sit in the root or in `carriersettings/`, or the provider disappears from the build.
- Gradle's own output (`build/`, `.gradle/`) holds no build files Soong or Kati read.
- CI checks this layout on every pull request.
- The provider's code must not depend on where a ROM puts this folder. Soong names a module's Kotlin module after its directory (`vendor__extra__carriersettings__android_common__OcdCarrierSettingsDataset` against `packages__apps__OcdCarrierSettings__carriersettings__android_common__OcdCarrierSettingsDataset`), and Kotlin puts that name into the JVM name of every `internal` member. Round 37's step 3 found exactly that: the S20 built from both places differed only in `classes.dex`, through `Engine.update`. So the provider has no `internal`, `@JvmName` or `@JvmMultifileClass` declaration, and `check_path_independent.sh` (CI) compiles `ocd/src` with Soong's Kotlin flags under both module names: the classes have the same members, signatures and bytecode (their class files differ only by the module name their `kotlin.Metadata` records, which the ROM build's R8 drops), and R8 configured as Soong configures it for this app writes a byte-identical `classes.dex`. Nothing else in the module carries a path: resources and the manifest hold none, the writer zips hold only values, and the `.kotlin_module` file never reaches the APK.

The `Android.bp` was checked without a ROM build: `bpfmt` from LineageOS 23.2's prebuilts parses it with no change; blueprint's parser evaluates it; the writer genrules' commands, run as Soong runs them, write the owner's values byte for byte as the cut's did, write an empty list for `none`, and fail for an unset or malformed value; and aapt2 links the resources with each.

## Tests and CI

- `ocd/test/run.sh [ANDROID_JAR]`: the JVM tests of the pure code (the spec's matcher vectors, OCD's golden vectors of the resolution order in `../vectors/`, its refusals, the bundle order, the APN path, the engine against a stand-in platform), the contract test, and with an SDK `android.jar` the compile check of the Android glue.
- `ocd/test/verify-baked.sh DATASET_DIR OCD_BUILD_JSON`: the phone's reader over a baked dataset.
- `check_upstream.sh ANDROID_TREE [LEGACY_DIR]`: on a LineageOS tree, what still comes from LineageOS's CarrierSettings is LineageOS's, byte for byte, and no manifest asks for INTERNET.
- `check_build.sh PRODUCT_OUT ROM_DATA_DIR`: the finished image (step 7 above).
- `check_cut.py`: this folder against `cut.json`.
- `check_path_independent.sh ANDROID_JAR [R8_JAR]`: the same code under any ROM path (above).

`.github/workflows/provider.yml` runs `run.sh` with Android 36's `android.jar`, `check_path_independent.sh` with the SDK's R8, `check_cut.py` and the layout check on every pull request, on GitHub's hosted runners. The app's half of the contract runs in `test.yml` with the owner build's tests.

## Tags

The provider has its own tags, `carriersettings/vMAJOR.MINOR.PATCH`, separate from OCD Check's versions. A ROM pins a provider tag and a validated dataset commit (SM6), never `main`, so the app's changes never reach a ROM's CarrierSettings unasked. Each tag's message names the provider protocol version and what changed. `carriersettings/v1.0.0` is the first cut (`cfa8ee1`, protocol 3); `carriersettings/v1.0.1` is the cut `64c73cb`, the same code with `Engine.update` public, so that it builds byte for byte wherever a ROM puts it. `carriersettings/v1.0.2` (protocol 3, unchanged) makes the serving index serve what the dataset serves: a group A read admits a profile's own value only for one of the value's source families, and the index (`ocd-index/1`) left the families out, so from the index no such read was admitted and the key fell to the Google value or Android's default. The first load after a new build streams the baked files, which kept them, so that load and every later one differed. On the S20 with LIDL (build E) it was `carrier_use_ims_first_for_emergency_bool`: `true` at boot, left out after the first reload. Over OCD main `500525ec` with the S20's cell, the index differed from the dataset for a plain SIM of 231 of 3,139 networks (321 group A keys, each admitted by a read); with `ocd-index/2` none differs. An index of the old schema is never complete, so the phone builds it again. `carriersettings/v1.1.0` (protocol 3, unchanged; the dataset format unchanged) sorts a SIM's profiles in OCD's resolution order of rule round 45, item 3, instead of specificity and ID: Android's weight (`CarrierResolver`'s score of the fields a profile pins; a carrier-ID selector adds the fields of the rules the phone's own carrier ID table holds for that ID on the SIM's network, read from `content://carrier_id/all`, and counts at the MCC/MNC-only weight, logged, when the table holds none or cannot be read), then a longer prefix or more fixed digits, an OCD profile above a fill-only one, fewer list values, fewer network codes, and the profile ID last. It computes all of it from `profiles.json`'s `match` and `kind`, so an older provider and this one accept the same datasets. `GoldenVectorsTest` runs OCD's golden vectors (`../vectors/resolution-order.json`, a byte copy of OCD's `tests/vectors/resolution-order.json`), which OCD's resolver and the OCD Check app also pass. On OCD main `500525ec` it changes the serving profile for none of the owner's SIM groups (LIDL Connect on 262-02, o2 on 262-07); OCD's dry run lists the SIM cases it changes.
