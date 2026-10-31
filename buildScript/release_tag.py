"""Normalize the public tag and reject releases older than a published version."""
import json
import os
import re
import urllib.request
from pathlib import Path
from release_assets import embedded_manifest


def version_key(value):
    match = re.fullmatch(r"v?(\d+)\.(\d+)(?:\.(\d+))?(?:-(beta|rc)\.?(\d+))?", value)
    if not match:
        raise ValueError("Unsupported release version: " + value)
    major, minor, patch, stage, number = match.groups()
    return (int(major), int(minor), int(patch or 0), {"beta": 0, "rc": 1, None: 2}[stage], int(number or 0))


def release_build_number(version):
    key = version_key(version)
    if key[3] == 2:
        return 999
    if not 1 <= key[4] <= 499:
        raise ValueError("Prerelease number must be 1..499")
    return key[4] + (499 if key[3] == 1 else 0)


def validate_release_code(code, version, previous, tag):
    # A failed 2.4.2 publication may already have updated its old-client bridge.
    resuming_bridge = (tag == "v2.4.1" and version == "2.4.2" and
                       previous.get("versionName") == version and code == previous["versionCode"])
    if code <= previous["versionCode"] and not resuming_bridge:
        raise ValueError("Increase VERSION_CODE: APK code must exceed " + str(previous["versionCode"]) + " from " + tag)


if __name__ == "__main__":
    version = next(line.split("=", 1)[1].strip() for line in Path("nb4a.properties").read_text().splitlines() if line.startswith("VERSION_NAME="))
    key = version_key(version)
    build = release_build_number(version)
    index = int(next(line.split("=", 1)[1] for line in Path("nb4a.properties").read_text().splitlines() if line.startswith("VERSION_CODE=")))
    code = index * 1000 + build
    stage = {0: "beta", 1: "rc"}.get(key[3])
    # GitHub orders same-day beta.10 below beta.9. The release title and APK
    # retain the dotted version; compact multi-digit tags sort above legacy tags.
    tag = f"v{key[0]}.{key[1]}.{key[2]}" + (f"-{stage}{'.' if key[4] < 10 else ''}{key[4]}" if stage else "")
    if os.environ.get("PUBLISH") == "true":
        req = urllib.request.Request("https://api.github.com/repos/" + os.environ["GITHUB_REPOSITORY"] + "/releases?per_page=100", headers={"Authorization": "Bearer " + os.environ["GH_TOKEN"], "User-Agent": "qThrone-release"})
        with urllib.request.urlopen(req, timeout=30) as response:
            releases = json.load(response)
        for release in releases:
            if release["draft"] or "dev" in release["tag_name"].lower():
                continue
            if key <= version_key(release["tag_name"]):
                raise SystemExit("Increase VERSION_NAME above published release " + release["tag_name"])
            previous = embedded_manifest(release.get("body") or "")
            asset = next((a for a in release.get("assets", []) if a["name"] == "qthrone-update.json"), None)
            if previous is None and asset:
                with urllib.request.urlopen(asset["browser_download_url"], timeout=30) as response:
                    previous = json.load(response)
            if previous is not None:
                validate_release_code(code, version, previous, release["tag_name"])
    with open(os.environ["GITHUB_OUTPUT"], "a") as output:
        output.write(f"tag={tag}\nname={version}\nbuild={build}\n")
