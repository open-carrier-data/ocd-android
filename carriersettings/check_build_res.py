#!/usr/bin/env python3
# SPDX-FileCopyrightText: Open Carrier Data
# SPDX-License-Identifier: Apache-2.0
"""Reads APKs of a finished image for carriersettings/check_build.sh (OCD rule round 31).

Plain Python, no Android tools: Android's binary XML (AndroidManifest.xml, res/xml/*.xml) and
resources.arsc, as aapt2 writes them.

  check_build_res.py override PRODUCT_OUT [DEVICE_OVERRIDE_JSON]
                                                SM3: the device override present and read
  check_build_res.py one-source PRODUCT_OUT     SM2: Telephony reads one carrier data source
  check_build_res.py writer PRODUCT_OUT APK     SM5: the writer certificate and package built in
  check_build_res.py withheld RECORD_JSON       SM4: the keys the public lane withholds for privacy

Each prints what it saw and exits 0 when its checks hold.
"""
import json
import os
import struct
import sys
import zipfile

ANDROID_NS = "http://schemas.android.com/apk/res/android"

# --- binary XML and string pools ---------------------------------------------------------------


def string_pool(data, off):
    """The strings of a ResStringPool chunk at off."""
    _, header_size, size = struct.unpack_from("<HHI", data, off)
    count, _styles, flags, strings_start, _ = struct.unpack_from("<IIIII", data, off + 8)
    utf8 = bool(flags & 0x100)
    offsets = struct.unpack_from("<%dI" % count, data, off + header_size)
    base = off + strings_start
    out = []
    for o in offsets:
        p = base + o
        if utf8:
            n = data[p]
            p += 2 if n & 0x80 else 1
            n = data[p]
            if n & 0x80:
                n = ((n & 0x7F) << 8) | data[p + 1]
                p += 2
            else:
                p += 1
            out.append(data[p:p + n].decode("utf-8", "replace"))
        else:
            n = struct.unpack_from("<H", data, p)[0]
            p += 2
            if n & 0x8000:
                n = ((n & 0x7FFF) << 16) | struct.unpack_from("<H", data, p)[0]
                p += 2
            out.append(data[p:p + 2 * n].decode("utf-16-le", "replace"))
    return out


def typed(strings, data_type, value):
    """A Res_value as Python: strings, booleans, ints; references as '@0x...'."""
    if data_type == 0x03:
        return strings[value] if 0 <= value < len(strings) else None
    if data_type == 0x12:
        return value != 0
    if data_type in (0x10, 0x11):
        return value - (1 << 32) if value & 0x80000000 else value
    if data_type == 0x01:
        return "@0x%08x" % value
    return value


def axml(data):
    """Android binary XML to a tree of {tag, attrs, children, text}."""
    if struct.unpack_from("<H", data, 0)[0] != 0x0003:
        raise ValueError("not binary XML")
    strings = []
    root = {"tag": None, "attrs": {}, "children": [], "text": ""}
    stack = [root]
    off = struct.unpack_from("<H", data, 2)[0]
    while off < len(data):
        ctype, header_size, size = struct.unpack_from("<HHI", data, off)
        if ctype == 0x0001:
            strings = string_pool(data, off)
        elif ctype == 0x0102:
            ext = off + header_size
            _ns, name, attr_start, attr_size, attr_count = struct.unpack_from("<IIHHH", data, ext)
            attrs = {}
            for i in range(attr_count):
                a = ext + attr_start + i * attr_size
                ns, aname, raw, _sz, _r, dtype, dval = struct.unpack_from("<IIIHBBI", data, a)
                key = strings[aname]
                if ns != 0xFFFFFFFF and strings[ns] == ANDROID_NS:
                    key = "android:" + key
                attrs[key] = strings[raw] if raw != 0xFFFFFFFF else typed(strings, dtype, dval)
            node = {"tag": strings[name], "attrs": attrs, "children": [], "text": ""}
            stack[-1]["children"].append(node)
            stack.append(node)
        elif ctype == 0x0103:
            stack.pop()
        elif ctype == 0x0104:
            idx = struct.unpack_from("<I", data, off + header_size)[0]
            if idx != 0xFFFFFFFF:
                stack[-1]["text"] += strings[idx]
        off += size
    return root["children"][0] if root["children"] else None


# --- resources.arsc ------------------------------------------------------------------------------


def arsc(data):
    """resources.arsc: {(type, key): [value, ...]} over every configuration, and the overlayables {name: {(type, key)}}."""
    if struct.unpack_from("<H", data, 0)[0] != 0x0002:
        raise ValueError("not a resource table")
    header_size = struct.unpack_from("<H", data, 2)[0]
    off = header_size
    global_strings = []
    entries = {}
    ids = {}
    overlayables = {}
    while off < len(data):
        ctype, hsize, size = struct.unpack_from("<HHI", data, off)
        if ctype == 0x0001:
            global_strings = string_pool(data, off)
        elif ctype == 0x0200:
            pkg_id = struct.unpack_from("<I", data, off + 8)[0]
            type_strings_off, _, key_strings_off = struct.unpack_from("<III", data, off + 8 + 4 + 256)
            types = string_pool(data, off + type_strings_off)
            keys = string_pool(data, off + key_strings_off)
            p = off + hsize
            end = off + size
            pending_overlayable = None
            while p < end:
                t, h, s = struct.unpack_from("<HHI", data, p)
                if t == 0x0001:
                    pass
                elif t == 0x0201:
                    tid, flags = struct.unpack_from("<BB", data, p + 8)
                    count, entries_start = struct.unpack_from("<II", data, p + 12)
                    tname = types[tid - 1]
                    table = p + h
                    slots = []
                    if flags & 0x01:  # sparse
                        for i in range(count):
                            idx, o16 = struct.unpack_from("<HH", data, table + 4 * i)
                            slots.append((idx, o16 * 4))
                    elif flags & 0x02:  # 16-bit offsets
                        for i in range(count):
                            o16 = struct.unpack_from("<H", data, table + 2 * i)[0]
                            if o16 != 0xFFFF:
                                slots.append((i, o16 * 4))
                    else:
                        for i in range(count):
                            o = struct.unpack_from("<I", data, table + 4 * i)[0]
                            if o != 0xFFFFFFFF:
                                slots.append((i, o))
                    for idx, o in slots:
                        e = p + entries_start + o
                        esize, eflags = struct.unpack_from("<HH", data, e)
                        if eflags & 0x0008:  # compact entry: key in size, type in flags
                            key = esize
                            value = typed(global_strings, eflags >> 8, struct.unpack_from("<I", data, e + 4)[0])
                        else:
                            key = struct.unpack_from("<I", data, e + 4)[0]
                            if eflags & 0x0001:  # a map (array, style, ...)
                                count2 = struct.unpack_from("<I", data, e + 12)[0]
                                items = []
                                m = e + esize
                                for _ in range(count2):
                                    _name, _vs, _r, dtype, dval = struct.unpack_from("<IHBBI", data, m)
                                    items.append(typed(global_strings, dtype, dval))
                                    m += 12
                                value = items
                            else:
                                _vs, _r, dtype, dval = struct.unpack_from("<HBBI", data, e + esize)
                                value = typed(global_strings, dtype, dval)
                        name = (tname, keys[key])
                        entries.setdefault(name, []).append(value)
                        ids[(pkg_id << 24) | (tid << 16) | idx] = name
                elif t == 0x0204:
                    # An overlayable, its policy chunks nested inside it (or, from older tools, after it).
                    pending_overlayable = data[p + 8:p + 8 + 512].decode("utf-16-le").split("\x00")[0]
                    refs_of = overlayables.setdefault(pending_overlayable, set())
                    q = p + h
                    while q < p + s:
                        qt, qh, qs = struct.unpack_from("<HHI", data, q)
                        if qt == 0x0205:
                            _flags, count = struct.unpack_from("<II", data, q + 8)
                            refs_of.update(struct.unpack_from("<%dI" % count, data, q + qh))
                        q += qs
                elif t == 0x0205 and pending_overlayable is not None:
                    _flags, count = struct.unpack_from("<II", data, p + 8)
                    overlayables[pending_overlayable].update(struct.unpack_from("<%dI" % count, data, p + h))
                p += s
        off += size
    named = {k: {ids.get(r, ("?", "0x%08x" % r)) for r in v} for k, v in overlayables.items()}
    return entries, named


def apk(path):
    with zipfile.ZipFile(path) as z:
        names = z.namelist()
        manifest = axml(z.read("AndroidManifest.xml"))
        table = arsc(z.read("resources.arsc")) if "resources.arsc" in names else ({}, {})
        files = {n: z.read(n) for n in names if n.startswith("res/xml/")}
    return manifest, table, files


def overlay_of(manifest):
    for child in manifest["children"]:
        if child["tag"] == "overlay":
            return child["attrs"]
    return None


def bundle_xml(node):
    """A PersistableBundle XML fragment (carrier_config) as {key: value}."""
    out = {}
    for c in node["children"]:
        name = c["attrs"].get("name")
        if c["tag"] == "boolean":
            out[name] = c["attrs"].get("value") in (True, "true")
        elif c["tag"] in ("int", "long"):
            out[name] = int(c["attrs"].get("value"))
        elif c["tag"] == "string":
            out[name] = c["text"]
        elif c["tag"] in ("int-array", "string-array"):
            out[name] = [i["attrs"].get("value") for i in c["children"]]
        else:
            out[name] = "<%s>" % c["tag"]
    return out


def overlay_apks(out):
    for part in ("product/overlay", "vendor/overlay", "odm/overlay", "system_ext/overlay", "system/overlay", "system/product/overlay", "system/vendor/overlay"):
        top = os.path.join(out, part)
        for root, _dirs, files in os.walk(top):
            for f in files:
                if f.endswith(".apk"):
                    yield os.path.join(root, f)


# --- the checks ----------------------------------------------------------------------------------

# The device's own override is the ROM tree's (round 37, item 6): DEVICE_OVERRIDE_JSON, for the
# S20 vendor/extra carriersettings/device-override.json, names the overlay APK and exactly the
# values it must hold, {"overlay": "CarrierSettingsOverlayOcd", "values": {key: value}}. Without
# that file only the general checks run: nothing the image does not read, no overlay outranked
# with values lost, never the bearer-wait skip.
HELD = "imsemergency.skip_audio_dedicated_bearer_wait_timer_for_emergency_bool"
# What OcdCarrierSettingsDataset reads of a device tree's override, per target package (SM3).
READ = {
    "com.android.carriersettings": {("xml", "carrier_config_override")},
    "com.android.carrierconfig": {("xml", "vendor"), ("xml", "vendor_no_sim"), ("string", "sku_filter")},
}


def device_override(path):
    """DEVICE_OVERRIDE_JSON as (overlay APK name, values), or None when there is no such file."""
    if not path or not os.path.exists(path):
        return None
    spec = json.load(open(path))
    if set(spec) - {"overlay", "values", "note"} or not isinstance(spec.get("overlay"), str) or not isinstance(spec.get("values"), dict):
        raise ValueError("%s: expected {\"overlay\": name, \"values\": {key: value}}" % path)
    return spec["overlay"] + ".apk", spec["values"]


def check_override(out, spec_path=None):
    ok = True
    try:
        spec = device_override(spec_path)
    except (OSError, ValueError) as e:
        print("FAIL: the device override file is unreadable: %s" % e, file=sys.stderr)
        return False
    want_apk, want_values = spec if spec else (None, None)
    if spec:
        print("device override expected: %s with %d values (%s)" % (want_apk, len(want_values), spec_path))
    else:
        print("device override: no device-override.json, so no overlay is required; the general checks run")
    apps = [os.path.join(out, "system_ext/priv-app/%s/%s.apk" % (n, n)) for n in ("OcdCarrierSettingsDataset", "OcdCarrierSettings")]
    for app in [a for a in apps if os.path.exists(a)][:1]:
        _m, (entries, overlayables), _f = apk(app)
        target = overlayables.get("CarrierSettingsConfig", set())
        if ("xml", "carrier_config_override") in target:
            print("CarrierSettings: overlayable CarrierSettingsConfig holds xml/carrier_config_override")
        else:
            print("FAIL: CarrierSettings has no overlayable CarrierSettingsConfig with xml/carrier_config_override: a device override would not apply", file=sys.stderr)
            ok = False
    found = False
    # Android applies only the highest-priority overlay of carrier_config_override.xml, so every
    # other overlay's values must be in the winner, or they are silently lost.
    competing = []
    for path in sorted(overlay_apks(out)):
        try:
            manifest, (entries, _o), files = apk(path)
        except Exception as e:  # noqa: BLE001 - a broken overlay is itself a finding
            print("FAIL: %s is unreadable: %s" % (path[len(out) + 1:], e), file=sys.stderr)
            ok = False
            continue
        ov = overlay_of(manifest)
        if not ov:
            continue
        tp = ov.get("android:targetPackage")
        if tp not in READ:
            continue
        rel = path[len(out) + 1:]
        names = set(entries)
        if ov.get("android:resourcesMap"):
            # A resources map (overlays.xml) names the target resources it overlays; the map
            # itself is not one of them.
            maps = [n for n in files if n.startswith("res/xml/") and axml(files[n])["tag"] == "overlay"]
            names = set()
            for n in maps:
                for item in axml(files[n])["children"]:
                    if item["tag"] == "item" and "/" in item["attrs"].get("target", ""):
                        names.add(tuple(item["attrs"]["target"].split("/", 1)))
        unread = sorted("%s/%s" % n for n in names - READ[tp])
        if tp == "com.android.carriersettings" and ov.get("android:targetName") != "CarrierSettingsConfig":
            print("FAIL: %s overlays %s without targetName CarrierSettingsConfig" % (rel, tp), file=sys.stderr)
            ok = False
        if unread:
            print("FAIL: %s overlays %s with resources the image does not read: %s" % (rel, tp, ", ".join(unread)), file=sys.stderr)
            ok = False
        else:
            print("device override read: %s -> %s %s" % (rel, tp, ", ".join(sorted("%s/%s" % n for n in names))))
        if tp == "com.android.carriersettings" and "res/xml/carrier_config_override.xml" in files:
            values = bundle_xml(axml(files["res/xml/carrier_config_override.xml"]))
            competing.append((int(ov.get("android:priority") or 0), rel, values))
            if want_apk and os.path.basename(path) == want_apk:
                found = True
                if values == want_values:
                    print("device override values: the expected values (%s)" % ", ".join("%s=%s" % kv for kv in sorted(values.items())))
                else:
                    print("FAIL: %s holds %s, expected exactly %s" % (want_apk, json.dumps(values, sort_keys=True), json.dumps(want_values, sort_keys=True)), file=sys.stderr)
                    ok = False
            if HELD in values:
                print("FAIL: %s sets %s, which the read of 2026-10-25 held (a new second-agent read is needed)" % (rel, HELD), file=sys.stderr)
                ok = False
    if want_apk and not found:
        print("FAIL: no %s with carrier_config_override.xml in the image" % want_apk, file=sys.stderr)
        ok = False
    competing.sort(key=lambda c: c[0], reverse=True)
    if len(competing) > 1:
        top_priority, top_rel, top_values = competing[0]
        if competing[1][0] == top_priority:
            print("FAIL: %s and %s overlay carrier_config_override.xml at the same priority %d: which applies is not defined" % (top_rel, competing[1][1], top_priority), file=sys.stderr)
            ok = False
        if want_apk and os.path.basename(top_rel) != want_apk:
            print("FAIL: %s outranks %s for carrier_config_override.xml" % (top_rel, want_apk), file=sys.stderr)
            ok = False
        for _p, rel, values in competing[1:]:
            lost = sorted(k for k, v in values.items() if top_values.get(k, object()) != v)
            if lost:
                print("FAIL: %s is outranked by %s, and these of its values would be lost: %s" % (rel, top_rel, ", ".join(lost)), file=sys.stderr)
                ok = False
            else:
                print("device override: %s's values are all in %s, which applies" % (rel, top_rel))
    return ok


def check_one_source(out):
    """SM2: every overlay of com.android.phone that sets platform_carrier_config_package names OCD's CarrierSettings."""
    ok = True
    seen = False
    for path in sorted(overlay_apks(out)):
        try:
            manifest, (entries, _o), _f = apk(path)
        except Exception:  # noqa: BLE001 - check_override reports unreadable overlays
            continue
        ov = overlay_of(manifest)
        if not ov or ov.get("android:targetPackage") != "com.android.phone":
            continue
        values = entries.get(("string", "platform_carrier_config_package"))
        if not values:
            continue
        rel = path[len(out) + 1:]
        if all(v == "com.android.carriersettings" for v in values):
            print("Telephony's carrier config package: com.android.carriersettings (%s)" % rel)
            seen = True
        else:
            print("FAIL: %s sets Telephony's carrier config package to %s" % (rel, values), file=sys.stderr)
            ok = False
    if not seen:
        print("FAIL: no overlay points Telephony at com.android.carriersettings; Telephony's default is com.android.carrierconfig", file=sys.stderr)
        ok = False
    cc = [p for p in ("system/priv-app/CarrierConfig", "system_ext/priv-app/CarrierConfig", "product/priv-app/CarrierConfig") if os.path.isdir(os.path.join(out, p))]
    if cc:
        print("note: AOSP CarrierConfig is in the image (%s) but is not Telephony's carrier config package, so its assets are not read; only its vendor.xml override is" % cc[0])
    return ok


def check_writer(out, apk_rel):
    """SM5 with no default writer (round 37): the APK's writer is OCD_WRITER_CERT and OCD_WRITER_PACKAGE, both none or both set."""
    _m, (entries, _o), _f = apk(os.path.join(out, apk_rel))
    certs = entries.get(("array", "ocd_owner_certs"), [[]])
    pkg = entries.get(("string", "ocd_writer_package"), [None])
    want_cert = os.environ.get("OCD_WRITER_CERT")
    want_pkg = os.environ.get("OCD_WRITER_PACKAGE")
    print("writer: %s, certificate SHA-256 %s" % (pkg[0], ", ".join(certs[0]) if certs[0] else "none"))
    if not want_cert or not want_pkg:
        print("FAIL: set OCD_WRITER_CERT and OCD_WRITER_PACKAGE to what the build set (none for no writer); there is no default", file=sys.stderr)
        return False
    if (want_cert == "none") != (want_pkg == "none"):
        print("FAIL: OCD_WRITER_CERT and OCD_WRITER_PACKAGE must both be none, or neither", file=sys.stderr)
        return False
    expected_certs = [] if want_cert == "none" else [want_cert]
    ok = len(certs) == 1 and certs[0] == expected_certs and pkg == [want_pkg]
    if not ok:
        print("FAIL: the writer is not the build parameter (OCD_WRITER_CERT %s, OCD_WRITER_PACKAGE %s)" % (want_cert, want_pkg), file=sys.stderr)
    elif want_cert == "none":
        print("writer: none, so no app can write the dataset and the provider serves only the baked one")
    return ok


def withheld(record):
    try:
        rom = json.load(open(record)).get("ocd_owner_rom", {})
    except (OSError, ValueError) as e:
        print("note: the build record is unreadable (%s)" % e)
        return True
    keys = rom.get("privacy_withheld_keys")
    if keys is None:
        print("SM4: the build record lists no privacy-withheld keys yet (the exporter's SM4 list, implementation order step 4)")
    else:
        print("SM4: %d keys fall to Android's default because OCD withholds them for privacy: %s" % (len(keys), ", ".join(sorted(keys))))
    return True


def main(argv):
    if len(argv) >= 3 and argv[1] == "override":
        return 0 if check_override(argv[2], argv[3] if len(argv) >= 4 else None) else 1
    if len(argv) >= 3 and argv[1] == "one-source":
        return 0 if check_one_source(argv[2]) else 1
    if len(argv) >= 4 and argv[1] == "writer":
        return 0 if check_writer(argv[2], argv[3]) else 1
    if len(argv) >= 3 and argv[1] == "withheld":
        return 0 if withheld(argv[2]) else 1
    if len(argv) >= 3 and argv[1] == "dump":
        m, (entries, overlayables), files = apk(argv[2])
        print(json.dumps({"overlay": overlay_of(m), "resources": sorted("%s/%s" % n for n in entries), "overlayables": {k: sorted("%s/%s" % n for n in v) for k, v in overlayables.items()},
                          "xml": {n: bundle_xml(axml(b)) for n, b in files.items()}}, indent=1, default=str))
        return 0
    print(__doc__, file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv))
