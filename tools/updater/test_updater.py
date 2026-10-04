import hashlib
import importlib.util
import json
import re
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]


def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(filename))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


prepare = load("prepare", "prepare-source.py")
apk = load("apk", "apk_metadata.py")


class UpdaterTests(unittest.TestCase):
    def test_rebuild_30_preserves_code_and_backports_control_plane(self):
        with tempfile.TemporaryDirectory() as directory:
            dest = Path(directory) / "source"
            result = prepare.prepare(ROOT, "ecf8b56", dest)
            self.assertEqual("restore", result["kind"])
            for name in prepare.DATA_FILES:
                self.assertEqual((ROOT / name).read_bytes(), (dest / name).read_bytes())
            self.assertEqual((ROOT / "app/src/main/java/com/glance/update/UpdateChecker.kt").read_bytes(),
                             (dest / "app/src/main/java/com/glance/update/UpdateChecker.kt").read_bytes())
            old = prepare.git(ROOT, "show", "ecf8b56:app/src/main/java/com/glance/kiosk/KioskService.kt")
            self.assertEqual(old, (dest / "app/src/main/java/com/glance/kiosk/KioskService.kt").read_bytes())
            self.assertEqual(hashlib.sha256(b"".join((dest / p).read_bytes() for p in prepare.DATA_FILES)).hexdigest(), result["dataContract"])

    def test_incompatible_reader_or_migration_fails_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            subprocess.run(["git", "init", "-q", str(root)], check=True)
            for name in prepare.DATA_FILES:
                target = root / name
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_text("old format")
            subprocess.run(["git", "-C", str(root), "add", "."], check=True)
            subprocess.run(["git", "-C", str(root), "-c", "user.name=Test", "-c", "user.email=test@example.com",
                            "commit", "-qm", "old"], check=True)
            (root / prepare.DATA_FILES[0]).write_text("new incompatible migration")
            with self.assertRaisesRegex(ValueError, "Incompatible data"):
                prepare.prepare(root, "HEAD", root / "output")
            self.assertFalse((root / "output").exists())

    def test_legacy_descriptor_cannot_publish_recovery_on_normal_channel(self):
        legacy = {"versionCode": 1000201, "versionName": "restore30"}
        with patch.object(apk, "identity", return_value={"package": "com.glance", **legacy, "UPDATE_KIND": "restore"}):
            with self.assertRaisesRegex(ValueError, "operation differs"):
                apk.validate("unused", legacy)

    def test_built_apk_metadata_is_checked_not_just_build_json(self):
        output = ROOT / "app/build/outputs/apk/release"
        self.assertTrue((output / "output-metadata.json").exists(), "Run assembleRelease before this test")
        metadata = json.loads((output / "output-metadata.json").read_text())
        path = output / metadata["elements"][0]["outputFile"]
        self.assertTrue(path.exists(), "The current release artifact must exist")
        info = apk.identity(path)
        self.assertEqual("com.glance", info["package"])
        generated = (ROOT / "app/build/generated/source/buildConfig/release/com/glance/BuildConfig.java").read_text()
        self.assertEqual(info["versionCode"], int(re.search(r"VERSION_CODE = (\d+)", generated).group(1)))
        self.assertIn('CODE_IDENTITY = "' + info["CODE_IDENTITY"] + '"', generated)

        metadata = {"versionCode": info["versionCode"], "versionName": info["versionName"],
                    "codeIdentity": info["CODE_IDENTITY"], "dataContract": info["DATA_CONTRACT"], "kind": info["UPDATE_KIND"]}
        apk.validate(path, metadata)
        for key, value in [("versionCode", 123456), ("codeIdentity", "wrong"), ("dataContract", "wrong"), ("kind", "restore")]:
            with self.assertRaises(ValueError):
                apk.validate(path, dict(metadata, **{key: value}))


if __name__ == "__main__":
    unittest.main()
