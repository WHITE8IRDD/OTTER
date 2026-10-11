#!/usr/bin/env python3
"""Summarise the Instagram probe dumps: statuses, payload markers, CDN URLs and
short windows around the interesting JSON keys. The result is short enough to be
published as a commit comment (CI job logs are not always retrievable)."""
import pathlib
import re
import sys

MARKERS = [
    "xdt_api__v1__media__shortcode__web_info",
    "xdt_shortcode_media",
    "edge_sidecar_to_children",
    "carousel_media",
    "image_versions2",
    "display_url",
    "candidates",
    "og:image",
    "loginForm",
    "accounts/login",
    "media_type",
]
URL_RE = re.compile(r"https?://[A-Za-z0-9._-]*(?:cdninstagram\.com|fbcdn\.net)[^\"'<>\s\\]*")


def window(text: str, needle: str, size: int = 1200) -> str:
    index = text.find(needle)
    if index < 0:
        return f"(no {needle})"
    start = max(0, index - 60)
    return text[start:index + size]


def main() -> str:
    lines = ["# Instagram probe summary"]
    status = pathlib.Path("probe_status.txt")
    if status.exists():
        lines.append("\n## responses")
        lines.append("```")
        lines.append(status.read_text(errors="replace").strip())
        lines.append("```")

    for path in sorted(pathlib.Path(".").glob("probe_*.txt")):
        text = path.read_text(errors="replace")
        lines.append(f"\n## {path.name}  size={len(text)}")
        lines.append("markers: " + ", ".join(f"{m}={text.count(m)}" for m in MARKERS))
        urls = []
        seen_paths = set()
        for match in URL_RE.finditer(text):
            url = match.group(0).replace("\\u0026", "&").replace("&amp;", "&")
            key = url.split("?")[0]
            if key in seen_paths:
                continue
            seen_paths.add(key)
            urls.append(url)
        lines.append(f"distinct cdn urls: {len(urls)} (with query: {sum('?' in u for u in urls)})")
        for url in urls[:8]:
            lines.append(f"  - {url[:200]}")
        if "xdt_api__v1__media__shortcode__web_info" in text:
            lines.append("payload window:")
            lines.append("```json")
            lines.append(window(text, "xdt_api__v1__media__shortcode__web_info"))
            lines.append("```")
        elif "carousel_media" in text:
            lines.append("carousel window:")
            lines.append("```json")
            lines.append(window(text, "carousel_media"))
            lines.append("```")
    return "\n".join(lines)


if __name__ == "__main__":
    sys.stdout.write(main())
