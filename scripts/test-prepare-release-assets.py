#!/usr/bin/env python3
"""Exercise the publication contract without keys, a device, or network access."""

import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest


spec = importlib.util.spec_from_file_location(
    "release_assets", Path(__file__).with_name("prepare-release-assets.py"))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class ReleaseAssetsTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.apks = self.root / "apks"
        self.apks.mkdir()
        self.output = self.root / "assets"
        self.metadata = {
            "applicationId": "com.xatune.app",
            "elements": [
                {"versionCode": 32, "versionName": "1.1.9",
                 "filters": [{"filterType": "ABI", "value": abi}],
                 "outputFile": f"app-{abi}-release-unsigned.apk"}
                for abi in module.ABIS
            ],
        }
        for abi in module.ABIS:
            (self.apks / f"app-{abi}-release-signed.apk").write_bytes(abi.encode())

    def prepare(self, channel="release"):
        (self.apks / "output-metadata.json").write_text(json.dumps(self.metadata))
        return module.prepare(self.apks, self.output, channel,
                              "Kelejun/XATUHelper-Android", "abcdef0" + "1" * 33)

    def test_release_manifest_matches_all_packaged_files(self):
        outputs = self.prepare()
        self.assertEqual(outputs["tag"], "v1.1.9")
        manifest = json.loads((self.output / "update.json").read_text())
        self.assertEqual(manifest["channel"], "stable")
        self.assertEqual(manifest["versionCode"], 32)
        self.assertEqual(manifest["versionName"], "1.1.9")
        self.assertEqual(set(manifest["assets"]), set(module.ABIS))
        checksums = (self.output / "SHA256SUMS").read_text().splitlines()
        for abi, asset in manifest["assets"].items():
            self.assertEqual(asset["name"], f"XATune-v1.1.9-release-{abi}.apk")
            data = (self.output / asset["name"]).read_bytes()
            self.assertEqual(data, abi.encode())
            self.assertEqual(asset["size"], len(data))
            self.assertEqual(asset["sha256"], hashlib.sha256(data).hexdigest())
            self.assertIn(f"{asset['sha256']}  {asset['name']}", checksums)
            self.assertTrue(asset["url"].endswith(f"/v1.1.9/{asset['name']}"))
        self.assertIn("**对于大多数用户", (self.output / "release-notes.md").read_text())

    def test_prerelease_keeps_ci_names_and_rolling_tag(self):
        outputs = self.prepare("prerelease")
        self.assertEqual(outputs["tag"], "Prerelease")
        self.assertEqual(outputs["prefix"], "XATune-v1.1.9.r32.abcdef0")
        manifest = json.loads((self.output / "update.json").read_text())
        self.assertEqual(manifest["channel"], "prerelease")
        self.assertTrue(manifest["releaseUrl"].endswith("/tag/Prerelease"))

    def test_rejects_inconsistent_apk_versions(self):
        self.metadata["elements"][1]["versionCode"] = 31
        with self.assertRaisesRegex(ValueError, "same version"):
            self.prepare()
        self.assertFalse(self.output.exists())

    def test_rejects_missing_signed_apk(self):
        (self.apks / "app-x86-release-signed.apk").unlink()
        with self.assertRaisesRegex(ValueError, "Missing or empty"):
            self.prepare()

    def test_rejects_qa_package(self):
        self.metadata["applicationId"] += ".qa"
        with self.assertRaisesRegex(ValueError, "production application"):
            self.prepare()

    def test_rejects_duplicate_abi(self):
        self.metadata["elements"][1] = self.metadata["elements"][0]
        with self.assertRaisesRegex(ValueError, "duplicate ABI"):
            self.prepare()

    def test_rejects_universal_apk(self):
        self.metadata["elements"][0]["filters"] = []
        with self.assertRaisesRegex(ValueError, "universal APK"):
            self.prepare()

    def test_rejects_stale_output(self):
        self.output.mkdir()
        (self.output / "old.apk").write_bytes(b"old")
        with self.assertRaisesRegex(ValueError, "empty"):
            self.prepare()


if __name__ == "__main__":
    unittest.main()
