#!/usr/bin/env python3
"""Summarise the result of InstagramLiveVerificationTest for the CI summary.

The point is to make the difference between "verified against the live post",
"could not be verified from this network" and "verification failed" impossible
to miss in the build output.
"""
import pathlib
import xml.etree.ElementTree as ET

TEST_CLASSES = (
    "com.otter.app.engine.InstagramLiveVerificationTest",
    "com.otter.app.engine.LiveImageDownloadTest",
)
RESULTS = pathlib.Path("app/build/test-results/testDebugUnitTest")


def main() -> str:
    lines = ["# Live Instagram verification", ""]
    reported = 0
    verdicts = []
    checks = []
    for test_class in TEST_CLASSES:
        target = RESULTS / f"TEST-{test_class}.xml"
        if not target.exists():
            candidates = list(RESULTS.glob(f"TEST-*{test_class.rsplit('.', 1)[-1]}*.xml"))
            target = candidates[0] if candidates else None
        if target is None:
            lines.append(f"- `{test_class}`: no result XML in this job")
            continue
        reported += 1
        root = ET.parse(target).getroot()
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
            verdicts.append((test_class, verdict))
            lines.append(f"- `{test_class.rsplit('.', 1)[-1]}.{name}`: **{verdict}**")
            for skip in skipped:
                lines.append(f"  - reason: {skip.attrib.get('message', 'skipped')}")
            for failure in failures:
                message = (failure.attrib.get("message", "") or "").splitlines()
                lines.append(f"  - failure: {message[0] if message else 'see logs'}")
                for detail in (failure.text or "").splitlines()[:8]:
                    lines.append(f"    - {detail.strip()}")
        output = root.find("system-out")
        if output is not None and output.text:
            checks += [line.strip() for line in output.text.splitlines() if "LIVE-CHECK" in line]

    if reported == 0:
        lines.append(
            "No test-result XML in this job (expected for release-only jobs). "
            "See the build job summary for the verification verdict."
        )
        return "\n".join(lines)

    if checks:
        lines += ["", "```", *checks[:80], "```"]

    states = [v for _, v in verdicts]
    carousel = [v for cls, v in verdicts if cls.endswith("InstagramLiveVerificationTest")]
    download = [v for cls, v in verdicts if cls.endswith("LiveImageDownloadTest")]
    if any(v == "FAILED" for v in states):
        lines += ["", ":warning: The live verification failed — see the failure output above."]
    elif states and all(v == "PASSED" for v in states):
        lines += ["", "The carousel was read from the live post and every selected slide " \
                      "was written to the download folder with a valid picture signature."]
    elif carousel and all(v == "NOT VERIFIED" for v in carousel) and download and \
            all(v == "PASSED" for v in download):
        lines += ["", ":warning: Instagram served no post payload to this runner (see the probe " \
                      "job), so the carousel read/save path is **not verified against the live " \
                      "post** here. A real HTTP download through the same save path did pass, " \
                      "which covers the \"photos do not appear in the folder\" half of the bug."]
    else:
        lines += ["", ":warning: The live post could not be read from this network, so the " \
                      "carousel fix is **not verified** in this run. See the probe job for the " \
                      "HTTP statuses Instagram returned."]
    return "\n".join(lines)


if __name__ == "__main__":
    print(main())
