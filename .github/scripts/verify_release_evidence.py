#!/usr/bin/env python3
"""Fail closed: a successful APK build is not end-to-end media verification."""
import argparse
import hashlib
import json
import pathlib
import subprocess

SCENARIOS = (
    "instagram_single_image", "instagram_single_video", "instagram_image_carousel",
    "instagram_video_carousel", "instagram_mixed_carousel", "no_thumbnail_substitution",
    "youtube_shorts", "youtube_long_form", "tiktok_video", "valid_outputs",
    "failure_recovery", "picker_responsiveness", "existing_functionality",
)


def source_digest():
    paths = subprocess.check_output([
        "git", "ls-files", "app", "gradle", "build.gradle.kts", "settings.gradle.kts", "gradle.properties"
    ], text=True).splitlines()
    digest = hashlib.sha256()
    for name in sorted(paths):
        digest.update(name.encode() + b"\0" + pathlib.Path(name).read_bytes() + b"\0")
    return digest.hexdigest()


def verify(report, apk):
    errors = []
    if report.get("release") != "Outter 28":
        errors.append("release must be Outter 28")
    if report.get("source_sha256") != source_digest():
        errors.append("verification does not match the current application sources")
    if not report.get("tested_by") or not report.get("device"):
        errors.append("device and tester sign-off are required")
    if report.get("apk_sha256") != hashlib.sha256(apk.read_bytes()).hexdigest():
        errors.append("the APK is not the exact APK verified by the tester")
    for scenario in SCENARIOS:
        result = report.get("scenarios", {}).get(scenario, {})
        if result.get("status") != "passed" or not result.get("evidence"):
            errors.append(f"{scenario}: no successful verification evidence")
    return errors


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--source-digest", action="store_true")
    parser.add_argument("--report", default="docs/releases/outter-28-verification.json")
    parser.add_argument("--apk", type=pathlib.Path)
    args = parser.parse_args()
    if args.source_digest:
        print(source_digest())
    else:
        if not args.apk:
            parser.error("--apk is required")
        errors = verify(json.loads(pathlib.Path(args.report).read_text()), args.apk)
        if errors:
            raise SystemExit("Release blocked:\n- " + "\n- ".join(errors))
        print("All Outter 28 verification evidence is present and matches this APK/source.")
