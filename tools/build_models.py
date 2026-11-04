#!/usr/bin/env python3
"""Build the list of phone models the week report may name (OCD rule round 48)
from a checkout of Open Carrier Data's public repository.

Reads, and never writes, generated/devices/android.json, and keeps only the
sorted distinct `models`. A phone whose Build.MODEL is not on the list sends
"other", so a ROM's free-text model never leaves it. The publish step checks
every value against the same public file, so a list older than main only
sends "other" more often.

Writes one deterministic gzip file. With --check it compares the content with
the committed asset instead of writing.
"""

from __future__ import annotations

import argparse
import gzip
import io
import json
import sys
from pathlib import Path
from typing import Any

REPO_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_PUBLIC = REPO_ROOT.parent / "live-public"
# Not ".gz": the Android build gunzips assets ending in .gz and drops the suffix.
DEFAULT_OUT = REPO_ROOT / "app/src/main/assets/ocd-models.txt.gzip"
INPUT = "generated/devices/android.json"


def models(catalog: dict[str, Any]) -> list[str]:
    devices = catalog.get("devices")
    if not isinstance(devices, list):
        raise ValueError("android.json has no devices list")
    found = {model for device in devices for model in device.get("models", [])}
    return sorted(model for model in found if model and "\n" not in model and "\r" not in model)


def text(names: list[str]) -> bytes:
    return ("\n".join(names) + "\n").encode("utf-8")


def packed(data: bytes) -> bytes:
    out = io.BytesIO()
    with gzip.GzipFile(filename="", mode="wb", compresslevel=9, fileobj=out, mtime=0) as file:
        file.write(data)
    return out.getvalue()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--public", type=Path, default=DEFAULT_PUBLIC)
    parser.add_argument("--out", type=Path, default=DEFAULT_OUT)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    data = text(models(json.loads((args.public / INPUT).read_text(encoding="utf-8"))))
    if args.check:
        if gzip.decompress(args.out.read_bytes()) != data:
            print(f"{args.out} differs from {INPUT}", file=sys.stderr)
            return 1
        return 0
    args.out.write_bytes(packed(data))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
