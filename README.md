# glance
Lightweight Android kiosk app that turns an old tablet into an always-on, wall-mounted dashboard.

## Building

Requirements: JDK 17, Android SDK with platform 34 and build-tools 34.0.0.

Point Gradle at your SDK by creating `local.properties` (untracked):

```
sdk.dir=/Users/<you>/Library/Android/sdk
```

### Debug

```
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Release

Release builds are signed from `keystore.properties` in the project root, which is
untracked. Create the keystore once and keep it safe — an APK signed with a different
key cannot be installed over an existing one without uninstalling first, which on a
device-owner kiosk means factory-resetting the tablet.

```
keytool -genkeypair -v -keystore glance-release.jks -alias glance \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -dname "CN=Glance Kiosk, OU=Home, O=Glance, C=PL"
cp keystore.properties.example keystore.properties   # then fill in your passwords
./gradlew assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

Without `keystore.properties` the release build still runs, but produces an unsigned
APK that cannot be installed.

### Provisioning as device owner

Kiosk mode (LockTask) and screen on/off require the app to be device owner. The device
must have no accounts configured — on a used tablet this means a factory reset first.

```
adb shell dpm set-device-owner com.glance/.AdminReceiver
```

## Networking

Home Assistant is usually self-hosted over plain `http://` on a LAN, so the app ships a
permissive `res/xml/network_security_config.xml` that allows cleartext traffic. Without
it, `targetSdk 34` would block the dashboard WebView. The MQTT connection is configured
separately and normally uses plain TCP port `1883` on the trusted local network.

## Home Assistant MQTT discovery

Glance exposes the tablet screen as a native brightness-capable MQTT light. It publishes
a retained Home Assistant MQTT Discovery payload, state, and availability/LWT. No helper
entities, template YAML, or Home Assistant access token are required.

MQTT and the daily screen schedule run inside the foreground kiosk service rather than
the dashboard Activity. This lets Home Assistant wake the tablet even if Android has
recreated the WebView while the display was off. Initial broker failures use exponential
backoff, while established sessions use Paho automatic reconnect.

Requirements:

1. Configure the MQTT integration and broker in Home Assistant.
2. Create a dedicated login for the tablet in the Mosquitto broker configuration.
3. Open Glance settings (tap the top-right corner five times; default PIN `1234`).
4. Enable MQTT and enter the broker host, port (normally `1883`), username, and password.
5. Save and restart Glance.

Home Assistant will discover a `Glance Tablet` device with a `Screen` light entity. The
entity supports ON/OFF, brightness `0..255`, and availability. Topics are derived from a
stable Android device ID:

```
homeassistant/light/glance_<device-id>/config
glance/<device-id>/light/set
glance/<device-id>/light/state
glance/<device-id>/availability
```

When Glance is not Device Owner, OFF uses a reversible soft-off (black overlay and window
brightness zero); tapping the black screen or sending ON wakes it. With Device Owner,
Glance uses `DevicePolicyManager.lockNow()` for a real hardware screen-off.

MQTT credentials are encrypted at rest with an Android Keystore AES-GCM key. Port `1883`
still sends MQTT traffic unencrypted on the network; use an `ssl://` broker endpoint when
the broker is configured for TLS.

## Device Owner signing

Android only accepts an update to an installed Device Owner when the new APK uses the same
signing certificate. Configure a release keystore before first provisioning. If the tablet
was provisioned with a debug APK, securely back up that exact `~/.android/debug.keystore`;
the build falls back to that key when no `keystore.properties` is present so local release
artifacts remain update-compatible with the provisioned tablet.
