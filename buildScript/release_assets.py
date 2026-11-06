#!/usr/bin/env python3
"""Collects the per-ABI release APKs of one build into a directory ready for a GitHub release: the APKs, SHA256SUMS and
rawline-update.json, the manifest the in-app updater reads (io.nekohasekai.sagernet.update.UpdateChecker).

Usage: release_assets.py <apk-dir> <dist-dir> <stable|preview>

<apk-dir> is AGP's output directory (app/build/outputs/apk/oss/release) with output-metadata.json. Every APK must be
signed by exactly one key and must agree with the metadata, the others and the expected ABI list.
"""

import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

ABIS = ["arm64-v8a", "armeabi-v7a", "x86_64"]
ROOT = Path(__file__).resolve().parent.parent
MANIFEST_START = "<!-- rawline-update\n"
MANIFEST_END = "\n-->"


def embedded_manifest(body):
    start = body.find(MANIFEST_START)
    if start < 0:
        return None
    if body.find(MANIFEST_START, start + len(MANIFEST_START)) >= 0:
        raise ValueError("Duplicate update metadata")
    content = start + len(MANIFEST_START)
    end = body.find(MANIFEST_END, content)
    if end < content or end - content > 256 * 1024:
        raise ValueError("Invalid update metadata")
    return json.loads(body[content:end])


def release_notes(notes, manifest):
    if MANIFEST_START in notes:
        raise ValueError("Release notes already contain update metadata")
    payload = json.dumps(manifest, separators=(",", ":")).replace("-->", "--\\u003e")
    return notes.rstrip() + "\n\n" + MANIFEST_START + payload + MANIFEST_END + "\n"


def fail(message):
    print(f"::error::{message}")
    sys.exit(1)


def pinned_build_tools():
    """The buildToolsVersion the Gradle build uses (buildSrc Helpers.kt): the runner may carry newer ones."""
    found = re.search(r'buildToolsVersion = "([^"]+)"', (ROOT / "buildSrc/src/main/kotlin/Helpers.kt").read_text())
    return found.group(1) if found else None


def build_tool(name):
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT") or fail("ANDROID_HOME is not set")
    tools = []
    for d in Path(sdk, "build-tools").iterdir():
        tool = next((d / f for f in (name, name + ".exe", name + ".bat") if (d / f).exists()), None)
        if tool:
            tools.append(([int(n) for n in re.findall(r"\d+", d.name)], d.name, tool))
    if not tools:
        fail(f"{name} not found under {sdk}/build-tools")
    pinned = pinned_build_tools()
    return str(next((t for _, v, t in tools if v == pinned), None) or max(tools)[2])


def run(*args, merge_stderr=False):
    result = subprocess.run(args, capture_output=True, text=True)
    if result.returncode != 0:
        fail(f"{' '.join(args[:3])} ... failed: {result.stderr.strip() or result.stdout.strip()}")
    return result.stdout + result.stderr if merge_stderr else result.stdout


def core_ref():
    properties = dict(line.split("=", 1) for line in (ROOT / "nb4a.properties").read_text().splitlines() if "=" in line)
    ref = properties.get("THRONE_AAR_REF", properties.get("THRONE_CORE_REF", "")).strip()
    if ref:
        return ref
    fail("THRONE_CORE_REF missing in nb4a.properties")


def signer_sha256(apksigner, apk):
    out = run(apksigner, "verify", "--print-certs", str(apk), merge_stderr=True)
    # "Signer #1 …" or, with a v3.1 rotation target, "Signer (minSdkVersion=…) …"; source stamps are not signers.
    digests = {d.lower() for d in re.findall(r"^Signer\b.*? certificate SHA-256 digest: ([0-9a-fA-F]{64})\s*$", out, re.M)}
    if len(digests) != 1:
        print(out.strip())
        fail(f"{apk.name}: expected exactly one signer, apksigner printed {len(digests)}")
    return digests.pop()


def badging(aapt2, apk):
    out = run(aapt2, "dump", "badging", str(apk))
    package = re.search(r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']*)'", out, re.M)
    min_sdk = re.search(r"^(?:minSdkVersion|sdkVersion):'(\d+)'", out, re.M)
    native = re.search(r"^native-code: (.*)$", out, re.M)
    if not package or not min_sdk:
        fail(f"{apk.name}: cannot read the package line of aapt2 dump badging")
    return {
        "packageName": package.group(1),
        "versionCode": int(package.group(2)),
        "versionName": package.group(3),
        "minSdk": int(min_sdk.group(1)),
        "abis": re.findall(r"'([^']+)'", native.group(1)) if native else [],
    }


def sha256(path):
    digest = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main():
    if len(sys.argv) != 4 or sys.argv[3] not in ("stable", "preview"):
        fail("usage: release_assets.py <apk-dir> <dist-dir> <stable|preview>")
    apk_dir, dist, channel = Path(sys.argv[1]), Path(sys.argv[2]), sys.argv[3]
    metadata = json.loads((apk_dir / "output-metadata.json").read_text())
    apksigner, aapt2 = build_tool("apksigner"), build_tool("aapt2")

    by_abi = {}
    for element in metadata["elements"]:
        abi = next((f["value"] for f in element.get("filters", []) if f["filterType"] == "ABI"), None)
        by_abi[abi or "universal"] = element
    if sorted(by_abi) != sorted(ABIS+["universal"]):
        fail(f"expected APKs for {ABIS}, output-metadata.json lists {sorted(by_abi)}")

    dist.mkdir(parents=True, exist_ok=True)
    manifest = None
    assets = {}
    for abi in ABIS+["universal"]:
        element = by_abi[abi]
        apk = apk_dir / element["outputFile"]
        if apk.name.endswith("-unsigned.apk"):
            fail(f"{apk.name} is unsigned")
        if not apk.exists():
            fail(f"{apk} is missing")

        info = badging(aapt2, apk)
        signer = signer_sha256(apksigner, apk)
        if info["versionCode"] != element["versionCode"] or info["versionName"] != element["versionName"]:
            fail(f"{apk.name}: version {info['versionName']} ({info['versionCode']}) differs from output-metadata.json")
        if info["packageName"] != metadata["applicationId"]:
            fail(f"{apk.name}: package {info['packageName']} differs from {metadata['applicationId']}")
        expected_abis=ABIS if abi=="universal" else [abi]
        if sorted(info["abis"]) != sorted(expected_abis):
            fail(f"{apk.name}: native code {info['abis']}, expected {expected_abis}")
        if ("-pre." in info["versionName"]) != (channel == "preview"):
            fail(f"{apk.name}: versionName {info['versionName']} does not match the {channel} channel")

        common = {
            "packageName": info["packageName"],
            "versionName": info["versionName"],
            "versionCode": info["versionCode"],
            "minSdk": info["minSdk"],
            "signerSha256": signer,
        }
        if manifest is None:
            manifest = common
        elif manifest != common:
            fail(f"{apk.name} differs from the other APKs: {common} != {manifest}")

        shutil.copy2(apk, dist / apk.name)
        assets[abi] = {"name": apk.name, "size": apk.stat().st_size, "sha256": sha256(apk)}

    (dist / "SHA256SUMS").write_text("".join(f"{a['sha256']}  {a['name']}\n" for a in assets.values()), newline="\n")
    # rawline-update.json is the in-app updater manifest; only the stable channel publishes the Releases the
    # updater reads, so preview artifacts carry the APKs and SHA256SUMS only.
    if channel != "stable":
        print(f"channel {channel}: skipped rawline-update.json")
        return
    update = {
        "schema": 1,
        "packageName": manifest["packageName"],
        "channel": channel,
        "versionName": manifest["versionName"],
        "versionCode": manifest["versionCode"],
        "coreRef": core_ref(),
        "minSdk": manifest["minSdk"],
        "signerSha256": manifest["signerSha256"],
        "assets": assets,
    }
    (dist / "rawline-update.json").write_text(json.dumps(update, indent=2) + "\n", newline="\n")
    print(json.dumps(update, indent=2))


if __name__ == "__main__":
    main()
