#!/usr/bin/env python3
"""Rebuild old application code with the current recovery control plane. No signing or publishing."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess

DATA_FILES = ["app/src/main/java/com/glance/" + p for p in
              ("config/AppConfig.kt", "config/SecretStore.kt", "content/ContentProfile.kt", "kiosk/LockTaskHelper.kt")]
# Keep the mechanism that permits subsequent updates and explicit resume. Never patch an APK.
CONTROL_FILES = ["app/src/main/java/com/glance/update", "app/src/main/java/com/glance/remote",
                 "app/src/main/java/com/glance/GlanceApp.kt", "app/src/main/AndroidManifest.xml",
                 "app/build.gradle.kts", "build.gradle.kts", "settings.gradle.kts", "gradle", "gradlew",
                 "app/src/main/java/com/glance/settings/DebugInfoProvider.kt",
                 "app/src/main/java/com/glance/settings/DeviceDebugInfo.kt",
                 "app/src/test/java/com/glance/update"]
# Keep the archived UI tests: current cross-surface diagnostics tests assume current tablet UI,
# while recovery intentionally retains the older SettingsActivity. The main checkout tests the
# backported HTTP handler (including recovery auth/CSRF); the recovery tree tests the older UI.


def git(root, *args):
    return subprocess.check_output(["git", "-C", str(root), *args])


def prepare(root, ref, destination):
    commit = git(root, "rev-parse", "--verify", ref + "^{commit}").decode().strip()
    # Same serializers, PIN hashing, Keystore alias/AAD and migrations. No inferred downgrade safety.
    for name in DATA_FILES:
        if git(root, "show", f"{commit}:{name}") != (root / name).read_bytes():
            raise ValueError(f"Incompatible data/migrations: {name}; backport and review before rebuilding")
    if destination.exists():
        raise ValueError("Destination must not exist")
    destination.mkdir(parents=True)
    archive = subprocess.Popen(["git", "-C", str(root), "archive", commit], stdout=subprocess.PIPE)
    subprocess.run(["tar", "-x", "-C", str(destination)], stdin=archive.stdout, check=True)
    archive.stdout.close()
    if archive.wait() != 0:
        raise RuntimeError("git archive failed")
    for name in CONTROL_FILES:
        source, target = root / name, destination / name
        if source.is_dir():
            if target.exists():
                shutil.rmtree(target)
            shutil.copytree(source, target, ignore=shutil.ignore_patterns("__pycache__"))
        else:
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, target)
    contract = hashlib.sha256(b"".join((root / p).read_bytes() for p in DATA_FILES)).hexdigest()
    metadata = {"codeIdentity": commit, "dataContract": contract, "kind": "restore"}
    (destination / "recovery-source.json").write_text(json.dumps(metadata, indent=2) + "\n")
    return metadata


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("ref")
    parser.add_argument("destination", type=Path)
    args = parser.parse_args()
    print(json.dumps(prepare(Path(__file__).resolve().parents[2], args.ref, args.destination.resolve())))
