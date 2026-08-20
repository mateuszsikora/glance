# Architecture

Glance is a single-module native Android application written in Kotlin. It supports Android 8.0 (API 26) and newer, compiles against API 37, targets API 34, and is designed for long-running wall-mounted tablets.

## Runtime components

- `MainActivity` owns the fullscreen UI, scheduled dashboard selection, dashboard pager, idle-screen overlay and timer, settings gesture, brightness controller, reversible soft-off overlay, and communication with the background services.
- `ContentSchedulePolicy` resolves the active profile from the local day and time, falling back to the most recent earlier day, while `IdleTimeoutTracker` keeps elapsed-time inactivity calculations independent from wall-clock changes.
- `DashboardPagerAdapter` and `WebViewFragment` create one WebView page per configured dashboard. `DashboardOrigin` restricts top-level navigation to the dashboard origin and explicitly allowed login origins.
- `KioskService` is the foreground control plane. It owns MQTT, the optional remote configuration server, screen commands, schedule state, and relaunch behavior independently of the Activity lifecycle.
- `WatchdogService` performs health checks, periodic reloads, and memory-pressure recovery while avoiding unnecessary WebView work when the screen is off. `DashboardReachabilityProbe` and `StaleDashboardPolicy` additionally recover pages that stayed loaded while their live connection died.
- `ScheduleManager` and `ScheduleReceiver` calculate and deliver daily screen transitions. `BootReceiver` restores the kiosk and reschedules work after boot, package replacement, clock, time-zone, and exact-alarm permission changes.
- `ScreenController` implements screen state changes. Device Owner mode can use `lockNow()`; regular-app mode uses a reversible black overlay and window brightness. `PowerWakePolicy` decides whether a display wake that followed a charger event has to be undone.
- `BatteryMonitor` and `BatteryReading` translate the platform's battery broadcast into the charge level and power source published over MQTT, filtering the many events that do not change either.
- `BrightnessController` maps ambient-light readings or remote brightness commands to the Android window.
- `AppConfig` stores configuration and lifecycle state in private `SharedPreferences`. `SecretStore` encrypts the MQTT password using AES-GCM with a non-exportable Android Keystore key.
- `SettingsActivity` validates and applies user configuration behind a salted PBKDF2 PIN verifier with retry lockout.
- `RemoteConfigServer` exposes an opt-in, LAN-only HTTP form on port 8080. It shares `AppConfig`, validation, PIN lockout and the service reload path with on-device settings.
- `UpdateChecker` polls an operator-supplied manifest from the watchdog's timers. `UpdateManifestParser` and `UpdatePolicy` hold the pure parsing and decision logic; `UpdateInstaller` verifies the APK's signing certificate against the running installation and installs it through `PackageInstaller` using Device Owner privileges.

## Data flow

1. `GlanceApp` creates the shared configuration and notification channels.
2. `MainActivity` resolves the current content profile, renders its dashboard URLs, and starts both foreground services unless kiosk mode was deliberately suspended.
3. `KioskService` connects to the configured broker, publishes Home Assistant discovery/state/availability, and converts MQTT or schedule requests into screen and brightness commands. `AlarmPingSender` keeps the broker session alive through display-off suspend, where Paho's default timer-based keep-alive would stall.
4. Commands that require UI state are sent through package-scoped, non-exported broadcasts to `MainActivity`; Device Owner screen-off can be executed directly by the service.
5. The Activity reports resulting screen and brightness state back to the service, which publishes retained MQTT state. Hardware screen transitions are reported but never redefine the requested state: Android wakes the display on charger events, and a charge-maintaining smart plug must not be able to cancel the screen schedule. During a scheduled OFF window the service disables the stay-awake-while-plugged policy and restores screen-off after a charger event.
6. After inactivity, the Activity overlays a separately origin-restricted idle WebView. Its first touch is consumed, the profile for the current day and time is resolved again, and the underlying dashboard is revealed.
7. The watchdog asks the current visible WebView for a health response and broadcasts a package-scoped reload request when recovery is needed. In the same pass it probes the active dashboard host off the main thread, because a dashboard whose backend restarted keeps reporting a loaded document; a host that answers again, or a long screen-off window, triggers the same reload.
8. An authenticated remote form save updates `AppConfig`, asks `KioskService` to rebuild MQTT and schedule state, and broadcasts the same dashboard reload used by local settings.

## Security model

- Dashboard and broker endpoints are entirely user-configured. The app has no project-operated backend or telemetry.
- Top-level WebView navigation is origin restricted. Additional authentication origins require explicit configuration.
- MQTT credentials stay in private application storage; the password is encrypted at rest. WebView credentials and cookies remain under Android WebView's storage model.
- Remote configuration is disabled by default. When enabled, it uses bounded HTTP parsing, a limited worker pool, PIN retry lockout, expiring in-memory sessions, CSRF tokens, restrictive response headers, and never renders the stored MQTT password.
- Android backups are disabled. Receivers, services, and settings are not exported unless Android requires an exported entry point with a platform permission.
- Device Owner, signing keys, settings PINs, dashboard sessions, and broker credentials are trust anchors and must be protected operationally.
- Self-hosted updates are disabled by default and have no built-in URL. An update is installed only when its versionCode increases, its published digest matches, and it is signed by the certificate of the running installation, so the update transport is not a trust anchor.
- Cleartext dashboard HTTP, remote configuration HTTP, and MQTT are supported for local deployments, so transport confidentiality depends on endpoint configuration and network trust. Remote settings should be enabled only on a trusted LAN.

## Lifecycle and provisioning constraints

Device Owner provisioning gives Glance powerful kiosk controls and may require a factory reset. Updates must retain both the Android application ID and signing certificate. Forks should choose their own application ID and signing strategy before provisioning the first device.

That constraint decides how the project publishes. Releases carry an **unsigned** APK and always will: because an installed Device Owner accepts an update only from the certificate it was provisioned with, a signed release would bind every installation to one key held by this project, and losing or rotating that key would require a factory reset on every device using it. Each operator therefore signs with their own key, which also means the project holds no key whose compromise would reach an installed device. [Self-hosted updates](self-hosted-updates.md) describes the signing and distribution path built on this.

Vendor battery management can still interfere with long-running services. Deployment testing should cover boot, screen transitions, network loss and recovery, WebView updates, clock changes, and application updates on the target hardware.

## Verification

Unit tests cover configuration, navigation origins, MQTT topics/discovery payloads/endpoints/reconnect policy/keep-alive alarms, battery readings, screen and content scheduling, charger-wake recovery, idle-timeout calculations, settings validation, update manifest parsing and install policy, watchdog lifecycle, dashboard reachability probing, and staleness decisions. CI runs unit tests, Android lint, and debug and release builds. Hardware-dependent Device Owner and OEM behavior requires real-device testing.
