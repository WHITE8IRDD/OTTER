#!/usr/bin/env python3
"""Expose small, useful test counts even when CI log/artifact downloads are restricted."""
import pathlib
import sys
import xml.etree.ElementTree as ET

tests = failures = errors = skipped = 0
for name in sys.argv[1:]:
    for path in pathlib.Path(name).rglob('TEST-*.xml'):
        root = ET.parse(path).getroot()
        tests += int(root.get('tests', 0))
        failures += int(root.get('failures', 0))
        errors += int(root.get('errors', 0))
        skipped += int(root.get('skipped', 0))
print(f'::notice title=Test totals::{tests} tests; {failures} failures; {errors} errors; {skipped} skipped')
if not tests or failures or errors or skipped:
    raise SystemExit('Tests missing, failing or skipped: not verification-passing')
