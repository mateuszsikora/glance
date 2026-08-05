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
  refuses an update signed by any other certificate, and Glance checks this before downloading
  rather than discovering it at install time.
- The tablet can reach the machine serving the updates.

## 1. Publish a release

Pushing a `v*` tag builds an unsigned, zip-aligned APK and publishes it, together with a
`build.json` describing it, as a GitHub release:

```sh
git tag v1.5 && git push origin v1.5
```

Releasing is deliberate rather than automatic on every merge. A tablet on a wall has no ADB and
cannot be downgraded, so an unreviewed commit is a poor thing to install on it unattended.

`versionName` comes from the tag. `versionCode` comes from the commit count, because Android
compares it numerically and refuses an update that does not increase it — tag names are not
reliably ordered that way.

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
an APK that is still being copied. The five most recent APKs are kept so a tablet that is
mid-download does not lose its URL.

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

Android cannot downgrade a package, so a broken build cannot be rolled back automatically. Two
guards limit the damage:

- Glance will not install a replacement until the running build has been up for 15 minutes. A build
  that keeps restarting therefore never installs its successor, which keeps the tablet reachable
  through the remote configuration panel.
- A `versionCode` that fails to install three times is abandoned until a newer one is published.

Both guards are bypassed by an explicit install from the settings page. A manual check also bypasses
them when automatic installation is enabled, because in that mode checking and installing are one
action. With automatic installation disabled, checking alone never installs anything.

The way out of a bad build is a **higher** `versionCode` containing the fix. Keep the signing key
backed up: without it no tablet provisioned with it can ever be updated again, and Device Owner
apps cannot be replaced by a differently-signed APK without a factory reset.

## Privacy

Update checks contact only the host you configure. That host learns the tablet's IP address and the
time of each check. Glance has no default update URL, so an installation that is not configured for
updates makes no such request at all.
