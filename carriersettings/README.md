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

This folder moved here in one commit from the owner's ROM tree for the Galaxy S20: **vendor/extra `main` at `cfa8ee12ca56f7a5ad76bf06529c110e0af2e560`** (the cut): round 32's APN candidate trials on top of `131caf9`, the build the S20 runs today. No history came with it.

[`cut.json`](cut.json) lists every file with its git blob at the cut, and `check_cut.py` (run in CI) holds the folder to it: 41 files are byte for byte the cut's. The rest:

| File | Change | Why |
|---|---|---|
| `Android.bp` | the writer genrules fail when the writer is unset and accept `none`; the definitions of what stays in the ROM tree are gone | no default writer (SM5); the ROM keeps its dataset, `ocd-build.json`, build record and the legacy `.pb` module |
| `ocd/res/values/ocd_dataset.xml` | the base writer values are an empty list and `none`, not the owner build's | no default writer. The genrules always overlay both, so with the owner's settings the linked `resources.arsc` is byte-identical to the cut's (checked with aapt2) |
| `check_build.sh`, `check_build_res.py` | read the ROM's data folder and its `device-override.json`; the writer check has no default | the S20's values and paths stay in its tree |
| `check_upstream.sh` | the legacy module's code is compared only with `LEGACY_DIR` | that module stays in the ROM tree |
| `ocd/test/run.sh` | runs the contract test | round 37's writer-to-provider contract |
| added | `ocd/test/src/ProtocolContractTest.kt`, `check_cut.py`, `cut.json`, this README | |
| moved | `sepolicy/lineageos/platform_app.te` is vendor/extra's `sepolicy/private/platform_app.te` | LineageOS only, added only by a LineageOS build |

The sources (`ocd/src`), the manifest, the resources, the Telephony overlay, the protos and the permission files are unchanged. The provider's code is the code the S20 runs.

These stay in the ROM tree, because they belong to the ROM or the device:
- the baked dataset and the build record (`ocd-carriersettings.json`);
- `ocd-build.json`, the device's cell and, only after that device family's own first use (SM10), its emergency data test declaration;
- the writer, `OCD_WRITER_CERT` and `OCD_WRITER_PACKAGE`;
- the device's `carrier_config_override` overlay and the ROM family's sepolicy lines;
- Google's `.pb` files and the legacy `.pb` module `OcdCarrierSettings`, until they are retired.

**Status.** The S20 still builds from vendor/extra's copy. Round 37's step 3 builds it from this folder, checks that the provider APK's classes, manifest and resources equal today's apart from signature and timestamps and that the dataset files are byte-identical, and checks calls with o2 and LIDL on the phone. Only then does vendor/extra's copy go, and `rom-builders.md` points here. Until then a change here must also reach vendor/extra, or the two drift; `cut.json` shows which files still match.

**Known limits of the code as moved** (a code change needs its own reviewed pull request, so none rode in with the move):
- The service applies LineageOS's fixed key (`imsvoice.audio_evs_support_bool` false) on every ROM, as LineageOS's CarrierService does.
- `ocd-build.json` fits a phone only with ROM family `lineageos` and `ro.lineage.build.version` as its line. On another ROM no measured record is served and the emergency gate stays full.

## Add it to a ROM

**1. The project.** In a local manifest, pinned to a provider tag (see [Tags](#tags)):

```xml
<remote name="ocd" fetch="https://github.com/open-carrier-data" />
<project name="ocd-android" path="packages/apps/OcdCarrierSettings" remote="ocd"
         revision="refs/tags/carriersettings/v1.0.0" />
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

The `Android.bp` was checked without a ROM build: `bpfmt` from LineageOS 23.2's prebuilts parses it with no change; blueprint's parser evaluates it; the writer genrules' commands, run as Soong runs them, write the owner's values byte for byte as the cut's did, write an empty list for `none`, and fail for an unset or malformed value; and aapt2 links the resources with each.

## Tests and CI

- `ocd/test/run.sh [ANDROID_JAR]`: the JVM tests of the pure code (the spec's matcher vectors, its refusals, the bundle order, the APN path, the engine against a stand-in platform), the contract test, and with an SDK `android.jar` the compile check of the Android glue.
- `ocd/test/verify-baked.sh DATASET_DIR OCD_BUILD_JSON`: the phone's reader over a baked dataset.
- `check_upstream.sh ANDROID_TREE [LEGACY_DIR]`: on a LineageOS tree, what still comes from LineageOS's CarrierSettings is LineageOS's, byte for byte, and no manifest asks for INTERNET.
- `check_build.sh PRODUCT_OUT ROM_DATA_DIR`: the finished image (step 7 above).
- `check_cut.py`: this folder against `cut.json`.

`.github/workflows/provider.yml` runs `run.sh` with Android 36's `android.jar`, `check_cut.py` and the layout check on every pull request, on GitHub's hosted runners. The app's half of the contract runs in `test.yml` with the owner build's tests.

## Tags

The provider has its own tags, `carriersettings/vMAJOR.MINOR.PATCH`, separate from OCD Check's versions. A ROM pins a provider tag and a validated dataset commit (SM6), never `main`, so the app's changes never reach a ROM's CarrierSettings unasked. Each tag's message names the provider protocol version and what changed. The first, `carriersettings/v1.0.0`, is the cut (protocol 3).
