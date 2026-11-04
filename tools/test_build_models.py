#!/usr/bin/env python3
"""Tests for build_models.py. Run: python3 -m unittest discover -s tools -p 'test_*.py'"""

from __future__ import annotations

import gzip
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import build_models  # noqa: E402


class Models(unittest.TestCase):
    def test_sorted_distinct_and_one_per_line(self) -> None:
        catalog = {"devices": [{"models": ["SM-G981B", "Pixel 8"]}, {"models": ["SM-G981B", "", "two\nlines"]}, {}]}
        self.assertEqual(["Pixel 8", "SM-G981B"], build_models.models(catalog))

    def test_a_catalog_without_devices_is_refused(self) -> None:
        with self.assertRaises(ValueError):
            build_models.models({})

    def test_the_same_list_packs_to_the_same_bytes(self) -> None:
        data = build_models.text(["A", "B"])
        self.assertEqual(build_models.packed(data), build_models.packed(data))
        self.assertEqual(b"A\nB\n", gzip.decompress(build_models.packed(data)))


if __name__ == "__main__":
    unittest.main()
