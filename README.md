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
it, `targetSdk 34` would block both the dashboard WebView and the `ws://` connection to
the HA WebSocket API.
