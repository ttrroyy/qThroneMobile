import json
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from release_assets import ABIS, embedded_manifest, release_notes, sha256
from release_assets import core_ref
from publish_release import publish, validate_assets, validate_bridge


class ReleaseMetadataTest(unittest.TestCase):
    def test_core_metadata_uses_the_aar_pin(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            metadata = root / "nb4a.properties"
            with patch("release_assets.ROOT", root):
                metadata.write_text("THRONE_CORE_REF=transports\n")
                self.assertEqual(core_ref(), "transports")
                metadata.write_text("THRONE_CORE_REF=transports\nTHRONE_AAR_REF=engine\n")
                self.assertEqual(core_ref(), "engine")

    def manifest(self, directory):
        assets = {}
        for abi in ABIS + ["universal"]:
            apk = directory / ("RawlineMobile-2.4.2-" + abi + ".apk")
            apk.write_bytes(("signed APK " + abi).encode())
            assets[abi] = dict(name=apk.name, size=apk.stat().st_size, sha256=sha256(apk))
        return dict(schema=1, packageName="app.rawline.mobile", channel="stable",
                    versionName="2.4.2", versionCode=69999, signerSha256="original-key", assets=assets)

    def test_hidden_metadata_roundtrip_and_rejections(self):
        manifest = dict(versionCode=69999, name="contains --> text")
        body = release_notes("Human release notes", manifest)
        self.assertEqual(embedded_manifest(body), manifest)
        self.assertEqual(body.count("-->"), 1)
        self.assertIsNone(embedded_manifest("Legacy notes"))
        for broken in (body + body, body.removesuffix("\n").removesuffix("\n-->"), "<!-- rawline-update\n" + " " * 262145 + "\n-->"):
            with self.assertRaises((ValueError, json.JSONDecodeError)):
                embedded_manifest(broken)

    def test_bridge_cannot_change_signature_or_downgrade(self):
        current = dict(packageName="app.rawline.mobile", signerSha256="key", versionCode=69999)
        validate_bridge(dict(current, versionCode=68999), current)
        validate_bridge(current, current)
        for previous in (dict(current, signerSha256="other"), dict(current, packageName="other"), dict(current, versionCode=70999)):
            with self.assertRaises(ValueError):
                validate_bridge(previous, current)

    def test_beta_keeps_installable_metadata_and_prerelease_flags(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            manifest = dict(self.manifest(directory), versionName="3.0.0-beta.1", versionCode=89001)
            (directory / "rawline-update.json").write_text(json.dumps(manifest), encoding="utf-8")
            calls = []

            def gh(*args, **kwargs):
                calls.append(args)
                if args[:2] == ("release", "view"):
                    return subprocess.CompletedProcess(args, 1, "", "release not found")
                if "--notes-file" in args:
                    body = Path(args[args.index("--notes-file") + 1]).read_bytes().decode("utf-8")
                    self.assertIn("<!-- rawline-update\n", body)
                    self.assertEqual(embedded_manifest(body), manifest)
                return subprocess.CompletedProcess(args, 0, "", "")

            with patch("publish_release.gh", side_effect=gh):
                publish(directory, "Beta notes", "v3.0.0-beta.1", "dev-commit", "3.0 beta 1")
            self.assertEqual(calls[-1][:3], ("release", "edit", "v3.0.0-beta.1"))
            self.assertIn("--prerelease", calls[-1])
            self.assertIn("--latest=false", calls[-1])

    def test_only_apks_are_published_and_bridge_metadata_is_uploaded_last(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            manifest = self.manifest(directory)
            (directory / "rawline-update.json").write_text(json.dumps(manifest), encoding="utf-8")
            (directory / "SHA256SUMS").write_text("private CI checksums", encoding="utf-8")
            calls = []

            def gh(*args, **kwargs):
                calls.append(args)
                if args[:2] == ("release", "download"):
                    Path(args[-1], "rawline-update.json").write_text(json.dumps(dict(manifest, versionCode=68999)), encoding="utf-8")
                if args[:2] == ("release", "view"):
                    return subprocess.CompletedProcess(args, 1, "", "release not found")
                if args[:2] == ("release", "edit"):
                    body = Path(args[args.index("--notes-file") + 1]).read_bytes().decode("utf-8")
                    self.assertNotIn("\r", body, "Installed updaters require LF metadata markers")
                    self.assertEqual(embedded_manifest(body), manifest)
                return subprocess.CompletedProcess(args, 0, "", "")

            with patch("publish_release.gh", side_effect=gh):
                publish(directory, "Notes", "v2.4.2", "commit", "2.4.2")
            uploads = [c for c in calls if c[:2] == ("release", "upload")]
            self.assertEqual([c[2] for c in uploads], ["v2.4.2", "v2.4.1", "v2.4.1"])
            self.assertTrue(all(str(p).endswith(".apk") for p in uploads[0][3:-1]))
            self.assertEqual(Path(uploads[-1][3]).name, "rawline-update.json")
            self.assertEqual(calls[-1][:3], ("release", "edit", "v2.4.2"))
            next(iter(directory.glob("*.apk"))).write_bytes(b"tampered")
            with self.assertRaises(ValueError):
                validate_assets(directory, manifest)


if __name__ == "__main__":
    unittest.main()
