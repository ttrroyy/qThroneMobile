"""Publish APK-only releases; keep one legacy release as a bridge to the new updater."""
import json
import os
import subprocess
from pathlib import Path
from tempfile import TemporaryDirectory

from release_assets import ABIS, release_notes, sha256


def gh(*args, check=True):
    return subprocess.run(["gh", *map(str, args)], check=check, capture_output=True, text=True)


def validate_assets(dist, manifest):
    if manifest["packageName"] != "app.qthrone.mobile" or manifest["channel"] != "stable":
        raise ValueError("Unexpected release package or channel")
    if set(manifest["assets"]) != set(ABIS + ["universal"]):
        raise ValueError("Missing APK architecture")
    names = {entry["name"] for entry in manifest["assets"].values()}
    if names != {p.name for p in dist.glob("*.apk")}:
        raise ValueError("Release APKs differ from verified metadata")
    for entry in manifest["assets"].values():
        name = entry["name"]
        if Path(name).name != name or "/" in name or "\\" in name or not name.endswith(".apk"):
            raise ValueError("Unsafe APK name")
        apk = dist / name
        if apk.stat().st_size != entry["size"] or sha256(apk) != entry["sha256"]:
            raise ValueError("APK changed after verification: " + name)
    return sorted(dist / name for name in names)


def validate_bridge(previous, manifest):
    for key in ("packageName", "signerSha256"):
        if previous[key] != manifest[key]:
            raise ValueError("Legacy update would change " + key)
    if previous["versionCode"] > manifest["versionCode"]:
        raise ValueError("Legacy update would downgrade the APK")
    if previous["versionCode"] == manifest["versionCode"] and previous != manifest:
        raise ValueError("Different APKs have the same versionCode")


def publish(dist, notes, tag, target, title):
    manifest = json.loads((dist / "qthrone-update.json").read_text(encoding="utf-8"))
    apks = validate_assets(dist, manifest)
    with TemporaryDirectory(prefix="qthrone-release-") as directory:
        directory = Path(directory)
        body = directory / "notes.md"
        body.write_text(release_notes(notes, manifest), encoding="utf-8", newline="\n")
        # Old updaters resolve APKs inside the release that holds their JSON.
        # shortcut: old clients scan 20 releases; retain this bridge while migrating them.
        bridge = manifest["versionName"] == "2.4.2"
        if bridge:
            gh("release", "download", "v2.4.1", "--pattern", "qthrone-update.json", "--dir", directory)
            previous = json.loads((directory / "qthrone-update.json").read_text(encoding="utf-8"))
            validate_bridge(previous, manifest)

        existing = gh("release", "view", tag, "--json", "isDraft,targetCommitish", check=False)
        if existing.returncode == 0:
            draft = json.loads(existing.stdout)
            if not draft["isDraft"] or draft["targetCommitish"] != target:
                raise ValueError("Refusing to overwrite an existing release")
        elif "release not found" in existing.stderr.lower() or "404" in existing.stderr:
            gh("release", "create", tag, "--draft", "--target", target, "--title", title, "--notes-file", body)
        else:
            raise RuntimeError(existing.stderr)
        gh("release", "upload", tag, *apks, "--clobber")

        if bridge:
            # Publish the manifest last: clients must never see metadata before its APKs exist.
            gh("release", "upload", "v2.4.1", *apks, "--clobber")
            gh("release", "upload", "v2.4.1", dist / "qthrone-update.json", "--clobber")
        flags = ["--prerelease", "--latest=false"] if "-beta" in tag or "-rc" in tag else ["--latest"]
        gh("release", "edit", tag, "--title", title, "--notes-file", body, "--draft=false", *flags)


if __name__ == "__main__":
    publish(Path("dist"), Path("RELEASE_NOTES.md").read_text(encoding="utf-8"),
            os.environ["TAG"], os.environ["TARGET"], os.environ["RELEASE_NAME"])
