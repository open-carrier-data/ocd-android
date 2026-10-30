#!/bin/sh
# Check a finished build with OCD_CARRIER_SETTINGS=true before it is flashed.
#
#   vendor/extra/carriersettings/check_build.sh PRODUCT_OUT
#
# PRODUCT_OUT is out/target/product/x1s. Exactly one of OCD's two apps must be in the image, and
# the script says which:
# - OcdCarrierSettingsDataset (OCD_CARRIER_DATASET on, OCD rules rounds 30 and 31): one carrier
#   data source (SM2: no Google carrier file the loader reads, no .pb file, no
#   /system_ext/etc/CarrierSettings, and every Telephony overlay that names the carrier config
#   package names com.android.carriersettings), the baked dataset present and equal to
#   carriersettings/dataset, its APN rows equal to /product/etc/apns-conf.xml's (the vendor/apn
#   fork's copy of the same pinned file, reformatted), ocd-build.json present and equal, the
#   privapp file listing exactly MODIFY_PHONE_STATE, READ_PRIVILEGED_PHONE_STATE, UPDATE_CONFIG
#   and WRITE_APN_SETTINGS, the writer built from OCD_WRITER_CERT and OCD_WRITER_PACKAGE (SM5,
#   defaults the owner build), and the keys the public lane withholds for privacy listed (SM4);
# - OcdCarrierSettings (switch off): every installed .pb file equals carriersettings/configs.
# In both, LineageOS's CarrierSettings, its files, privapp file and overlay are gone, OCD's
# Telephony overlay and build record are present, and the device override is present and read
# (SM3): CarrierSettingsOverlayOcd with the S20's four values plus LineageOS's common values, the winning overlay of that resource, and never the emergency
# bearer-wait skip, and no device tree override the image does not read. Exit 0 when all hold.
set -eu

out=${1:?usage: check_build.sh PRODUCT_OUT}
here=$(cd "$(dirname "$0")" && pwd)
status=0

need() {
    if [ -e "$out/$1" ]; then echo "present: $1"; else echo "MISSING: $1" >&2; status=1; fi
}
gone() {
    if [ -e "$out/$1" ]; then echo "STILL INSTALLED: $1" >&2; status=1; else echo "absent: $1"; fi
}

plain=system_ext/priv-app/OcdCarrierSettings/OcdCarrierSettings.apk
dataset=system_ext/priv-app/OcdCarrierSettingsDataset/OcdCarrierSettingsDataset.apk
etc=system_ext/etc/OcdCarrierSettings

need system_ext/etc/OcdCarrierSettings/ocd-carriersettings.json
need product/overlay/OcdCarrierSettingsTelephonyOverlay.apk
need product/overlay/CarrierSettingsOverlayOcd.apk
gone system_ext/priv-app/CarrierSettings
gone system_ext/etc/CarrierSettings
gone system_ext/etc/permissions/com.android.carriersettings.xml
gone product/overlay/CarrierSettingsOverlay.apk
gone system_ext/priv-app/OcdCarrierSettingsLayer

if [ -e "$out/$plain" ] && [ -e "$out/$dataset" ]; then
    echo "BOTH INSTALLED: $plain and $dataset" >&2
    exit 1
fi

# SM3: the device override present and read (round 31, item "keys").
python3 -I "$here/check_build_res.py" override "$out" || status=1

if [ -e "$out/$dataset" ]; then
    echo "present: $dataset (OCD's dataset, OCD_CARRIER_DATASET on)"
    gone "$plain"
    gone system_ext/etc/permissions/privapp-permissions-ocd-carriersettings.xml
    privapp=system_ext/etc/permissions/privapp-permissions-ocd-carriersettings-dataset.xml
    need "$privapp"

    # SM2: no Google carrier file the loader reads, anywhere in the image, and Telephony pointed
    # at OCD's CarrierSettings alone.
    pbs=$(find "$out/system_ext" "$out/product" "$out/system" "$out/vendor" -name '*.pb' \( -path '*CarrierSettings*' -o -name 'carrier_list.pb' -o -name 'others.pb' \) 2>/dev/null | wc -l)
    echo "Google carrier files (.pb) in the image: $pbs"
    [ "$pbs" -eq 0 ] || status=1
    python3 -I "$here/check_build_res.py" one-source "$out" || status=1

    # SM5: the writer is the build parameter (OCD_WRITER_CERT and OCD_WRITER_PACKAGE, from the
    # environment when the build set them, else the owner build's).
    python3 -I "$here/check_build_res.py" writer "$out" "$dataset" || status=1
    # SM4: the keys that fall to Android's default because OCD withholds them for privacy.
    python3 -I "$here/check_build_res.py" withheld "$out/$etc/ocd-carriersettings.json" || true

    # The baked dataset: the same file set as carriersettings/dataset, byte for byte.
    want=$(cd "$here/dataset" 2>/dev/null && ls *.json *.xml 2>/dev/null | sort | tr '\n' ' ')
    have=$(cd "$out/$etc/dataset" 2>/dev/null && ls 2>/dev/null | sort | tr '\n' ' ')
    if [ -z "$want" ]; then
        echo "carriersettings/dataset is empty: run S20_Project scripts/ocd-pin-main.py" >&2
        status=1
    elif [ "$want" != "$have" ]; then
        echo "the installed dataset holds [$have], carriersettings/dataset [$want]" >&2
        status=1
    else
        same=0
        total=0
        for f in $want; do
            total=$((total + 1))
            if cmp -s "$here/dataset/$f" "$out/$etc/dataset/$f"; then same=$((same + 1)); else echo "DIFFERS: dataset/$f" >&2; fi
        done
        echo "dataset: $same of $total files equal to carriersettings/dataset"
        [ "$same" -eq "$total" ] || status=1
        grep -q '"role": *"build"' "$out/$etc/dataset/pin.json" || { echo "the baked pin.json is not role build" >&2; status=1; }
    fi

    if cmp -s "$here/ocd-build.json" "$out/$etc/ocd-build.json"; then
        echo "present: $etc/ocd-build.json, equal to carriersettings/ocd-build.json"
    else
        echo "MISSING or DIFFERENT: $etc/ocd-build.json" >&2
        status=1
    fi

    if [ -e "$out/$privapp" ]; then
        perms=$(grep -o 'permission name="[^"]*"' "$out/$privapp" | sed 's/permission name="//; s/"$//' | sort | tr '\n' ' ')
        expected="android.permission.MODIFY_PHONE_STATE android.permission.READ_PRIVILEGED_PHONE_STATE android.permission.UPDATE_CONFIG android.permission.WRITE_APN_SETTINGS "
        if [ "$perms" = "$expected" ]; then
            echo "privapp: the four permissions ($perms)"
        else
            echo "privapp lists [$perms], expected [$expected]" >&2
            status=1
        fi
    fi

    # 7.3: the last-resort APN file is the baked dataset's copy of the pinned apns-conf.xml; the
    # vendor/apn fork reformats the same file into /product/etc/apns-conf.xml. Their rows must be equal.
    if [ -e "$out/$etc/dataset/apns-conf.xml" ] && [ -e "$out/product/etc/apns-conf.xml" ]; then
        if python3 -I - "$out/$etc/dataset/apns-conf.xml" "$out/product/etc/apns-conf.xml" <<'PY'
import collections, sys, xml.etree.ElementTree as ET
def rows(path):
    root = ET.parse(path).getroot()
    return collections.Counter(tuple(sorted(e.attrib.items())) for e in root.iter("apn"))
a, b = rows(sys.argv[1]), rows(sys.argv[2])
print(f"APN rows: baked {sum(a.values())}, /product {sum(b.values())}, equal: {a == b}")
sys.exit(0 if a == b else 1)
PY
        then :; else status=1; fi
    else
        echo "MISSING: the baked or the product apns-conf.xml" >&2
        status=1
    fi
else
    need "$plain"
    need system_ext/etc/permissions/privapp-permissions-ocd-carriersettings.xml
    installed="$out/$etc"
    want=$(ls "$here"/configs/*.pb | wc -l)
    have=$(ls "$installed"/*.pb 2>/dev/null | wc -l)
    same=0
    for file in "$here"/configs/*.pb; do
        if cmp -s "$file" "$installed/$(basename "$file")"; then same=$((same + 1)); fi
    done
    echo "files: $same of $want equal to carriersettings/configs, $have installed"
    if [ "$same" -ne "$want" ] || [ "$have" -ne "$want" ]; then status=1; fi
fi
exit "$status"
