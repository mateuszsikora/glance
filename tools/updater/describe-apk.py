#!/usr/bin/env python3
"""Describe the actual unsigned APK, not independently supplied version properties."""
import hashlib
import json
from pathlib import Path
import sys
from apk_metadata import identity

path = Path(sys.argv[1])
info = identity(path)
print(json.dumps({"versionCode": info["versionCode"], "versionName": info["versionName"],
                  "codeIdentity": info["CODE_IDENTITY"], "dataContract": info["DATA_CONTRACT"],
                  "kind": info["UPDATE_KIND"], "asset": path.name,
                  "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}, indent=2))
