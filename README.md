# Glance

Glance is a lightweight Android kiosk that turns an old tablet into an always-on, wall-mounted web dashboard. It supports multiple dashboard views, Device Owner kiosk mode, automatic brightness, scheduled screen control, a watchdog, and Home Assistant MQTT discovery.

The project targets Android 14 (API 34) and supports Android 8.0 (API 26) and newer. Development and security fixes track the latest `master` branch and, once releases are published, the most recent release.

> [!WARNING]
> Device Owner provisioning can require a factory reset. Android only accepts an update to an installed Device Owner when the APK is signed with the same certificate. Back up your signing key before provisioning and read the signing section below.

## Features

- fullscreen, swipeable WebView dashboards with origin-restricted top-level navigation;
- Device Owner LockTask mode and boot recovery;
- ambient-light and MQTT-controlled brightness;
- scheduled screen on/off, including overnight windows;
- Home Assistant MQTT discovery with retained state and availability;
- watchdog health checks and memory-pressure recovery;
- PIN-protected settings and encrypted MQTT password storage.

See [the architecture overview](docs/architecture.md) for component and data-flow details.

## Building

Requirements: JDK 17, Android SDK platform 34, and build-tools 34.0.0.

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

## Networking

Home Assistant is commonly hosted over plain `http://` on a local network, so Glance currently allows cleartext application traffic. MQTT commonly uses plain TCP port `1883`. Both expose traffic to other participants on that network. Prefer a valid HTTPS dashboard and an `ssl://` MQTT broker whenever possible, and use Glance only on a network you trust.

Top-level WebView navigation stays on the configured dashboard origin by default. If a dashboard uses OAuth or SSO on another host, add each trusted authentication origin under `Allowed login origins` in settings. Paths are ignored; scheme, host, and effective port must match exactly. Leave the list empty for same-origin authentication.

Android System WebView performs TLS validation and stores the dashboard's browser session. Keep WebView updated on the kiosk device.

## Home Assistant MQTT discovery

Glance exposes the tablet screen as a brightness-capable MQTT light. It publishes a retained Home Assistant discovery payload, state, and availability/LWT. No helper entities, template YAML, or Home Assistant access token are required.

MQTT and the daily screen schedule run in the foreground kiosk service, independently from the dashboard Activity. Initial broker failures use exponential backoff, while established sessions use Paho automatic reconnect.

1. Configure an MQTT broker and the MQTT integration in Home Assistant.
2. Create a dedicated, least-privilege broker login for the tablet.
3. Open Glance settings by tapping the top-right corner five times. A fresh installation asks you to create a PIN. An installation still using the legacy `1234` PIN must replace it on its next settings login.
4. Enable MQTT and enter the broker endpoint, username, and password.
5. Save. Glance applies the new configuration without an application restart.

Home Assistant discovers a `Glance Tablet` device with a `Screen` light entity. It supports ON/OFF, brightness `0..255`, and availability. Topics use a stable, randomly generated Glance device ID:

```text
homeassistant/light/glance_<device-id>/config
glance/<device-id>/light/set
glance/<device-id>/light/state
glance/<device-id>/availability
```

Without Device Owner privileges, OFF uses a reversible soft-off (black overlay and window brightness zero); tapping the black screen or sending ON wakes it. As Device Owner, Glance uses `DevicePolicyManager.lockNow()` for hardware screen-off.

The optional schedule defines a daytime or overnight ON window. A manual MQTT command overrides the current state until the next scheduled transition. On Android 12 and newer, Glance requests exact-alarm access; when approximate alarms are selected, Android may deliver a transition later than its configured minute. Time, time-zone, package-update, and reboot events reschedule both alarms.

MQTT passwords are encrypted at rest with an Android Keystore AES-GCM key. Encryption at rest does not protect plain MQTT traffic in transit.

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
