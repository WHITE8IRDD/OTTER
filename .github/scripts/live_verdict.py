#!/usr/bin/env python3
"""Summarise the result of InstagramLiveVerificationTest for the CI summary.

The point is to make the difference between "verified against the live post",
"could not be verified from this network" and "verification failed" impossible
to miss in the build output.
"""
import pathlib
import xml.etree.ElementTree as ET

TEST_CLASS = "com.otter.app.engine.InstagramLiveVerificationTest"
RESULTS = pathlib.Path("app/build/test-results/testDebugUnitTest")


def main() -> str:
    lines = ["# Live Instagram verification", ""]
    target = RESULTS / f"TEST-{TEST_CLASS}.xml"
    if not target.exists():
        candidates = list(RESULTS.glob("TEST-*InstagramLive*.xml"))
        target = candidates[0] if candidates else None
    if target is None:
        lines.append(
            "No test-result XML in this job (expected for release-only jobs). "
            "See the build job summary for the verification verdict."
        )
        return "\n".join(lines)

    root = ET.parse(target).getroot()
    verdicts = []
    for case in root.iter("testcase"):
        failures = list(case.findall("failure")) + list(case.findall("error"))
        skipped = list(case.findall("skipped"))
        name = case.attrib.get("name", "unknown")
        if failures:
            verdict = "FAILED"
        elif skipped:
            verdict = "NOT VERIFIED"
        else:
            verdict = "PASSED"
        verdicts.append(verdict)
        lines.append(f"- `{name}`: **{verdict}**")
        for skip in skipped:
            lines.append(f"  - reason: {skip.attrib.get('message', 'skipped')}")
        for failure in failures:
            message = (failure.attrib.get("message", "") or "").splitlines()
            lines.append(f"  - failure: {message[0] if message else 'see logs'}")
            for detail in (failure.text or "").splitlines()[:8]:
                lines.append(f"    - {detail.strip()}")

    output = root.find("system-out")
    if output is not None and output.text:
        checks = [line.strip() for line in output.text.splitlines() if "LIVE-CHECK" in line]
        if checks:
            lines += ["", "```", *checks[:80], "```"]

    if all(v == "PASSED" for v in verdicts):
        lines += ["", "The carousel was read from the live post and every selected slide " \
                      "was written to the download folder with a valid picture signature."]
    elif any(v == "FAILED" for v in verdicts):
        lines += ["", ":warning: The live verification failed — see the failure output above."]
    else:
        lines += ["", ":warning: The live post could not be read from this network, so the " \
                      "carousel fix is **not verified** in this run. See the probe job for the " \
                      "HTTP statuses Instagram returned."]
    return "\n".join(lines)


if __name__ == "__main__":
    print(main())
