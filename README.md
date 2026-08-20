# Glance

[![Android CI](https://github.com/mateuszsikora/glance/actions/workflows/android.yml/badge.svg?branch=master)](https://github.com/mateuszsikora/glance/actions/workflows/android.yml)

Give an old Android tablet a second life as a dedicated Home Assistant or web dashboard.

Glance is a lightweight, self-hosted Android kiosk for an always-on, wall-mounted display. It supports multiple dashboard views, browser-based configuration from another device, Device Owner kiosk mode, automatic brightness, scheduled screen control, a watchdog, and Home Assistant MQTT discovery.

The project targets Android 14 (API 34) and supports Android 8.0 (API 26) and newer. Development and security fixes track the latest `master` branch and, once releases are published, the most recent release.

> [!WARNING]
> Device Owner provisioning can require a factory reset. Android only accepts an update to an installed Device Owner when the APK is signed with the same certificate. Back up your signing key before provisioning and read the signing section below.

## Features

- fullscreen, swipeable WebView dashboards with origin-restricted top-level navigation;
- day- and time-based dashboard profiles and a touch-to-return idle URL;
- Device Owner LockTask mode and boot recovery;
- ambient-light and MQTT-controlled brightness;
- scheduled screen on/off, including overnight windows;
- Home Assistant MQTT discovery with retained state and availability, including battery level and charging state for smart-plug charge control;
- watchdog health checks, memory-pressure recovery, and an automatic reload once a dashboard comes back after a server restart or network outage;
- PIN-protected remote configuration from any browser on the same LAN;
- optional self-hosted over-the-air updates, signed with your own key;
- encrypted MQTT password storage with no cloud account or companion app required.

See [the architecture overview](docs/architecture.md) for component and data-flow details.

## Who is this for?

Glance is intended for people who want to reuse an older Android tablet as a dedicated smart-home or information display. It is a good fit when you:

- already have a Home Assistant, Grafana, or other web dashboard;
- want the tablet to start automatically and remain in a controlled kiosk UI;
- are comfortable using ADB once for installation and Device Owner provisioning;
- prefer a local application with no analytics, advertising, or project-operated cloud service.

Glance is distributed as source code and as *unsigned* release artifacts, so you must sign the application yourself. Releases will never contain a signed APK: an installed Device Owner can only be updated by an APK carrying the certificate it was provisioned with, so a signed release would bind every installation to the maintainer's key for good. Building from source and signing the published artifact are equally supported paths. See [Self-hosted updates](#self-hosted-updates).

## Configure the tablet from your computer

Once the tablet is mounted, you do not need to take it down or type long URLs on its touchscreen.
Glance can expose an opt-in settings panel directly on your local network:

```text
http://192.168.1.42:8080/
```

Open that address from a computer or phone, sign in with the tablet's settings PIN, and manage:

- dashboard URLs, allowed login origins, rotation and scheduled content profiles;
- the idle-screen URL and inactivity timeout;
- automatic and minimum/maximum brightness;
- screen wake and turn-off schedules;
- Home Assistant MQTT connection and discovery;
- remote access and the settings PIN itself.

Changes are validated and applied immediately without restarting the application. The browser talks
directly to the tablet—there is no Glance cloud service, account, or separate desktop application.
Remote configuration is disabled by default and the tablet shows the correct URL when it is enabled.
See [Networking](#networking) for the HTTP security model.

## Quick start

1. Prepare a dedicated tablet running Android 8.0 or newer. Device Owner provisioning normally requires a factory reset and no configured accounts.
2. Configure a stable release signing key using the instructions below.
3. Build and install the release APK.
4. Provision Glance as Device Owner with `adb shell dpm set-device-owner com.glance/.AdminReceiver`.
5. Launch Glance, tap the top-right corner five times, create a settings PIN, and configure the dashboard and optional MQTT integration.

To continue configuration from a computer, enable `Remote configuration`, save, and open one of
the displayed addresses from a device on the same network. Sessions expire after 30 minutes,
repeated wrong PINs trigger the same lockout as on-device settings, and a PIN change signs out all
remote sessions.

Use a disposable test device before provisioning a tablet you depend on. Changing the application ID or signing key later can require another factory reset.

## Building

Requirements: JDK 17, Android SDK platform 37, and Build Tools 36.0.0. The checked-in Gradle Wrapper supplies Gradle 9.5.

Point Gradle at your SDK by creating an untracked `local.properties`:

```properties
sdk.dir=/Users/<you>/Library/Android/sdk
```

### Debug

```sh
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Release

Release builds are signed from an untracked `keystore.properties` in the project root. Create the keystore once and keep it safe. An APK signed with a different key cannot update the installed app; replacing a Device Owner app may require a factory reset.

```sh
keytool -genkeypair -v -keystore glance-release.jks -alias glance \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -dname "CN=Glance Kiosk, OU=Home, O=Glance, C=PL"
cp keystore.properties.example keystore.properties   # then enter your passwords
./gradlew assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

Without `keystore.properties`, Gradle produces an unsigned release. This prevents a public artifact from silently inheriting a developer's debug identity.

To update a tablet originally provisioned with a debug APK, opt in explicitly and use the exact same backed-up debug keystore:

```sh
./gradlew -PuseDebugSigning=true assembleRelease
```

Never commit a keystore, `keystore.properties`, passwords, or a private signing certificate.

## Provisioning as Device Owner

LockTask kiosk mode and hardware screen-off require Glance to be Device Owner. The device must have no configured accounts; on a used tablet this normally means a factory reset.

```sh
adb shell dpm set-device-owner com.glance/.AdminReceiver
```

`Exit Kiosk Mode` in the protected settings screen suspends automatic relaunch, cancels screen alarms, clears temporary kiosk policies, and stops the foreground services. Launch Glance again, or select it as the Home app, to resume kiosk mode deliberately.

### Forks and application identity

The published application ID is `com.glance`. Fork maintainers should choose a unique `applicationId` and namespace before provisioning devices or publishing an APK. Changing identity later creates a different Android app and cannot update an existing `com.glance` Device Owner installation. Likewise, changing the signing certificate can require removing Device Owner state or factory-resetting the tablet.

## Scheduled content and idle screen

`Dashboard URLs` remain the default content. Enable `Show different dashboards on a schedule` to replace them with local-time profiles. Each profile is a row with a start time, the days it applies to, and one or more URLs — add rows with `+ Add profile` on the tablet or in the remote panel.

A profile starts at its configured minute on each selected day and remains active until the next profile starts, including across midnight. Leaving every day unselected means the profile repeats daily. When a day-specific profile and a daily profile start at the same minute, the day-specific one wins. Several URLs in one profile become swipeable and use the existing auto-rotation setting.

For example, a weekday profile at 06:00 stays on screen through Friday night until a weekend profile takes over on Saturday at 09:00.

A save posted programmatically to the panel may send an equivalent text form instead of rows, one mapping per line, in a `contentProfiles` field:

```text
Mon-Fri 06:00 | https://dashboard.example.test/work
Sat,Sun 09:00 | https://dashboard.example.test/weekend
18:00 | https://dashboard.example.test/evening
18:00 | https://weather.example.test/
```

Day prefixes accept single days (`Mon`), ranges (`Mon-Fri`, including wrapping ones such as `Fri-Mon`), comma-separated lists (`Mon,Wed,Fri`) and the aliases `weekdays`, `weekend` and `daily`. Omitting the prefix means every day.

The optional idle screen loads a separate URL after the configured number of minutes without a touch. Its first touch is consumed and returns to the profile for the current time, so it cannot accidentally activate dashboard controls. The idle timer is suspended while the display is logically or physically off. The idle URL keeps the display powered; use the separate screen ON/OFF schedule when the goal is to save power.

## Networking

Home Assistant is commonly hosted over plain `http://` on a local network, so Glance currently allows cleartext application traffic. MQTT commonly uses plain TCP port `1883`. Both expose traffic to other participants on that network. Prefer a valid HTTPS dashboard and an `ssl://` MQTT broker whenever possible, and use Glance only on a network you trust.

The optional remote configuration panel listens on TCP port `8080` on the tablet's network
interfaces. It is disabled by default and requires the settings PIN. The embedded server accepts
only a small bounded subset of HTTP needed by its forms, limits concurrent requests and request
sizes, uses short-lived `HttpOnly`/`SameSite` sessions and CSRF tokens, and never sends the stored
MQTT password to the browser. A blank MQTT password field preserves the stored credential; use the
explicit checkbox to clear it.

The panel deliberately uses HTTP so a browser can connect directly by IP without certificate
setup. Consequently, the PIN and any newly submitted MQTT password are not encrypted in transit.
Enable it only on a trusted LAN, keep untrusted clients off that network, and disable the panel
after use when persistent access is unnecessary. Reserving the tablet's address in DHCP keeps its
URL stable. Android 17 requires an additional local-network runtime permission after the app moves
to target SDK 37; the current target SDK 34 continues to use the existing `INTERNET` permission.

Top-level WebView navigation stays on the configured dashboard origin by default. If a dashboard uses OAuth or SSO on another host, add each trusted authentication origin under `Allowed login origins` in settings. Paths are ignored; scheme, host, and effective port must match exactly. Leave the list empty for same-origin authentication.

Android System WebView performs TLS validation and stores the dashboard's browser session. Keep WebView updated on the kiosk device.

## Home Assistant MQTT discovery

Glance exposes the tablet screen as a brightness-capable MQTT light, plus a battery percentage and a charging sensor. It publishes retained Home Assistant discovery payloads, state, and availability/LWT. No helper entities, template YAML, or Home Assistant access token are required.

MQTT and the daily screen schedule run in the foreground kiosk service, independently from the dashboard Activity. Initial broker failures use exponential backoff, while established sessions use Paho automatic reconnect. Keep-alive pings are driven by wakeup alarms rather than a plain timer, so the broker session survives the tablet suspending with the screen off instead of dropping to the last will and marking the entity unavailable.

1. Configure an MQTT broker and the MQTT integration in Home Assistant.
2. Create a dedicated, least-privilege broker login for the tablet.
3. Open Glance settings by tapping the top-right corner five times. A fresh installation asks you to create a PIN. An installation still using the legacy `1234` PIN must replace it on its next settings login.
4. Enable MQTT and enter the broker endpoint, username, and password.
5. Save. Glance applies the new configuration without an application restart.

Home Assistant discovers a `Glance Tablet` device with a `Screen` light entity, a `Battery` sensor, and a `Charging` binary sensor. The light supports ON/OFF, brightness `0..255`, and availability. Topics use a stable, randomly generated Glance device ID:

```text
homeassistant/light/glance_<device-id>/config
homeassistant/sensor/glance_<device-id>/battery/config
homeassistant/binary_sensor/glance_<device-id>/charging/config
glance/<device-id>/light/set
glance/<device-id>/light/state
glance/<device-id>/battery/state
glance/<device-id>/availability
```

Without Device Owner privileges, OFF uses a reversible soft-off (black overlay and window brightness zero); tapping the black screen or sending ON wakes it. As Device Owner, Glance uses `DevicePolicyManager.lockNow()` for hardware screen-off.

The optional schedule defines a daytime or overnight ON window. A manual MQTT command overrides the current state until the next scheduled transition. On Android 12 and newer, Glance requests exact-alarm access; when approximate alarms are selected, Android may deliver a transition later than its configured minute. Time, time-zone, package-update, and reboot events reschedule both alarms.

### Keeping the battery healthy

A tablet held at 100% on a permanent charger ages its battery quickly. The `Battery` sensor reports the charge level as a percentage, which is what a Home Assistant automation needs to switch a smart plug and keep the charge inside a window such as 40–80%. `Charging` reports whether the charge is actually rising, so a charger that a vendor charge limit or a high temperature has paused is visible rather than hidden. Both are published retained on every change, so Home Assistant has a value immediately after a restart.

Because the charger is then switched while the tablet is in use, Glance also protects the screen schedule from it. Android lights the display up when power is connected or disconnected, and the kiosk's stay-awake-while-charging policy would otherwise keep it lit until the next scheduled transition. During a scheduled OFF window Glance disables that policy and turns the display back off after a charger event, so an overnight charge cycle does not light up the room.

MQTT passwords are encrypted at rest with an Android Keystore AES-GCM key. Encryption at rest does not protect plain MQTT traffic in transit.

## Self-hosted updates

A wall-mounted tablet does not have to come down for every change. Glance can check an update
manifest that **you** publish, verify it, and install it silently as Device Owner.

There is no project-operated update service and no default URL; update checks are disabled until
you configure one. The signing key stays on your machine — continuous integration only produces the
unsigned artifact, because building needs x86-only Android tools while signing needs nothing but a
JVM. That split also keeps the machine holding your key from ever running Gradle or a third-party
build dependency.

```sh
cd tools/updater
mkdir -p keys && cp /path/to/glance-release.jks keys/
printf '%s' 'your-keystore-password' > keys/keystore-password
$EDITOR compose.yml          # set GLANCE_REPO and GLANCE_PUBLIC_URL
docker compose up -d
```

Then set `Update manifest URL` in settings, on the tablet or through the remote configuration
panel, to `http://<host>:8080/glance-update.json`. Glance checks hourly and installs a build only
when its `versionCode` is higher, its checksum matches, and it is signed by the certificate that
signed the running installation. Turn automatic installation off to keep checking and report an
available build without installing it; the settings pages then offer a separate install button.
`Check for updates now` skips the hourly wait after you publish a build. The image runs on both x86
and a Raspberry Pi.

Android cannot downgrade a package, so a bad build is recovered by publishing a higher
`versionCode`, not by rolling back. See [the full procedure](docs/self-hosted-updates.md) for the
manifest format, the failure guards, and the security model.

## Privacy

Glance has no analytics, advertising, telemetry, or project-operated backend. Configuration stays on the device. Dashboard pages and MQTT brokers are endpoints selected by the user, and their own privacy policies apply. See [PRIVACY.md](PRIVACY.md) for details.

## Testing

Run the same checks as CI:

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug assembleRelease
```

Device Owner, boot, screen-off, exact-alarm, and vendor battery-management behavior should also be tested on real hardware before deployment.

## Contributing and security

Contributions are welcome; read [CONTRIBUTING.md](CONTRIBUTING.md). Report suspected vulnerabilities privately according to [SECURITY.md](SECURITY.md), never in a public issue.

## License

Glance is licensed under the [MIT License](LICENSE). Direct dependency licenses are summarized in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
