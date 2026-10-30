#!/usr/bin/env python3
# SPDX-FileCopyrightText: Open Carrier Data
# SPDX-License-Identifier: Apache-2.0
"""Checks carriersettings/ against cut.json, the record of the move from the owner's ROM tree.

OCD rule round 37, item 6: the provider moved byte for byte from vendor/extra at the cut commit,
apart from the changes the ruling's conditions need. cut.json lists each file with the git blob
it had at the cut. A file marked "equal" must still have that blob; a file marked "changed" or
"added" names why. A file in carriersettings/ that cut.json does not list fails, so no change
rides in unnamed while vendor/extra still holds its own copy (until round 37's step 3 removes it).

  python3 carriersettings/check_cut.py

Plain Python, no git needed: the blob ID is SHA-1 over "blob <size>\\0" and the bytes.
"""
import hashlib
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
IGNORED_DIRS = {"__pycache__"}


def blob(path):
    data = open(path, "rb").read()
    return hashlib.sha1(b"blob %d\0" % len(data) + data).hexdigest()


def main():
    record = json.load(open(os.path.join(HERE, "cut.json")))
    files = record["files"]
    ok = True
    present = set()
    for root, dirs, names in os.walk(HERE):
        dirs[:] = [d for d in dirs if d not in IGNORED_DIRS]
        for name in names:
            present.add(os.path.relpath(os.path.join(root, name), HERE))
    equal = changed = added = 0
    for rel, entry in sorted(files.items()):
        status = entry["status"]
        path = os.path.join(HERE, rel)
        if not os.path.exists(path):
            print("MISSING: %s (%s)" % (rel, status), file=sys.stderr)
            ok = False
            continue
        if status == "equal":
            if blob(path) == entry["blob"]:
                equal += 1
            else:
                print("DIFFERS from the cut: %s (blob %s, cut %s); mark it changed with a reason, or restore it" % (rel, blob(path), entry["blob"]), file=sys.stderr)
                ok = False
        elif status in ("changed", "added"):
            if not entry.get("why"):
                print("NO REASON: %s is %s without a why" % (rel, status), file=sys.stderr)
                ok = False
            changed += status == "changed"
            added += status == "added"
        else:
            print("UNKNOWN status %r for %s" % (status, rel), file=sys.stderr)
            ok = False
    for rel in sorted(present - set(files)):
        print("UNLISTED: %s is not in cut.json" % rel, file=sys.stderr)
        ok = False
    cut = record["cut"]
    print("cut %s (%s %s): %d files equal, %d changed, %d added" % (cut["commit"][:7], cut["tree"], cut["branch"], equal, changed, added))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
