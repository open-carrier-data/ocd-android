#!/usr/bin/env python3
"""Build the OCD Check app's bundled data from a checkout of Open Carrier
Data's public repository.

Reads, and never writes, three files of the public checkout:

- generated/android/lookup.json: per profile, profile_id, display_name,
  match, capabilities and newest_entry are kept; everything else is dropped.
- generated/android/metadata.json: the snapshot's checks_through,
  stale_after and data_digest.
- README.md, section "Limits to read before you ship": the plain meanings of
  the four capability values and the limit bullets the app shows as general
  context (gates, tables not tests, no phone verification, MVNO merge,
  missing switches). The app never hard-codes this text.

Writes one deterministic gzipped JSON asset (sorted keys, no timestamps in
the gzip header). With --check it rebuilds in memory and compares the
content with the committed asset instead of writing.
"""

from __future__ import annotations

import argparse
import gzip
import json
import re
import subprocess
import sys
from pathlib import Path
from typing import Any

FORMAT = "ocd-check-bundle.v1"
REPOSITORY = "https://github.com/open-carrier-data/open-carrier-data"
REPO_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_PUBLIC = REPO_ROOT.parent / "live-public"
# Not ".gz": the Android build gunzips assets ending in .gz and drops the suffix.
DEFAULT_OUT = REPO_ROOT / "app/src/main/assets/ocd-lookup.json.gzip"
INPUTS = ("generated/android/lookup.json", "generated/android/metadata.json", "README.md")

PROFILE_KEYS = ("profile_id", "display_name", "match", "capabilities", "newest_entry")
STRING_MATCH_KEYS = (
    "mccmnc",
    "spn",
    "gid1_prefixes",
    "gid2_prefixes",
    "iccid_prefixes",
    "imsi_prefix_patterns",
)
INT_MATCH_KEYS = ("android_carrier_ids",)
CAPABILITY_VALUES = ("supported", "conditional", "unsupported", "unknown")
DATE = re.compile(r"^\d{4}-\d{2}-\d{2}$")
MONTH = re.compile(r"^\d{4}-\d{2}$")
HEX_COMMIT = re.compile(r"^[0-9a-f]{40}$")

LIMITS_HEADING = "## Limits to read before you ship"
# Top-level Limits bullets the app shows, by their opening words, and how
# many sentences of each it keeps (None: the whole bullet).
LIMIT_BULLETS = {
    "gates": ("A carrier can still block a feature", None),
    "missing_switch": ("A switch that", 1),
    "not_verified": ("No profile has been verified on a phone", None),
    "tables_not_tests": ("A capability value says what carrier tables configure", 2),
    "mvno_merge": ("An MVNO without its own", None),
}


class BundleError(Exception):
    pass


def plain(markdown: str) -> str:
    """Markdown inline text to plain text: links keep their label, code
    spans lose their backticks."""
    text = re.sub(r"\[([^\]]+)\]\([^)]+\)", r"\1", markdown)
    return text.replace("`", "").strip()


def first_sentences(text: str, count: int | None) -> str:
    if count is None:
        return text
    sentences = re.split(r"(?<=\.)\s+(?=[A-Z`\[])", text)
    return " ".join(sentences[:count])


def readme_limits(readme: str) -> dict[str, Any]:
    start = readme.find(LIMITS_HEADING)
    if start < 0:
        raise BundleError(f"README.md has no '{LIMITS_HEADING}' section")
    body = readme[start + len(LIMITS_HEADING):]
    end = body.find("\n## ")
    section = body if end < 0 else body[:end]

    meanings: dict[str, str] = {}
    bullets: list[str] = []
    for line in section.splitlines():
        nested = re.match(r"^  - `(\w+)`: (.+)$", line)
        if nested and nested.group(1) in CAPABILITY_VALUES:
            meanings[nested.group(1)] = plain(nested.group(2))
        elif line.startswith("- "):
            bullets.append(line[2:])

    missing = [value for value in CAPABILITY_VALUES if value not in meanings]
    if missing:
        raise BundleError(f"README Limits gives no plain meaning for {missing}")
    limits: dict[str, Any] = {
        "readme_section": LIMITS_HEADING.lstrip("# "),
        "value_meanings": meanings,
    }
    for name, (opening, sentences) in LIMIT_BULLETS.items():
        found = [bullet for bullet in bullets if bullet.startswith(opening)]
        if len(found) != 1:
            raise BundleError(f"README Limits has {len(found)} bullets starting {opening!r}")
        limits[name] = plain(first_sentences(found[0], sentences))
    return limits


def compact_profile(profile: dict[str, Any], capability_keys: list[str]) -> dict[str, Any]:
    profile_id = profile.get("profile_id")
    if not isinstance(profile_id, str) or not profile_id:
        raise BundleError(f"profile without a profile_id: {profile!r:.120}")
    if not isinstance(profile.get("display_name"), str):
        raise BundleError(f"{profile_id}: display_name is not a string")

    match = profile.get("match")
    if not isinstance(match, dict):
        raise BundleError(f"{profile_id}: match is not an object")
    unknown_keys = set(match) - set(STRING_MATCH_KEYS) - set(INT_MATCH_KEYS)
    if unknown_keys:
        raise BundleError(f"{profile_id}: unknown match keys {sorted(unknown_keys)}")
    kept_match: dict[str, Any] = {}
    for key in STRING_MATCH_KEYS:
        values = match.get(key, [])
        if not isinstance(values, list) or not all(isinstance(v, str) and v for v in values):
            raise BundleError(f"{profile_id}: match.{key} must be a list of strings")
        if values:
            kept_match[key] = values
    for key in INT_MATCH_KEYS:
        values = match.get(key, [])
        if not isinstance(values, list) or not all(
            isinstance(v, int) and not isinstance(v, bool) for v in values
        ):
            raise BundleError(f"{profile_id}: match.{key} must be a list of integers")
        if values:
            kept_match[key] = values
    if not kept_match.get("mccmnc"):
        raise BundleError(f"{profile_id}: match.mccmnc is empty")

    capabilities = profile.get("capabilities")
    if not isinstance(capabilities, dict) or sorted(capabilities) != capability_keys:
        raise BundleError(f"{profile_id}: capability keys differ from the first profile's")
    for key, value in capabilities.items():
        if value not in CAPABILITY_VALUES:
            raise BundleError(f"{profile_id}: capability {key} has value {value!r}")

    kept = {key: profile[key] for key in PROFILE_KEYS if key in profile and profile[key] is not None}
    kept["match"] = kept_match
    newest = kept.get("newest_entry")
    if newest is not None and (not isinstance(newest, str) or not MONTH.match(newest)):
        raise BundleError(f"{profile_id}: newest_entry {newest!r} is not YYYY-MM")
    return kept


def build_bundle(
    lookup: dict[str, Any],
    metadata: dict[str, Any],
    readme: str,
    commit: str,
    commit_date: str,
) -> dict[str, Any]:
    if lookup.get("resolution_order") != "generic_to_specific":
        raise BundleError(f"unexpected resolution_order {lookup.get('resolution_order')!r}")
    profiles = lookup.get("profiles")
    if not isinstance(profiles, list) or not profiles:
        raise BundleError("lookup.json has no profiles")
    if not HEX_COMMIT.match(commit):
        raise BundleError(f"source commit {commit!r} is not a full hex hash")
    for name, value in (
        ("commit_date", commit_date),
        ("checks_through", metadata.get("checks_through")),
        ("stale_after", metadata.get("stale_after")),
    ):
        if not isinstance(value, str) or not DATE.match(value):
            raise BundleError(f"{name} {value!r} is not YYYY-MM-DD")

    capability_keys = sorted(profiles[0].get("capabilities", {}))
    compact = [compact_profile(profile, capability_keys) for profile in profiles]
    compact.sort(key=lambda profile: profile["profile_id"])
    ids = [profile["profile_id"] for profile in compact]
    if len(ids) != len(set(ids)):
        raise BundleError("duplicate profile_id in lookup.json")

    source: dict[str, Any] = {
        "repository": REPOSITORY,
        "commit": commit,
        "commit_date": commit_date,
        "checks_through": metadata["checks_through"],
        "stale_after": metadata["stale_after"],
        "lookup_schema_version": lookup.get("schema_version"),
        "resolution_order": lookup["resolution_order"],
        "match_semantics": lookup.get("match_semantics"),
    }
    if isinstance(metadata.get("data_digest"), str):
        source["data_digest"] = metadata["data_digest"]
    return {
        "format": FORMAT,
        "source": source,
        "capability_keys": capability_keys,
        "limits": readme_limits(readme),
        "profiles": compact,
    }


def encode(bundle: dict[str, Any]) -> bytes:
    text = json.dumps(bundle, sort_keys=True, separators=(",", ":"), ensure_ascii=False)
    return gzip.compress(text.encode("utf-8"), compresslevel=9, mtime=0)


def git(public: Path, *args: str) -> str:
    return subprocess.run(
        ["git", "-C", str(public), *args],
        check=True,
        stdout=subprocess.PIPE,
        text=True,
    ).stdout.strip()


def read_public(public: Path, allow_dirty: bool) -> dict[str, Any]:
    dirty = git(public, "status", "--porcelain", "--", *INPUTS)
    if dirty and not allow_dirty:
        raise BundleError(
            "the public checkout has uncommitted changes in the input files; "
            "commit or stash them, or pass --allow-dirty:\n" + dirty
        )
    return build_bundle(
        json.loads((public / INPUTS[0]).read_text(encoding="utf-8")),
        json.loads((public / INPUTS[1]).read_text(encoding="utf-8")),
        (public / INPUTS[2]).read_text(encoding="utf-8"),
        git(public, "rev-parse", "HEAD"),
        git(public, "log", "-1", "--format=%cs"),
    )


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--public", type=Path, default=DEFAULT_PUBLIC,
                        help=f"checkout of the public repository (default: {DEFAULT_PUBLIC})")
    parser.add_argument("--out", type=Path, default=DEFAULT_OUT)
    parser.add_argument("--check", action="store_true",
                        help="compare with the asset at --out instead of writing it")
    parser.add_argument("--allow-dirty", action="store_true")
    args = parser.parse_args(argv[1:])
    try:
        bundle = read_public(args.public, args.allow_dirty)
    except (BundleError, OSError, subprocess.CalledProcessError, json.JSONDecodeError) as error:
        print(f"build_bundle: {error}", file=sys.stderr)
        return 1
    data = encode(bundle)
    source = bundle["source"]
    summary = (
        f"{len(bundle['profiles'])} profiles, commit {source['commit'][:12]} "
        f"({source['commit_date']}), checks_through {source['checks_through']}, "
        f"stale_after {source['stale_after']}"
    )
    if args.check:
        try:
            current = json.loads(gzip.decompress(args.out.read_bytes()))
        except (OSError, ValueError) as error:
            print(f"build_bundle: cannot read {args.out}: {error}", file=sys.stderr)
            return 1
        if current != bundle:
            print(f"build_bundle: {args.out} differs from a fresh build ({summary})", file=sys.stderr)
            return 1
        print(f"up to date: {args.out} ({summary})")
        return 0
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_bytes(data)
    print(f"wrote {args.out}: {len(data)} bytes, {summary}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
