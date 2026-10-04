# Self-hosted updates

Glance can update itself over the local network, so a wall-mounted tablet does not have to be
taken down and connected to a computer for every change.

Nothing about this is operated by the project. You publish the updates, from your own machine,
signed with your own key, served from your own network. Glance ships with update checks disabled
and no default URL.

## How it fits together

```
GitHub Actions            your machine (x86 or Raspberry Pi)        tablet
  build unsigned    →       sign with your key                →    check manifest
  publish artifact          serve APK + manifest                   install silently
```

The split is not arbitrary. Building needs `aapt2` and `zipalign`, which Google publishes only as
x86_64 Linux binaries, so builds run on GitHub's runners. Signing needs `apksigner`, which is a
plain Java program, so it runs anywhere — including on an ARM board. Two useful properties fall
out of that: the machine holding your signing key never runs Gradle or any third-party build
dependency, and the key never leaves it.

## Prerequisites

- The tablet is provisioned as **Device Owner**. Android exempts a device owner from the
  installation confirmation dialog; without it there is no way to install from a LockTask kiosk,
  and Glance skips the update instead of showing a prompt nobody can reach.
- You have the signing key that produced the build currently installed on the tablet. Android
  refuses an update signed by any other certificate, and Glance checks the downloaded archive before opening an installation session.
- The tablet can reach the machine serving the updates.

## 1. Publish a release

Pushing a `v*` tag builds an unsigned, zip-aligned APK and publishes it, together with a
`build.json` describing it, as a GitHub release:

```sh
git tag v1.5 && git push origin v1.5
```

Releasing is deliberate rather than automatic on every merge. A tablet on a wall has no ADB and
cannot be downgraded, so an unreviewed commit is a poor thing to install on it unattended.

`versionName` comes from the tag. `codeIdentity` identifies the source commit, independently of
Android's installation counter. All release and recovery artifacts use the **same Android workflow**
sequence: `versionCode = 1000000 + GITHUB_RUN_NUMBER * 100 + GITHUB_RUN_ATTEMPT` (attempts 1–99).
Commit count no longer determines installation order. Gradle generates the manifest and
`BuildConfig.VERSION_CODE` together; APKs are never patched after compilation.

The million offset migrates existing builds such as 30/31. Each new workflow run reserves 100
numbers, including reruns. A rerun of an old workflow run may be older than an already published
artifact and is correctly ignored: start a new run instead. Keep this workflow's identity and
sequence across releases; when moving to another repository/workflow or if devices already have
higher custom numbers, choose a new shared offset above **all** installed/published numbers before
building anything. Do not mix independently numbered local releases into this feed. The Gradle
build rejects numbers outside Android's supported positive range up to 2100000000.

**The published APK is unsigned, and always will be.** This project does not hold a signing key on
anyone's behalf. A Device Owner installation can only be updated by an APK carrying the same
certificate it was provisioned with, so publishing a signed build would permanently tie every
installation to the maintainer's key, and losing or rotating that key would mean a factory reset on
every device. The artifact is therefore a half-product: Android will not install it, and you sign
it yourself in the next step.

## 2. Run the updater

Each signing key is a directory under `keys/`, named however you like — the name becomes part of
the URL the tablets fetch:

```sh
cd tools/updater
mkdir -p keys/hallway
cp /path/to/glance-release.jks keys/hallway/keystore
printf '%s\n' 'glance'               > keys/hallway/alias
printf '%s\n' 'your-key-password'    > keys/hallway/password
chmod -R go-rwx keys
$EDITOR compose.override.yml    # set GLANCE_PUBLIC_URL for your network
docker compose up -d
```

No credentials are needed: release assets of a public repository are fetched anonymously. If you
run this against a fork you keep private, add a fine-grained read-only token with access to that
one repository as `keys/github-token` and set `GLANCE_GITHUB_TOKEN_FILE` to point at it.

`keys/` is gitignored in full, as is `compose.override.yml` — put anything specific to your own
network or key material there rather than in `compose.yml`. If the keystore and the key have
different passwords, add `keys/hallway/key-password`.

Also write `keys/hallway/cert-sha256`, the certificate of the build already installed on that
tablet. This is worth the one minute it takes. Signing with the wrong key is not a visible failure:
the container signs happily, the manifest looks correct, and the tablet quietly declines the
update, logging `Update is not signed by the installed certificate` where nobody is watching. With
the fingerprint recorded, that key is refused at startup instead. Read it off your signing key
with:

```sh
keytool -list -v -keystore keys/hallway/keystore -alias glance | grep SHA256:
```

Provision every tablet with a dedicated release key, generated once and kept backed up. A debug
keystore will physically work, but it is generated per machine and regenerated whenever it is
deleted, so it is easy to end up unable to reproduce the key a tablet was provisioned with — and a
Device Owner installation cannot be moved to a different certificate without a factory reset.

The container polls the newest release, verifies the published checksum, signs the APK with your
key, verifies its own output and its signing certificate, and writes:

```
http://<host>:8080/<key>/glance-update.json
http://<host>:8080/<key>/glance-<versionCode>.apk
```

`glance-update.json` is written atomically and last, so a tablet never reads a manifest pointing at
an APK that is still being copied. APKs are retained so a tablet mid-download or awaiting
recovery confirmation does not lose its URL.

## Tablets with different keys

Android accepts an update only from the certificate already installed, so tablets provisioned with
different keys need separately signed copies of the same build. Add a directory per key:

```
keys/hallway/     ->  http://<host>:8080/hallway/glance-update.json
keys/kitchen/     ->  http://<host>:8080/kitchen/glance-update.json
```

The release is downloaded once and signed once per key, so another tablet costs a signature rather
than another copy of the build. Point each tablet at its own manifest URL.

Keys are independent. One that is misconfigured is reported at startup and skipped, and the others
carry on being served; the same is true of a signing failure later, which is retried on the next
poll. Correspondingly, a tablet whose key is broken silently stops receiving updates — so read the
startup log after adding one, and set `cert-sha256` so a mixed-up key cannot go unnoticed.

If you have not provisioned the tablets yet, give them all the same key instead. It is free to do
now and impossible to change later without a factory reset.

## 3. Point the tablets at it

In tablet settings, or in the remote configuration panel under **Self-hosted updates**, set the
manifest belonging to that tablet's key:

```
http://<host>:8080/<key>/glance-update.json
```

Leave it blank to disable update checks entirely. Glance checks hourly and shows the running app
version, update-server reachability, and the last outcome on the same screen.

**Check for updates now** runs a check immediately instead of waiting for the next hourly tick. Use
it right after publishing a build. With **Install newer builds automatically** enabled, scheduled
and manual checks install a newer accepted build. With it disabled, both kinds of check only report
what the server offers and expose a separate **Install** button for an operator to act on it.

## The manifest

The updater generates this, but any tool that produces the same shape will do:

```json
{
  "versionCode": 412,
  "versionName": "1.4-412",
  "url": "http://192.168.1.10:8080/hallway/glance-412.apk",
  "sha256": "5f2c…"
}
```

Glance installs the APK only when all of the following hold: `versionCode` is higher than the
running build, the download matches `sha256`, the APK declares the same package name, and it is
signed by the certificate that signed the running installation.

## Why plain HTTP is acceptable here

Self-hosted updaters normally serve by IP on a local network, where certificate setup is
impractical. Transport is not the trust anchor: an APK substituted in transit fails the signature
check, and Android enforces the same rule again during installation. The realistic consequence of a
hostile network is that updates stop arriving, not that foreign code runs.

Use HTTPS if your setup allows it. It costs nothing and removes the nuisance case.

## Failure behaviour

Recovery requires an explicit operator decision. Two
guards limit the damage:

- Glance will not install a replacement until the running build has been up for 15 minutes. A build
  that keeps restarting therefore never installs its successor, which keeps the tablet reachable
  through the remote configuration panel.
- A `versionCode` that fails to install three times is abandoned until a newer one is published.

Both guards are bypassed by an explicit install from the settings page. A manual check also bypasses
them when automatic installation is enabled, because in that mode checking and installing are one
action. With automatic installation disabled, checking alone never installs anything.

The way out of a bad build is a **higher** `versionCode` containing a fix or a reviewed rebuild of older code (below). Keep the signing key
backed up: without it no tablet provisioned with it can ever be updated again, and Device Owner
apps cannot be replaced by a differently-signed APK without a factory reset.

## Privacy

Update checks contact only the host you configure. That host learns the tablet's IP address and the
time of each check. Glance has no default update URL, so an installation that is not configured for
updates makes no such request at all.


## Restore older code (Android 8 and later)

This is **not an Android versionCode downgrade**. Android prevents installation of a lower
`versionCode` ([Android versioning](https://developer.android.com/studio/publish/versioning)).
Device Owner removes the installation confirmation dialog
([PackageInstaller](https://developer.android.com/reference/android/content/pm/PackageInstaller));
it does not grant a downgrade exemption. In
[Android 8 PackageManagerService](https://android.googlesource.com/platform/frameworks/base/+/android-8.0.0_r1/services/core/java/com/android/server/pm/PackageManagerService.java#15625),
the downgrade flag requires a debuggable platform or debuggable installed package. It is not a
supported production Device Owner recovery mechanism. Android 10 introduced the system rollback
service; [RollbackManager](https://android.googlesource.com/platform/frameworks/base/+/android-10.0.0_r1/core/java/android/content/rollback/RollbackManager.java)
is a system API protected by MANAGE_ROLLBACKS / TEST_MANAGE_ROLLBACKS. An ordinary Device Owner
on Android 8 has neither that API nor its privileges. No hidden APIs, root, uninstall or data reset
are used here.

### Migration from build 31 and preparation of build 30

1. First deploy a normal release containing this recovery implementation, signed with the tablet's
   existing key. Build 31 cannot expose a recovery panel it does not contain. Its legacy OTA client
   can install this bridge because the installation number increases and the four original JSON
   fields remain present.
2. Run the **Android** workflow manually on the reviewed current branch, with `restore_ref` set to
   the older source commit/tag. For historical build 30 in this repository that commit is
   `ecf8b56c3e4ac6d4a7219f6989f5f8e640291551`; build 31 is `32889b9f13d49c9f266e432e5afc4f2a22722b26`.
   Verify source refs for your own release history; commit counts alone are not unique identities.
   This run builds/tests/lints the recovery variant and produces an **unsigned artifact only**.
   It does not publish a GitHub release or install anything.
3. Download the `recovery-unsigned-<number>` artifact. After review, put its `build.json` and
   `glance-unsigned.apk` in `tools/updater/recovery/` on the signing host (mounted read-only as
   `/recovery`). Copy the APK first and JSON last. Start/update the updater container with the new
   code. It checks the unsigned hash and actual APK identity, signs with each existing key, and
   exposes `<key>/glance-restore.json`. The ordinary `glance-update.json` remains on its own channel.
   The two channels share a persistent per-key installation high-water mark (`.last-signed`). An
   import older than a published artifact is refused; request a new workflow run instead. A failing
   key does not block the remaining keys: each poll attempts every eligible target, then reports
   whether any failed. Successful targets retain their publication state; failed ones retry.
4. Log in to the tablet's remote panel with its PIN. Under Self-hosted updates, use **Find recovery
   build**, inspect the current code/installation number and the recovery target, then click
   **Confirm restore**. These are authenticated POSTs with the session's CSRF token. Confirmation
   is bound to the previewed installation number and SHA-256, not whatever the server publishes later.
5. Reload after the restart. **Operation result** reports completion or the Android failure.
   A commit to PackageInstaller is only pending, never reported as successful installation. Success
   is reconciled against the running installation number on startup. A pending request below the
   running number is cleared as superseded, so returning through a legacy build which cannot
   consume recovery bookkeeping does not unexpectedly pause later updates. A failed/missing Android
   session is reported and can be retried explicitly. The installer persists Android's boot counter:
   after a device reboot, a sealed but inactive session from an earlier boot is abandoned too
   (Android 8 does not restore its queued commit or result callback). A process restart within the
   same boot does not abandon a sealed commit. Actively processed sessions are also retained.
   If boot identity is unavailable or a session otherwise remains stuck, use **Cancel pending
   installation** in the authenticated panel. Cancellation requires CSRF and the displayed session
   identity, works even with the update URL cleared, and keeps OTA paused until explicit resume.
6. OTA stays persistently **paused after recovery**, including across crashes, restarts and settings
   saves. Even forced ordinary installation cannot bypass this pause. Use **Resume normal updates**
   in the authenticated panel to remove it. The existing automatic-update switch still applies;
   resume does not turn that switch on. The next normal release uses a later workflow number and
   installs normally, even if its source commit count is below the recovery installation number.

Example sequence (illustrative numbers): code31 / installation31 → bridge / installation1000101
→ code30 / installation1000201 → code32 / installation1000301. Each arrow is an ordinary Android
package replacement. Package name, signing certificate, app UID, preferences, PIN hashes,
Android Keystore secrets and Device Owner registration stay in place.

`prepare-source.py` exports the older source and backports the current update package, remote panel,
application startup reconciliation, diagnostic providers needed by the current remote panel,
manifest and build tooling. Current update tests accompany the backport; archived UI tests remain
with the older tablet UI, and the main checkout tests the current HTTP handler and its recovery
authorization. It preserves the
older application behavior while retaining the recovery controls and future update support. This is
a deliberately identified hybrid rebuild, **not a byte-for-byte copy of the old release**. The
`codeIdentity` is its base source commit; the CI run identifies the current recovery infrastructure.
For local review only, without publishing:

```sh
python3 tools/updater/prepare-source.py ecf8b56 .context/recovery-review
# Supply an installation number reserved by your common release sequence when preparing a deployable build.
cd .context/recovery-review
./gradlew testDebugUnitTest lintDebug assembleDebug assembleRelease \
  -PversionCode=1000201 -PversionName=restore-30 \
  -PcodeIdentity=ecf8b56c3e4ac6d4a7219f6989f5f8e640291551 -PupdateKind=restore
```

### Data compatibility and security boundary

Glance currently keeps configuration in SharedPreferences, PINs as salted derived hashes, credentials
in Android Keystore-backed encrypted preferences, and policy bookkeeping in a separate preferences
file. There is no application SQL database migration to reverse. The preparation tool requires exact
matching source for `AppConfig.kt`, `SecretStore.kt`, `ContentProfile.kt` and `LockTaskHelper.kt`.
These cover configuration readers/writers, credential migration and encryption alias/AAD, content
serialization and persistent Device Owner policy bookkeeping. Builds 30 and 31 match these modules.
WebView data is left intact and continues to be read by the tablet's system WebView.

A SHA-256 compatibility contract over those modules is generated by Gradle into both BuildConfig
and signed APK metadata. The installed app persists its current contract. Recovery requires an
exact match, including the actual signed APK metadata; an HTTP manifest cannot declare incompatible
code safe. Unknown/changed serializers or migrations are rejected, even if the change might be
harmless. For a future data format change, review and backport compatible readers/migrations into
an explicit recovery source branch, test against representative current data, and extend the
contract coverage if new persisted stores are introduced. Do not simply edit the advertised hash.
There is no automatic lossy reverse migration or restoration of stale settings backups.

Before committing, Glance verifies SHA-256, package name, signer certificate set, actual APK
installation number, operation kind, source identity and data contract. Android verifies the
package signature again. Keys and keystore passwords remain on the signing host; neither the
remote panel nor the tablet receives them. The panel has no build/signing endpoint. A private
GitHub read token also stays on the updater host.

Legacy normal manifests (`versionCode`, `versionName`, `url`, `sha256`) remain accepted as ordinary
updates. Recovery requires the additional `kind: "restore"`, `codeIdentity` and `dataContract` fields
and signed APK metadata. Never point a legacy client's ordinary update URL at a recovery channel:
first install the bridge. New clients reject recovery APKs through the ordinary update path even
if someone changes the manifest's kind.

Normal and recovery APKs are retained so neither a previewed recovery nor an in-flight download
loses its URL. `GLANCE_KEEP` is no longer used; prune only artifacts no longer referenced by either
manifest or an outstanding operator confirmation. Back up `/out` (including `.last-signed`) together
with the signing keys. Restore publication state before resuming after a host migration.

Validation: `./gradlew testDebugUnitTest lintDebug assembleDebug assembleRelease`, then
`python3 -m unittest discover -s tools/updater -p 'test_*.py' -v` and
`bash -n tools/updater/entrypoint.sh`. Unit/Robolectric tests cover restore→later update, pause/resume,
PIN/config preservation, stale confirmation, authentication/CSRF, mismatched identity/signature/hash
and incompatible data. They do not substitute for a separately authorized device acceptance test.

The container integration test uses disposable keys, with networking disabled and no tablet access:

```sh
docker build -t glance-updater-test tools/updater
docker run --rm --network none --entrypoint bash -v "$PWD:/repo:ro" \
  -e INITIAL_APK=/repo/.context/initial.apk \
  -e RECOVERY_APK=/repo/.context/recovery.apk \
  -e NEXT_APK=/repo/.context/next.apk \
  glance-updater-test /repo/tools/updater/test-publish.sh
```

Supply three already built unsigned APKs in increasing installation order (normal, recovery, normal).
The test signs and verifies real APKs, checks isolated channels and persistent numbering, and rejects
corrupt input and a mismatched certificate. All output and disposable keys stay in the container's
`/tmp`; no HTTP server is started.
