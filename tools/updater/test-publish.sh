#!/usr/bin/env bash
# Run inside the updater image with this repository mounted read-only at /repo.
# Uses only disposable keys and /tmp output; never serves, publishes a release or contacts a tablet.
# The dynamically sourced publisher consumes the fixture globals below.
# shellcheck disable=SC1090,SC2034
set -euo pipefail
: "${INITIAL_APK:?}" "${RECOVERY_APK:?}" "${NEXT_APK:?}"
export GLANCE_REPO=unused GLANCE_PUBLIC_URL=https://example.invalid
export GLANCE_KEYS_DIR=/tmp/publish-test/keys GLANCE_OUT=/tmp/publish-test/out
export GLANCE_RECOVERY_DIR=/tmp/publish-test/recovery
source <(sed '/^mapfile -t TARGETS/,$d' /usr/local/bin/glance-updater)
mkdir -p "$GLANCE_KEYS_DIR/test" "$GLANCE_OUT/test" "$GLANCE_RECOVERY_DIR"
keytool -genkeypair -keystore "$GLANCE_KEYS_DIR/test/keystore" -alias test -keyalg RSA \
  -storepass testing-only -keypass testing-only -dname CN=DisposableTest -validity 1 >/dev/null 2>&1
printf '%s\n' test > "$GLANCE_KEYS_DIR/test/alias"
printf '%s\n' testing-only > "$GLANCE_KEYS_DIR/test/password"
TARGETS=(test)

publish_apk() {
  local apk="$1" metadata version name
  metadata="$(python3 /repo/tools/updater/describe-apk.py "$apk")"
  BUILD_KIND="$(jq -r .kind <<<"$metadata")"
  CODE_IDENTITY="$(jq -r .codeIdentity <<<"$metadata")"
  DATA_CONTRACT="$(jq -r .dataContract <<<"$metadata")"
  version="$(jq -r .versionCode <<<"$metadata")"
  name="$(jq -r .versionName <<<"$metadata")"
  load_target test
  verify_key
  if target_is_current "$version"; then echo "Expected increasing installation number"; exit 1; fi
  publish "$version" "$name" "$apk"
  target_is_current "$version"
  "${APKSIGNER[@]}" verify "$T_OUT/glance-$version.apk"
}

publish_apk "$INITIAL_APK"
initial_manifest="$(cat "$T_MANIFEST")"
cp "$RECOVERY_APK" "$GLANCE_RECOVERY_DIR/glance-unsigned.apk"
python3 /repo/tools/updater/describe-apk.py "$GLANCE_RECOVERY_DIR/glance-unsigned.apk" > "$GLANCE_RECOVERY_DIR/build.json"
# Invalid download hash cannot create a recovery manifest.
printf corrupt >> "$GLANCE_RECOVERY_DIR/glance-unsigned.apk"
if check_recovery; then echo "Accepted corrupt APK"; exit 1; fi
[[ ! -e "$GLANCE_OUT/test/glance-restore.json" ]]
cp "$RECOVERY_APK" "$GLANCE_RECOVERY_DIR/glance-unsigned.apk"
check_recovery
[[ "$(cat "$GLANCE_OUT/test/glance-update.json")" == "$initial_manifest" ]]
restore_manifest="$(cat "$GLANCE_OUT/test/glance-restore.json")"
# Recovery and normal updates share the high-water mark even after state-file loss.
last="$(cat "$T_STATE")"
printf 0 > "$T_STATE"
target_is_current "$last"
# A signer mismatch must not publish a new artifact/manifest.
T_EXPECT=wrong-certificate
if publish "$((last + 1))" wrong "$RECOVERY_APK"; then echo "Accepted wrong signer"; exit 1; fi
[[ "$(cat "$GLANCE_OUT/test/glance-restore.json")" == "$restore_manifest" ]]
publish_apk "$NEXT_APK"
[[ "$(cat "$GLANCE_OUT/test/glance-restore.json")" == "$restore_manifest" ]]
[[ "$(cat "$GLANCE_OUT/test/glance-update.json")" != "$initial_manifest" ]]
echo "PASS: normal → restore → later normal, corrupt hash, wrong signer, separate channels, durable numbering"

# A key can pass startup verification and then fail. It must not starve later targets.
TARGETS=(broken healthy)
for name in "${TARGETS[@]}"; do
  cp -R "$GLANCE_KEYS_DIR/test" "$GLANCE_KEYS_DIR/$name"
  mkdir -p "$GLANCE_OUT/$name"
  load_target "$name"
  verify_key
done
printf 'damaged after startup' > "$GLANCE_KEYS_DIR/broken/keystore"
for poll in 1 2; do
  if check_recovery; then echo "Expected failed-target status on poll $poll"; exit 1; fi
  [[ ! -e "$GLANCE_OUT/broken/glance-restore.json" ]]
  [[ ! -e "$GLANCE_OUT/broken/.last-signed" ]]
  [[ -s "$GLANCE_OUT/healthy/glance-restore.json" ]]
  version="$(jq -r .versionCode "$GLANCE_RECOVERY_DIR/build.json")"
  [[ "$(cat "$GLANCE_OUT/healthy/.last-signed")" == "$version" ]]
  "${APKSIGNER[@]}" verify "$GLANCE_OUT/healthy/glance-$version.apk"
  if [[ "$poll" == 1 ]]; then healthy_manifest="$(cat "$GLANCE_OUT/healthy/glance-restore.json")"; fi
  [[ "$(cat "$GLANCE_OUT/healthy/glance-restore.json")" == "$healthy_manifest" ]]
done
echo "PASS: failed first key does not block healthy second key on repeated recovery polls"
