#!/usr/bin/env python3
"""Bump TobiBoard's version in app/build.gradle.kts and scaffold a changelog.

Usage: python3 tools/bump_version.py [patch|minor|major]

versionCode scheme: major*1000 + minor*100 + patch  (monotonic with semver
as long as minor/patch stay < 100). Matches 6.6.0->6600, 6.7.0->6700.
"""
import re
import sys
import pathlib

REPO = pathlib.Path(__file__).resolve().parent.parent
GRADLE = REPO / "app/build.gradle.kts"

part = sys.argv[1] if len(sys.argv) > 1 else "patch"
if part not in ("patch", "minor", "major"):
    sys.exit(f"unknown bump '{part}' (use patch|minor|major)")

text = GRADLE.read_text(encoding="utf-8")
m = re.search(r'versionName\s*=\s*"(\d+)\.(\d+)\.(\d+)"', text)
if not m:
    sys.exit("versionName = \"X.Y.Z\" not found in app/build.gradle.kts")
major, minor, patch = (int(x) for x in m.groups())

if part == "major":
    major, minor, patch = major + 1, 0, 0
elif part == "minor":
    minor, patch = minor + 1, 0
else:
    patch += 1

new_name = f"{major}.{minor}.{patch}"
new_code = major * 1000 + minor * 100 + patch

m_code = re.search(r'versionCode\s*=\s*(\d+)', text)
if not m_code:
    sys.exit("versionCode not found in app/build.gradle.kts")
old_code = int(m_code.group(1))
if new_code <= old_code:
    sys.exit(f"refusing: new versionCode {new_code} <= current {old_code}")

text = re.sub(r'(versionCode\s*=\s*)\d+', rf'\g<1>{new_code}', text, count=1)
text = re.sub(r'(versionName\s*=\s*")\d+\.\d+\.\d+(")', rf'\g<1>{new_name}\g<2>', text, count=1)
GRADLE.write_text(text, encoding="utf-8")

changelog = REPO / f"fastlane/metadata/android/en-US/changelogs/{new_code}.txt"
if not changelog.exists():
    changelog.write_text(f"* TobiBoard {new_name}\n", encoding="utf-8")

print(f"Bumped {m.group(0)} -> versionName \"{new_name}\", versionCode {new_code}")
print(f"Changelog stub: {changelog.relative_to(REPO)}  (edit before release)")
