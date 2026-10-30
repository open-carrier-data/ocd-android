#!/bin/sh
# Compare OCD's CarrierSettings with LineageOS's in an Android tree.
#
#   vendor/extra/carriersettings/check_upstream.sh ANDROID_TREE
#
# Exit 0 when:
# - module OcdCarrierSettings (the image with OCD_CARRIER_DATASET off): the code, manifest,
#   resources and protos equal LineageOS's, apart from the one PbConfigLoader line that reads
#   OcdCarrierSettings;
# - module OcdCarrierSettingsDataset (OCD rules rounds 30 and 31, ocd/): ocd/ holds exactly the
#   files listed below; what still comes from LineageOS is LineageOS's, byte for byte: the
#   manifest outside the blocks marked "OCD dataset" (without LineageOS's BootCompletedReceiver,
#   whose class and .pb files this module does not have), and the two blocks of LineageOS's
#   service that apply its fixed EVS key and the device override XML last, for good (round 31,
#   SM3); the resources it shares (res/, with the CarrierSettingsConfig overlayable the device
#   override targets) are covered by the first point;
# - no manifest asks for INTERNET.
# It prints the dataset manifest's diff against LineageOS's, and says how many of the .pb files
# are byte-identical to LineageOS's copy of Google's.
# After LineageOS changes its app, copy its files here, reapply the one PbConfigLoader line,
# carry any change of the service's two blocks or of the manifest into ocd/, and rerun.
set -eu

tree=${1:?usage: check_upstream.sh ANDROID_TREE}
upstream="$tree/hardware/lineage/generic-ims/carriersettings"
here=$(cd "$(dirname "$0")" && pwd)
[ -d "$upstream/src" ] || { echo "no LineageOS CarrierSettings in $upstream" >&2; exit 2; }

scratch=$(mktemp -d)
trap 'rm -rf "$scratch"' EXIT
cp -r "$upstream/src" "$upstream/res" "$upstream/AndroidManifest.xml" "$scratch/"
mkdir "$scratch/proto"
cp "$upstream"/proto/*.proto "$scratch/proto/"
sed -i 's|"etc" + File.separator + "CarrierSettings" + File.separator|"etc" + File.separator + "OcdCarrierSettings" + File.separator|' \
    "$scratch/src/com/android/carriersettings/PbConfigLoader.kt"
grep -q '"OcdCarrierSettings"' "$scratch/src/com/android/carriersettings/PbConfigLoader.kt" || {
    echo "LineageOS's PbConfigLoader no longer has the line OCD changes; review it by hand" >&2
    exit 1
}

status=0
for part in src res AndroidManifest.xml proto; do
    if ! diff -r "$scratch/$part" "$here/$part"; then
        echo "differs from LineageOS: $part" >&2
        status=1
    fi
done
[ "$status" -eq 0 ] && echo "OcdCarrierSettings: equal to LineageOS apart from the OcdCarrierSettings folder"

# --- OcdCarrierSettingsDataset: the checked list of files -----------------------------------
# Every file of ocd/. ocd/test is never built into the app (Android.bp takes ocd/src/**/*.kt
# only); it holds the off-device tests and stand-ins.
ocd_files="
ocd/AndroidManifest.xml
ocd/res/values/ocd_dataset.xml
ocd/src/com/android/carriersettings/CarrierConfigService.kt
ocd/src/com/android/carriersettings/ocd/ApnCandidateSet.kt
ocd/src/com/android/carriersettings/ocd/ApnLogic.kt
ocd/src/com/android/carriersettings/ocd/ApnXml.kt
ocd/src/com/android/carriersettings/ocd/DeviceOverride.kt
ocd/src/com/android/carriersettings/ocd/Engine.kt
ocd/src/com/android/carriersettings/ocd/Files.kt
ocd/src/com/android/carriersettings/ocd/Guard.kt
ocd/src/com/android/carriersettings/ocd/Json.kt
ocd/src/com/android/carriersettings/ocd/Matcher.kt
ocd/src/com/android/carriersettings/ocd/Model.kt
ocd/src/com/android/carriersettings/ocd/OcdAndroid.kt
ocd/src/com/android/carriersettings/ocd/OcdDataProvider.kt
ocd/src/com/android/carriersettings/ocd/OcdReceiver.kt
ocd/src/com/android/carriersettings/ocd/Protocol.kt
ocd/src/com/android/carriersettings/ocd/Serving.kt
ocd/src/com/android/carriersettings/ocd/ServingIndex.kt
ocd/src/com/android/carriersettings/ocd/State.kt
ocd/src/com/android/carriersettings/ocd/Types.kt
ocd/src/com/android/carriersettings/ocd/Verifier.kt
ocd/test/run.sh
ocd/test/src/ApnTest.kt
ocd/test/src/BundleTest.kt
ocd/test/src/CandidateTest.kt
ocd/test/src/EngineTest.kt
ocd/test/src/Fixture.kt
ocd/test/src/ReaderTest.kt
ocd/test/src/VectorsTest.kt
ocd/test/stubs/Stubs.kt
ocd/test/stubs/StubsPlatform.kt
ocd/test/tools/VerifyBaked.kt
ocd/test/verify-baked.sh
"
echo "$ocd_files" | sed '/^$/d' | sort > "$scratch/ocd.expected"
(cd "$here" && find ocd -type f | sort) > "$scratch/ocd.actual"
if diff "$scratch/ocd.expected" "$scratch/ocd.actual" > "$scratch/ocd.diff"; then
    echo "OcdCarrierSettingsDataset: the $(wc -l < "$scratch/ocd.expected") listed files of ocd/ and no other"
else
    echo "ocd/ differs from the checked list (< listed only, > present only):" >&2
    cat "$scratch/ocd.diff" >&2
    status=1
fi
if [ -d "$here/layer" ]; then
    echo "layer/ (round 29's runtime layer) is still here; round 30 removes it" >&2
    status=1
fi

# The two blocks of LineageOS's service, byte for byte in OCD's service: the fixed EVS key, and
# the fixed key plus the device override XML applied last (round 31: for good, SM3).
block() { # file, first line, last line (or a line count with +N)
    awk -v first="$2" -v last="$3" '
        found == 0 && $0 == first { found = 1 }
        found == 1 { print; n++ }
        found == 1 && last ~ /^\+/ && n == substr(last, 2) + 0 { exit }
        found == 1 && last !~ /^\+/ && $0 == last { exit }
    ' "$1"
}
service="$here/ocd/src/com/android/carriersettings/CarrierConfigService.kt"
lineage_service="$upstream/src/com/android/carriersettings/CarrierConfigService.kt"
n=0
for spec in "        private val internalConfigOverride = PersistableBundle().apply {|+3" "        bundle.putAll(internalConfigOverride)|        return bundle"; do
    first=${spec%%|*}
    last=${spec#*|}
    n=$((n + 1))
    block "$lineage_service" "$first" "$last" > "$scratch/block$n.lineage"
    block "$service" "$first" "$last" > "$scratch/block$n.ocd"
    if [ ! -s "$scratch/block$n.lineage" ]; then
        echo "LineageOS's CarrierConfigService.kt no longer has block $n ($first); review it by hand" >&2
        status=1
    elif cmp -s "$scratch/block$n.lineage" "$scratch/block$n.ocd"; then
        echo "OcdCarrierSettingsDataset: service block $n is LineageOS's ($(wc -l < "$scratch/block$n.lineage") lines from \"$(echo "$first" | sed 's/^ *//')\")"
    else
        echo "OcdCarrierSettingsDataset: service block $n differs from LineageOS's" >&2
        diff -u "$scratch/block$n.lineage" "$scratch/block$n.ocd" >&2 || true
        status=1
    fi
done
grep -q 'PbConfigLoader\|ExtendedCarrierIdentifier\|readConfigFromPb' "$service" && {
    echo "OCD's service still reads Google's .pb files" >&2
    status=1
}

# The dataset manifest outside its marked blocks is LineageOS's, without the BootCompletedReceiver.
manifest="$here/ocd/AndroidManifest.xml"
awk '/<!-- OCD dataset: begin -->/ { skip = 1; next } /<!-- OCD dataset: end -->/ { skip = 0; next } !skip { print }' "$manifest" > "$scratch/manifest.stripped"
awk '/<receiver android:name="\.BootCompletedReceiver"/ { skip = 1 } !skip { print } skip && /<\/receiver>/ { skip = 0 }' "$upstream/AndroidManifest.xml" > "$scratch/manifest.lineage"
grep -q 'BootCompletedReceiver' "$upstream/AndroidManifest.xml" || echo "note: LineageOS's manifest no longer has the BootCompletedReceiver"
if cmp -s "$scratch/manifest.stripped" "$scratch/manifest.lineage"; then
    echo "OcdCarrierSettingsDataset: AndroidManifest.xml is LineageOS's (without the BootCompletedReceiver) outside the marked OCD blocks:"
else
    echo "OcdCarrierSettingsDataset: AndroidManifest.xml differs from LineageOS's outside the marked OCD blocks" >&2
    diff -u "$scratch/manifest.lineage" "$scratch/manifest.stripped" >&2 || true
    status=1
fi
diff -u "$upstream/AndroidManifest.xml" "$manifest" | sed 's/^/  /' || true
if grep -q 'android.permission.INTERNET' "$manifest" "$here/AndroidManifest.xml"; then
    echo "a CarrierSettings manifest asks for INTERNET; the config source never does network I/O" >&2
    status=1
fi

total=0
same=0
for file in "$here"/configs/*.pb; do
    total=$((total + 1))
    if cmp -s "$file" "$upstream/configs/$(basename "$file")"; then
        same=$((same + 1))
    fi
done
echo "data (OcdCarrierSettings only): $same of $total .pb files byte-identical to LineageOS's ($(ls "$upstream"/configs/*.pb | wc -l) there)"
exit "$status"
