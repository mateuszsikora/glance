# Architecture

Glance is a single-module native Android application written in Kotlin. It supports Android 8.0 (API 26) and newer, compiles against API 37, targets API 34, and is designed for long-running wall-mounted tablets.

## Runtime components

- `MainActivity` owns the fullscreen UI, time-based dashboard selection, dashboard pager, idle-screen overlay and timer, settings gesture, brightness controller, reversible soft-off overlay, and communication with the background services.
- `ContentSchedulePolicy` resolves the active local-time profile, while `IdleTimeoutTracker` keeps elapsed-time inactivity calculations independent from wall-clock changes.
- `DashboardPagerAdapter` and `WebViewFragment` create one WebView page per configured dashboard. `DashboardOrigin` restricts top-level navigation to the dashboard origin and explicitly allowed login origins.
- `KioskService` is the foreground control plane. It owns MQTT, the optional remote configuration server, screen commands, schedule state, and relaunch behavior independently of the Activity lifecycle.
- `WatchdogService` performs health checks, periodic reloads, and memory-pressure recovery while avoiding unnecessary WebView work when the screen is off. `DashboardReachabilityProbe` and `StaleDashboardPolicy` additionally recover pages that stayed loaded while their live connection died.
- `ScheduleManager` and `ScheduleReceiver` calculate and deliver daily screen transitions. `BootReceiver` restores the kiosk and reschedules work after boot, package replacement, clock, time-zone, and exact-alarm permission changes.
- `ScreenController` implements screen state changes. Device Owner mode can use `lockNow()`; regular-app mode uses a reversible black overlay and window brightness.
- `BrightnessController` maps ambient-light readings or remote brightness commands to the Android window.
- `AppConfig` stores configuration and lifecycle state in private `SharedPreferences`. `SecretStore` encrypts the MQTT password using AES-GCM with a non-exportable Android Keystore key.
- `SettingsActivity` validates and applies user configuration behind a salted PBKDF2 PIN verifier with retry lockout.
- `RemoteConfigServer` exposes an opt-in, LAN-only HTTP form on port 8080. It shares `AppConfig`, validation, PIN lockout and the service reload path with on-device settings.

## Data flow

1. `GlanceApp` creates the shared configuration and notification channels.
2. `MainActivity` resolves the current content profile, renders its dashboard URLs, and starts both foreground services unless kiosk mode was deliberately suspended.
3. `KioskService` connects to the configured broker, publishes Home Assistant discovery/state/availability, and converts MQTT or schedule requests into screen and brightness commands. `AlarmPingSender` keeps the broker session alive through display-off suspend, where Paho's default timer-based keep-alive would stall.
4. Commands that require UI state are sent through package-scoped, non-exported broadcasts to `MainActivity`; Device Owner screen-off can be executed directly by the service.
5. The Activity reports resulting screen and brightness state back to the service, which publishes retained MQTT state.
6. After inactivity, the Activity overlays a separately origin-restricted idle WebView. Its first touch is consumed, the current time profile is resolved again, and the underlying dashboard is revealed.
7. The watchdog asks the current visible WebView for a health response and broadcasts a package-scoped reload request when recovery is needed. In the same pass it probes the active dashboard host off the main thread, because a dashboard whose backend restarted keeps reporting a loaded document; a host that answers again, or a long screen-off window, triggers the same reload.
8. An authenticated remote form save updates `AppConfig`, asks `KioskService` to rebuild MQTT and schedule state, and broadcasts the same dashboard reload used by local settings.

## Security model

- Dashboard and broker endpoints are entirely user-configured. The app has no project-operated backend or telemetry.
- Top-level WebView navigation is origin restricted. Additional authentication origins require explicit configuration.
- MQTT credentials stay in private application storage; the password is encrypted at rest. WebView credentials and cookies remain under Android WebView's storage model.
- Remote configuration is disabled by default. When enabled, it uses bounded HTTP parsing, a limited worker pool, PIN retry lockout, expiring in-memory sessions, CSRF tokens, restrictive response headers, and never renders the stored MQTT password.
- Android backups are disabled. Receivers, services, and settings are not exported unless Android requires an exported entry point with a platform permission.
- Device Owner, signing keys, settings PINs, dashboard sessions, and broker credentials are trust anchors and must be protected operationally.
- Cleartext dashboard HTTP, remote configuration HTTP, and MQTT are supported for local deployments, so transport confidentiality depends on endpoint configuration and network trust. Remote settings should be enabled only on a trusted LAN.

## Lifecycle and provisioning constraints

Device Owner provisioning gives Glance powerful kiosk controls and may require a factory reset. Updates must retain both the Android application ID and signing certificate. Forks should choose their own application ID and signing strategy before provisioning the first device.

Vendor battery management can still interfere with long-running services. Deployment testing should cover boot, screen transitions, network loss and recovery, WebView updates, clock changes, and application updates on the target hardware.

## Verification

Unit tests cover configuration, navigation origins, MQTT topics/endpoints/reconnect policy/keep-alive alarms, screen and content scheduling, idle-timeout calculations, settings validation, watchdog lifecycle, dashboard reachability probing, and staleness decisions. CI runs unit tests, Android lint, and debug and release builds. Hardware-dependent Device Owner and OEM behavior requires real-device testing.
