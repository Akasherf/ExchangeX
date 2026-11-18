#!/usr/bin/env python3
"""Enforce non-skipped PostgreSQL integration suite, not just Maven exit status."""
from pathlib import Path
from xml.etree import ElementTree as ET
import sys

MIN_TESTS = 98  # Sprint 5: 92; Sprint 6: +1; Sprint 7A: +5 funded-trading checks
roots = (Path('matching-engine/target/surefire-reports'), Path('exchange-api/target/surefire-reports'))
files = [p for root in roots for p in root.glob('TEST-*.xml')]
if not files:
    sys.exit('FAIL: no Maven Surefire XML reports found')
tests = fails = errors = skips = 0
for file in files:
    suite = ET.parse(file).getroot()
    tests += int(suite.get('tests', 0))
    fails += int(suite.get('failures', 0))
    errors += int(suite.get('errors', 0))
    skips += int(suite.get('skipped', 0))
print(f'Surefire: {tests} total, {fails} failures, {errors} errors, {skips} skipped')
if tests < MIN_TESTS or fails or errors or skips:
    sys.exit(f'FAIL: require >={MIN_TESTS} executed tests, with zero failures/errors/skips')
print('PASS: full non-skipped regression suite')
