#!/usr/bin/env bash
#
# Watches a GitHub release for a new unsigned Glance build, signs it with the mounted keys, and
# serves the signed APKs plus an update manifest to tablets on the local network.
#
# Android accepts an update only from the certificate already installed, so tablets provisioned
# with different keys need separately signed copies of the same build. Each key is a directory
# under /keys and publishes below its own path, which is also why there is no flat single-key
# layout: one tablet is just the case where there is one directory.
#
#   /keys/<name>/keystore       the key that also signed the tablet's installed build
#   /keys/<name>/alias          the key alias inside that keystore
#   /keys/<name>/password       the keystore password, single line
#   /keys/<name>/key-password   optional, defaults to the keystore password
#   /keys/<name>/cert-sha256    optional, the certificate the tablet expects
set -euo pipefail

: "${GLANCE_REPO:?set GLANCE_REPO to owner/name}"
: "${GLANCE_PUBLIC_URL:?set GLANCE_PUBLIC_URL to the address tablets can reach, e.g. http://rpi.lan:8080}"

APKSIGNER=(java -jar /opt/apksigner/apksigner.jar)
PUBLIC_URL="${GLANCE_PUBLIC_URL%/}"
KEYS_DIR="${GLANCE_KEYS_DIR:-/keys}"
SECRETS_DIR=/tmp/glance-secrets

log() { printf '%s %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*"; }

token() {
  if [[ -n "${GLANCE_GITHUB_TOKEN_FILE:-}" && -r "${GLANCE_GITHUB_TOKEN_FILE}" ]]; then
    tr -d '\n' < "${GLANCE_GITHUB_TOKEN_FILE}"
  else
    printf '%s' "${GLANCE_GITHUB_TOKEN:-}"
  fi
}

# Always the newest release, so publishing a tag is all it takes to roll the tablets forward.
# Public repositories resolve /releases/latest/download/ without credentials; private ones expose
# assets only through the API, which needs a read-only token.
fetch_asset() {
  local name="$1" destination="$2" auth
  auth="$(token)"

  if [[ -z "$auth" ]]; then
    curl -fsSL -o "$destination" \
      "https://github.com/${GLANCE_REPO}/releases/latest/download/${name}"
    return
  fi

  local release asset_id
  release="$(curl -fsSL -H "Authorization: Bearer ${auth}" \
    "https://api.github.com/repos/${GLANCE_REPO}/releases/latest")"
  asset_id="$(jq -r --arg n "$name" '.assets[] | select(.name == $n) | .id' <<<"$release")"
  [[ -n "$asset_id" && "$asset_id" != "null" ]] || { log "asset $name not found"; return 1; }

  curl -fsSL -o "$destination" \
    -H "Authorization: Bearer ${auth}" \
    -H "Accept: application/octet-stream" \
    "https://api.github.com/repos/${GLANCE_REPO}/releases/assets/${asset_id}"
}

# A directory counts as a key only once it holds a keystore, so an editor backup or a stray note
# left under /keys cannot quietly become a publishing target of its own.
discover_targets() {
  local dir
  for dir in "${KEYS_DIR}"/*/; do
    [[ -r "${dir}keystore" ]] || continue
    basename "$dir"
  done
}

# Resolves one key directory into the paths the rest of the script works with.
load_target() {
  local dir="${KEYS_DIR}/$1"
  T_NAME="$1"
  T_KEYSTORE="${dir}/keystore"
  T_ALIAS="$(head -n 1 "${dir}/alias" 2>/dev/null || true)"
  T_STORE_PASSWORD_FILE="${dir}/password"
  T_KEY_PASSWORD_FILE="${dir}/key-password"
  [[ -r "$T_KEY_PASSWORD_FILE" ]] || T_KEY_PASSWORD_FILE="$T_STORE_PASSWORD_FILE"
  T_EXPECT="$(head -n 1 "${dir}/cert-sha256" 2>/dev/null | tr -d ' :' || true)"
  T_OUT="${GLANCE_OUT}/${T_NAME}"
  T_URL="${PUBLIC_URL}/${T_NAME}"
  T_SECRETS="${SECRETS_DIR}/${T_NAME}"
  T_STATE="${T_OUT}/.last-signed"
  T_MANIFEST="${T_OUT}/glance-update.json"
}

# apksigner consumes each `file:` reference sequentially from a single handle, so pointing both
# --ks-pass and --key-pass at one file makes it read the key password past end of file. It also
# requires a line terminator. Normalising into two private files makes the mounted password file
# work whether or not it ends with a newline, and whether or not both passwords are the same.
prepare_passwords() {
  rm -rf "$T_SECRETS"
  mkdir -p "$T_SECRETS"
  chmod 700 "$T_SECRETS"
  printf '%s\n' "$(head -n 1 "$T_STORE_PASSWORD_FILE")" > "${T_SECRETS}/ks"
  printf '%s\n' "$(head -n 1 "$T_KEY_PASSWORD_FILE")" > "${T_SECRETS}/key"
  chmod 600 "${T_SECRETS}/ks" "${T_SECRETS}/key"
}

# A keystore that exists but does not hold the expected key is the expensive mistake: signing
# succeeds, the manifest looks healthy, and the tablet refuses every update with nothing but
# "Update is not signed by the installed certificate" in its own log. Checking at startup turns
# that into an immediate, local failure. keytool reads the password from stdin so it stays out of
# the process list, the same reason apksigner is given file references.
verify_key() {
  local listing fingerprint

  [[ -r "$T_STORE_PASSWORD_FILE" ]] || { log "${T_NAME}: no keystore password at ${T_STORE_PASSWORD_FILE}"; return 1; }
  [[ -n "$T_ALIAS" ]] || { log "${T_NAME}: no key alias in ${KEYS_DIR}/${T_NAME}/alias"; return 1; }

  prepare_passwords

  if ! listing="$(keytool -list -v \
      -keystore "$T_KEYSTORE" \
      -alias "$T_ALIAS" < "${T_SECRETS}/ks" 2>&1)"; then
    log "${T_NAME}: keystore has no usable key '${T_ALIAS}'"
    log "${T_NAME}: keys present: $(keytool -list -keystore "$T_KEYSTORE" < "${T_SECRETS}/ks" 2>/dev/null \
      | sed -n 's/^\([^,]*\), .*PrivateKeyEntry.*/\1/p' | paste -sd' ' -)"
    return 1
  fi

  # keytool prints AA:BB:CC..., apksigner prints aabbcc...; compare in the latter form.
  fingerprint="$(sed -n 's/^[[:space:]]*SHA256: //p' <<<"$listing" | head -n 1 | tr -d ':' | tr 'A-Z' 'a-z')"
  log "${T_NAME}: key '${T_ALIAS}' certificate ${fingerprint}"

  if [[ -n "$T_EXPECT" && "$fingerprint" != "${T_EXPECT,,}" ]]; then
    log "${T_NAME}: expected certificate ${T_EXPECT,,}"
    log "${T_NAME}: every update signed with this key would be refused by the tablet"
    return 1
  fi
}

publish() {
  local version_code="$1" version_name="$2" unsigned="$3"
  local signed="${T_OUT}/glance-${version_code}.apk"
  local staging="${signed}.tmp"
  local certs

  # Every step below is checked explicitly. This function runs with errexit suppressed, because
  # check_once is invoked as `check_once || log ...` so that a transient network failure does not
  # kill the daemon -- which also means a failing command here would otherwise be ignored and the
  # manifest would be published pointing at an APK that was never written.
  rm -f "$staging"

  # Passwords are handed over as file references rather than arguments so they never appear in
  # this container's process list.
  # v4 signing is off: it emits a detached .idsig that only incremental installs use, and the
  # tablet fetches nothing but the APK itself.
  if ! "${APKSIGNER[@]}" sign \
    --ks "$T_KEYSTORE" \
    --ks-key-alias "$T_ALIAS" \
    --ks-pass "file:${T_SECRETS}/ks" \
    --key-pass "file:${T_SECRETS}/key" \
    --v4-signing-enabled false \
    --out "$staging" \
    "$unsigned"; then
    log "${T_NAME}: signing build ${version_code} failed; not publishing"
    rm -f "$staging"
    return 1
  fi

  if ! certs="$("${APKSIGNER[@]}" verify --print-certs "$staging" 2>&1)"; then
    log "${T_NAME}: signed build ${version_code} failed verification; not publishing"
    rm -f "$staging"
    return 1
  fi

  # Android refuses an update that is not signed by the certificate already installed, and reports
  # it only in the tablet's log. Comparing the fingerprint here turns that into a visible failure
  # on the machine doing the signing.
  local signer_sha
  signer_sha="$(sed -n 's/^Signer #1 certificate SHA-256 digest: //p' <<<"$certs" | head -n 1)"
  if [[ -n "$T_EXPECT" && "${signer_sha,,}" != "${T_EXPECT,,}" ]]; then
    log "${T_NAME}: signed with ${signer_sha:-unknown}, expected ${T_EXPECT,,}"
    rm -f "$staging"
    return 1
  fi

  local digest
  digest="$(sha256sum "$staging" | cut -d' ' -f1)" || { rm -f "$staging"; return 1; }
  mv "$staging" "$signed" || return 1

  # Written last and atomically: a tablet must never read a manifest that points at an APK which
  # is still being copied.
  jq -n \
    --argjson versionCode "$version_code" \
    --arg versionName "$version_name" \
    --arg url "${T_URL}/glance-${version_code}.apk" \
    --arg sha256 "$digest" \
    '{versionCode: $versionCode, versionName: $versionName, url: $url, sha256: $sha256}' \
    > "${T_MANIFEST}.tmp"
  mv "${T_MANIFEST}.tmp" "$T_MANIFEST"

  printf '%s' "$version_code" > "$T_STATE"
  log "${T_NAME}: published ${version_code} (${version_name}) signed by ${signer_sha:-unknown}"

  # Older APKs are kept briefly so a tablet mid-download is not left with a dead URL.
  find "$T_OUT" -maxdepth 1 -name 'glance-*.apk' -printf '%T@ %p\n' \
    | sort -rn | tail -n "+$((GLANCE_KEEP + 1))" | cut -d' ' -f2- | xargs -r rm -f
}

target_is_current() {
  local version_code="$1" last=0
  [[ -r "$T_STATE" ]] && last="$(cat "$T_STATE")"
  (( version_code <= last ))
}

check_once() {
  local version_code version_name asset expected actual name failed=0
  # Deliberately outside GLANCE_OUT: that directory is served, and the unsigned intermediate
  # should never be reachable from the network.
  local work=/tmp/glance-updater
  rm -rf "$work" && mkdir -p "$work"

  fetch_asset build.json "${work}/build.json" || return 0
  version_code="$(jq -r '.versionCode' "${work}/build.json")"
  version_name="$(jq -r '.versionName' "${work}/build.json")"
  asset="$(jq -r '.asset' "${work}/build.json")"
  expected="$(jq -r '.sha256' "${work}/build.json")"

  [[ "$version_code" =~ ^[0-9]+$ ]] || { log "malformed build.json"; return 0; }

  # The build is downloaded once and signed once per key, so adding a tablet costs a signature
  # rather than another copy of the release.
  local -a pending=()
  for name in "${TARGETS[@]}"; do
    load_target "$name"
    target_is_current "$version_code" || pending+=("$name")
  done
  (( ${#pending[@]} > 0 )) || return 0

  log "new build ${version_code} available for: ${pending[*]}"
  fetch_asset "$asset" "${work}/unsigned.apk" || return 0
  actual="$(sha256sum "${work}/unsigned.apk" | cut -d' ' -f1)"
  if [[ "$actual" != "$expected" ]]; then
    log "checksum mismatch for build ${version_code}; skipping"
    return 0
  fi

  # One key failing must not hold up the others; each retries on the next poll because a failed
  # publish deliberately leaves its state file alone.
  for name in "${pending[@]}"; do
    load_target "$name"
    publish "$version_code" "$version_name" "${work}/unsigned.apk" || failed=1
  done
  return "$failed"
}

mapfile -t TARGETS < <(discover_targets)
if (( ${#TARGETS[@]} == 0 )); then
  log "no keys found under ${KEYS_DIR}"
  log "each tablet key is a directory holding keystore, alias and password;"
  log "see docs/self-hosted-updates.md"
  exit 1
fi
log "signing for ${#TARGETS[@]} key(s): ${TARGETS[*]}"

# Every key is checked before anything is served, so a mistake in one of them is reported at
# startup rather than the first time that tablet asks for an update. A key that fails is dropped
# rather than fatal: a typo in a newly added one must not stop the tablets that were already being
# served, and refusing to start would take the manifests offline for all of them.
declare -a USABLE=()
for target in "${TARGETS[@]}"; do
  load_target "$target"
  mkdir -p "$T_OUT"
  if verify_key; then
    USABLE+=("$target")
  else
    log "${target}: excluded, its tablets will not be updated until this is fixed"
  fi
done
if (( ${#USABLE[@]} == 0 )); then
  log "no usable keys under ${KEYS_DIR}"
  exit 1
fi
TARGETS=("${USABLE[@]}")

log "serving ${GLANCE_OUT} on port ${GLANCE_PORT}"
python3 -m http.server "${GLANCE_PORT}" --directory "${GLANCE_OUT}" --bind 0.0.0.0 &
trap 'kill 0' TERM INT

log "watching ${GLANCE_REPO} releases every ${GLANCE_POLL_SECONDS}s"
while true; do
  check_once || log "update check failed"
  sleep "${GLANCE_POLL_SECONDS}"
done
