#!/usr/bin/env bash
#
# Watches a GitHub release for a new unsigned Glance build, signs it with the mounted key, and
# serves the signed APK plus an update manifest to tablets on the local network.
set -euo pipefail

: "${GLANCE_REPO:?set GLANCE_REPO to owner/name}"
: "${GLANCE_PUBLIC_URL:?set GLANCE_PUBLIC_URL to the address tablets can reach, e.g. http://rpi.lan:8080}"

APKSIGNER=(java -jar /opt/apksigner/apksigner.jar)
PUBLIC_URL="${GLANCE_PUBLIC_URL%/}"
STATE_FILE="${GLANCE_OUT}/.last-signed"
MANIFEST="${GLANCE_OUT}/glance-update.json"

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

STORE_PASSWORD_FILE="${GLANCE_KEYSTORE_PASSWORD_FILE:-/keys/keystore-password}"
KEY_PASSWORD_FILE="${GLANCE_KEY_PASSWORD_FILE:-$STORE_PASSWORD_FILE}"
SECRETS_DIR=/tmp/glance-secrets

# apksigner consumes each `file:` reference sequentially from a single handle, so pointing both
# --ks-pass and --key-pass at one file makes it read the key password past end of file. It also
# requires a line terminator. Normalising into two private files makes the mounted password file
# work whether or not it ends with a newline, and whether or not both passwords are the same.
prepare_passwords() {
  rm -rf "$SECRETS_DIR"
  mkdir -p "$SECRETS_DIR"
  chmod 700 "$SECRETS_DIR"
  printf '%s\n' "$(head -n 1 "$STORE_PASSWORD_FILE")" > "${SECRETS_DIR}/ks"
  printf '%s\n' "$(head -n 1 "$KEY_PASSWORD_FILE")" > "${SECRETS_DIR}/key"
  chmod 600 "${SECRETS_DIR}/ks" "${SECRETS_DIR}/key"
}

# A keystore that exists but does not hold the expected key is the expensive mistake: signing
# succeeds, the manifest looks healthy, and the tablet refuses every update with nothing but
# "Update is not signed by the installed certificate" in its own log. Checking at startup turns
# that into an immediate, local failure. keytool reads the password from stdin so it stays out of
# the process list, the same reason apksigner is given file references.
verify_key() {
  local listing fingerprint
  if ! listing="$(keytool -list -v \
      -keystore "${GLANCE_KEYSTORE}" \
      -alias "${GLANCE_KEY_ALIAS}" < "${SECRETS_DIR}/ks" 2>&1)"; then
    log "keystore ${GLANCE_KEYSTORE} has no usable key '${GLANCE_KEY_ALIAS}'"
    log "keys present: $(keytool -list -keystore "${GLANCE_KEYSTORE}" < "${SECRETS_DIR}/ks" 2>/dev/null \
      | sed -n 's/^\([^,]*\), .*PrivateKeyEntry.*/\1/p' | paste -sd' ' -)"
    exit 1
  fi

  # keytool prints AA:BB:CC..., apksigner prints aabbcc...; compare in the latter form.
  fingerprint="$(sed -n 's/^[[:space:]]*SHA256: //p' <<<"$listing" | head -n 1 | tr -d ':' | tr 'A-Z' 'a-z')"
  log "signing key '${GLANCE_KEY_ALIAS}' certificate ${fingerprint}"

  if [[ -n "${GLANCE_EXPECT_CERT_SHA256:-}" && "$fingerprint" != "${GLANCE_EXPECT_CERT_SHA256,,}" ]]; then
    log "expected certificate ${GLANCE_EXPECT_CERT_SHA256,,}"
    log "every update signed with this key would be refused by the tablet; refusing to start"
    exit 1
  fi
}

publish() {
  local version_code="$1" version_name="$2" unsigned="$3"
  local signed="${GLANCE_OUT}/glance-${version_code}.apk"
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
    --ks "${GLANCE_KEYSTORE}" \
    --ks-key-alias "${GLANCE_KEY_ALIAS}" \
    --ks-pass "file:${SECRETS_DIR}/ks" \
    --key-pass "file:${SECRETS_DIR}/key" \
    --v4-signing-enabled false \
    --out "$staging" \
    "$unsigned"; then
    log "signing build ${version_code} failed; not publishing"
    rm -f "$staging"
    return 1
  fi

  if ! certs="$("${APKSIGNER[@]}" verify --print-certs "$staging" 2>&1)"; then
    log "signed build ${version_code} failed verification; not publishing"
    rm -f "$staging"
    return 1
  fi

  # Android refuses an update that is not signed by the certificate already installed, and reports
  # it only in the tablet's log. Comparing the fingerprint here turns that into a visible failure
  # on the machine doing the signing.
  local signer_sha
  signer_sha="$(sed -n 's/^Signer #1 certificate SHA-256 digest: //p' <<<"$certs" | head -n 1)"
  if [[ -n "${GLANCE_EXPECT_CERT_SHA256:-}" ]]; then
    if [[ "${signer_sha,,}" != "${GLANCE_EXPECT_CERT_SHA256,,}" ]]; then
      log "signed with ${signer_sha:-unknown}, expected ${GLANCE_EXPECT_CERT_SHA256}"
      log "the tablet would reject this build; check GLANCE_KEYSTORE and GLANCE_KEY_ALIAS"
      rm -f "$staging"
      return 1
    fi
  fi

  local digest
  digest="$(sha256sum "$staging" | cut -d' ' -f1)" || { rm -f "$staging"; return 1; }
  mv "$staging" "$signed" || return 1

  # Written last and atomically: a tablet must never read a manifest that points at an APK which
  # is still being copied.
  jq -n \
    --argjson versionCode "$version_code" \
    --arg versionName "$version_name" \
    --arg url "${PUBLIC_URL}/glance-${version_code}.apk" \
    --arg sha256 "$digest" \
    '{versionCode: $versionCode, versionName: $versionName, url: $url, sha256: $sha256}' \
    > "${MANIFEST}.tmp"
  mv "${MANIFEST}.tmp" "$MANIFEST"

  printf '%s' "$version_code" > "$STATE_FILE"
  log "published build ${version_code} (${version_name}) signed by ${signer_sha:-unknown}"

  # Older APKs are kept briefly so a tablet mid-download is not left with a dead URL.
  find "${GLANCE_OUT}" -maxdepth 1 -name 'glance-*.apk' -printf '%T@ %p\n' \
    | sort -rn | tail -n "+$((GLANCE_KEEP + 1))" | cut -d' ' -f2- | xargs -r rm -f
}

check_once() {
  local version_code version_name asset expected actual
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
  local last=0
  [[ -r "$STATE_FILE" ]] && last="$(cat "$STATE_FILE")"
  if (( version_code <= last )); then
    return 0
  fi

  log "new build ${version_code} available (have ${last})"
  fetch_asset "$asset" "${work}/unsigned.apk" || return 0
  actual="$(sha256sum "${work}/unsigned.apk" | cut -d' ' -f1)"
  if [[ "$actual" != "$expected" ]]; then
    log "checksum mismatch for build ${version_code}; skipping"
    return 0
  fi

  # A failed publish deliberately leaves the state file alone, so the next poll retries instead of
  # recording a version that was never served.
  publish "$version_code" "$version_name" "${work}/unsigned.apk" || return 1
}

mkdir -p "${GLANCE_OUT}"
[[ -r "${GLANCE_KEYSTORE}" ]] || { log "no keystore at ${GLANCE_KEYSTORE}"; exit 1; }
[[ -r "${STORE_PASSWORD_FILE}" ]] || { log "no keystore password at ${STORE_PASSWORD_FILE}"; exit 1; }
prepare_passwords
verify_key

log "serving ${GLANCE_OUT} on port ${GLANCE_PORT}"
python3 -m http.server "${GLANCE_PORT}" --directory "${GLANCE_OUT}" --bind 0.0.0.0 &
trap 'kill 0' TERM INT

log "watching ${GLANCE_REPO} releases every ${GLANCE_POLL_SECONDS}s"
while true; do
  check_once || log "update check failed"
  sleep "${GLANCE_POLL_SECONDS}"
done
