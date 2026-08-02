# Architecture

Glance is a single-module native Android application written in Kotlin. It supports Android 8.0 (API 26) and newer, compiles against API 37, targets API 34, and is designed for long-running wall-mounted tablets.

## Runtime components

- `MainActivity` owns the fullscreen UI, dashboard pager, settings gesture, brightness controller, reversible soft-off overlay, and communication with the background services.
- `DashboardPagerAdapter` and `WebViewFragment` create one WebView page per configured dashboard. `DashboardOrigin` restricts top-level navigation to the dashboard origin and explicitly allowed login origins.
- `KioskService` is the foreground control plane. It owns MQTT, screen commands, schedule state, and relaunch behavior independently of the Activity lifecycle.
- `WatchdogService` performs health checks, periodic reloads, and memory-pressure recovery while avoiding unnecessary WebView work when the screen is off.
- `ScheduleManager` and `ScheduleReceiver` calculate and deliver daily screen transitions. `BootReceiver` restores the kiosk and reschedules work after boot, package replacement, clock, time-zone, and exact-alarm permission changes.
- `ScreenController` implements screen state changes. Device Owner mode can use `lockNow()`; regular-app mode uses a reversible black overlay and window brightness.
- `BrightnessController` maps ambient-light readings or remote brightness commands to the Android window.
- `AppConfig` stores configuration and lifecycle state in private `SharedPreferences`. `SecretStore` encrypts the MQTT password using AES-GCM with a non-exportable Android Keystore key.
- `SettingsActivity` validates and applies user configuration behind a salted PBKDF2 PIN verifier with retry lockout.

## Data flow

1. `GlanceApp` creates the shared configuration and notification channels.
2. `MainActivity` renders configured dashboard URLs and starts both foreground services unless kiosk mode was deliberately suspended.
3. `KioskService` connects to the configured broker, publishes Home Assistant discovery/state/availability, and converts MQTT or schedule requests into screen and brightness commands.
4. Commands that require UI state are sent through package-scoped, non-exported broadcasts to `MainActivity`; Device Owner screen-off can be executed directly by the service.
5. The Activity reports resulting screen and brightness state back to the service, which publishes retained MQTT state.
6. The watchdog asks the current WebView for a health response and broadcasts a package-scoped reload request when recovery is needed.

## Security model

- Dashboard and broker endpoints are entirely user-configured. The app has no project-operated backend or telemetry.
- Top-level WebView navigation is origin restricted. Additional authentication origins require explicit configuration.
- MQTT credentials stay in private application storage; the password is encrypted at rest. WebView credentials and cookies remain under Android WebView's storage model.
- Android backups are disabled. Receivers, services, and settings are not exported unless Android requires an exported entry point with a platform permission.
- Device Owner, signing keys, settings PINs, dashboard sessions, and broker credentials are trust anchors and must be protected operationally.
- Cleartext HTTP and MQTT are supported for local deployments, so transport confidentiality depends on endpoint configuration and network trust.

## Lifecycle and provisioning constraints

Device Owner provisioning gives Glance powerful kiosk controls and may require a factory reset. Updates must retain both the Android application ID and signing certificate. Forks should choose their own application ID and signing strategy before provisioning the first device.

Vendor battery management can still interfere with long-running services. Deployment testing should cover boot, screen transitions, network loss and recovery, WebView updates, clock changes, and application updates on the target hardware.

## Verification

Unit tests cover configuration, navigation origins, MQTT topics/endpoints/reconnect policy, scheduling, settings validation, and watchdog lifecycle. CI runs unit tests, Android lint, and debug and release builds. Hardware-dependent Device Owner and OEM behavior requires real-device testing.
