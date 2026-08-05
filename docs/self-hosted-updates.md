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

```sh
cd tools/updater
mkdir -p keys
cp /path/to/glance-release.jks keys/
printf '%s' 'your-keystore-password' > keys/keystore-password
chmod 600 keys/*
$EDITOR compose.yml          # set GLANCE_REPO, GLANCE_PUBLIC_URL and the keystore settings
docker compose up -d
```

No credentials are needed: release assets of a public repository are fetched anonymously. If you
run this against a fork you keep private, add a fine-grained read-only token with access to that
one repository as `keys/github-token` and set `GLANCE_GITHUB_TOKEN_FILE` to point at it.

`keys/` is gitignored in full. Set `GLANCE_KEYSTORE` and `GLANCE_KEY_ALIAS` to match the key you
actually provisioned the tablets with — the image defaults, `glance-release.jks` and alias
`glance`, assume a dedicated release key.

Set `GLANCE_EXPECT_CERT_SHA256` to the certificate of the build already installed on the tablets.
This is worth the one minute it takes. Signing with the wrong key is not a visible failure: the
container signs happily, the manifest looks correct, and every tablet quietly declines the update,
logging `Update is not signed by the installed certificate` where nobody is watching. With the
fingerprint configured the container refuses to start instead. Read it off your signing key with:

```sh
keytool -list -v -keystore keys/glance-release.jks -alias glance | grep SHA256:
```

The container polls the newest release, verifies the published checksum, signs the APK with your
key, verifies its own output and its signing certificate, and writes:

```
http://<host>:8080/glance-update.json
http://<host>:8080/glance-<versionCode>.apk
```

`glance-update.json` is written atomically and last, so a tablet never reads a manifest pointing at
an APK that is still being copied. The five most recent APKs are kept so a tablet that is
mid-download does not lose its URL.

## 3. Point the tablets at it

In tablet settings, or in the remote configuration panel under **Self-hosted updates**, set:

```
http://<host>:8080/glance-update.json
```

Leave it blank to disable update checks entirely. Glance checks hourly and reports the last outcome
on the same screen.

**Check for updates now** runs a check immediately instead of waiting for the next hourly tick. Use
it right after publishing a build. It also overrides both failure guards described below, because
those exist to stop an unattended tablet from churning on its own, and neither applies when someone
is standing at the settings page asking for the update.

## The manifest

The updater generates this, but any tool that produces the same shape will do:

```json
{
  "versionCode": 412,
  "versionName": "1.4-412",
  "url": "http://192.168.1.10:8080/glance-412.apk",
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

Both guards are bypassed by **Check for updates now**, which is an explicit operator action.

The way out of a bad build is a **higher** `versionCode` containing the fix. Keep the signing key
backed up: without it no tablet provisioned with it can ever be updated again, and Device Owner
apps cannot be replaced by a differently-signed APK without a factory reset.

## Privacy

Update checks contact only the host you configure. That host learns the tablet's IP address and the
time of each check. Glance has no default update URL, so an installation that is not configured for
updates makes no such request at all.
