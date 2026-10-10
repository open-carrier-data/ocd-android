#!/usr/bin/env python3
"""Tests for build_bundle.py. Run: python3 -m unittest discover -s tools -p 'test_*.py'"""

from __future__ import annotations

import gzip
import json
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import build_bundle  # noqa: E402

CAPS = {"mms": "supported", "volte": "conditional", "vowifi": "unknown"}
README = """# Open Carrier Data

## Limits to read before you ship

Read these before you depend on the data:

- In plain words, the four capability values mean:
  - `supported`: on in a table. It does not tell you that the carrier still offers it.
  - `conditional`: the sources disagree.
  - `unsupported`: off.
  - `unknown`: no reliable source. It does not mean "no".
- A carrier can still block a feature that its settings turn on. Gates are listed here.
- A switch that `carrier-config-list.xml` leaves out is not neutral. [docs/consume.md](docs/consume.md) lists the defaults.
- No profile has been verified on a phone. Every value comes from a maintained source.
- A capability value says what carrier tables configure, not what works. It does not say the operator still offers the service. `supported` means more.
- An MVNO without its own SPN merges into the host network's profile.

## How it is built
"""


def lookup(*profiles: dict) -> dict:
    return {
        "schema_version": 1,
        "match_semantics": "OR within a match list; AND between match dimensions",
        "resolution_order": "generic_to_specific",
        "profiles": list(profiles),
    }


def profile(profile_id: str, match: dict, **extra: object) -> dict:
    return {
        "profile_id": profile_id,
        "display_name": "Example",
        "match": match,
        "capabilities": dict(CAPS),
        "path": f"carriers/open/{profile_id}.json",
        "specificity": 0,
        "android_apn_count": 3,
        **extra,
    }


METADATA = {"checks_through": "2026-07-14", "stale_after": "2027-01-10", "data_digest": "sha256:ab"}
COMMIT = "231dd6fc612a8850c0ab7a2b9c6a49122f95a431"


class BuildBundleTest(unittest.TestCase):
    def build(self, *profiles: dict) -> dict:
        return build_bundle.build_bundle(lookup(*profiles), METADATA, README, COMMIT, "2026-10-10")

    def test_keeps_only_the_listed_fields_sorted_by_id(self) -> None:
        bundle = self.build(
            profile("open.26202.b", {"mccmnc": ["26202"], "spn": ["Vodafone.de"]}, newest_entry="2026-09"),
            profile("open.26202.a", {"mccmnc": ["26202"]}),
        )
        self.assertEqual([p["profile_id"] for p in bundle["profiles"]], ["open.26202.a", "open.26202.b"])
        self.assertEqual(set(bundle["profiles"][1]), {"profile_id", "display_name", "match", "capabilities", "newest_entry"})
        self.assertNotIn("newest_entry", bundle["profiles"][0])
        self.assertEqual(bundle["capability_keys"], ["mms", "volte", "vowifi"])
        self.assertEqual(bundle["source"]["commit"], COMMIT)
        self.assertEqual(bundle["source"]["stale_after"], "2027-01-10")

    def test_limits_text_comes_from_the_readme(self) -> None:
        limits = self.build(profile("open.1", {"mccmnc": ["26202"]}))["limits"]
        self.assertEqual(set(limits["value_meanings"]), {"supported", "conditional", "unsupported", "unknown"})
        self.assertTrue(limits["gates"].startswith("A carrier can still block"))
        self.assertEqual(limits["missing_switch"], "A switch that carrier-config-list.xml leaves out is not neutral.")
        self.assertEqual(
            limits["tables_not_tests"],
            "A capability value says what carrier tables configure, not what works. "
            "It does not say the operator still offers the service.",
        )

    def test_missing_limits_section_fails(self) -> None:
        with self.assertRaises(build_bundle.BundleError):
            build_bundle.build_bundle(lookup(profile("open.1", {"mccmnc": ["26202"]})), METADATA, "# x\n", COMMIT, "2026-10-10")

    def test_bad_capability_value_fails(self) -> None:
        bad = profile("open.1", {"mccmnc": ["26202"]})
        bad["capabilities"]["volte"] = "works"
        with self.assertRaises(build_bundle.BundleError):
            self.build(bad)

    def test_unknown_match_key_fails(self) -> None:
        with self.assertRaises(build_bundle.BundleError):
            self.build(profile("open.1", {"mccmnc": ["26202"], "plmn": ["x"]}))

    def test_encoding_is_deterministic(self) -> None:
        bundle = self.build(profile("open.1", {"mccmnc": ["26202"], "android_carrier_ids": [2397]}))
        first, second = build_bundle.encode(bundle), build_bundle.encode(bundle)
        self.assertEqual(first, second)
        self.assertEqual(json.loads(gzip.decompress(first)), bundle)


if __name__ == "__main__":
    unittest.main()
