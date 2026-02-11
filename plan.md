# Implementation Plan – HA Kiosk Tablet (Android 8)

## Goal

Turn an Android 8 tablet into a dedicated, stable Home Assistant kiosk that:

- Displays HA dashboard(s) in fullscreen WebView
- Supports multiple views with swipe navigation
- Automatically adjusts screen brightness based on ambient light
- Controls screen ON/OFF via schedule and HA automations
- Exposes itself as a virtual "light" entity in Home Assistant

---

## Target Device

**Model:** Huawei BAH2-L09 (MediaPad M5 Lite 10)
**Android:** 8.0 (Oreo)
**SoC:** Kirin 659
**RAM:** 3 GB
**Display:** 10.1" 1920x1200

**Huawei-specific notes:**
- Has Google Play Services (pre-ban device)
- EMUI aggressively kills background processes — must whitelist app in battery optimization, Protected Apps, and App Launch settings
- `dpm set-device-owner` may require removing all accounts (including Huawei ID) first
- Light sensor present (for auto-brightness)

---

## Architecture Overview

**Platform:** Native Android (Kotlin)
**Min SDK:** 26 (Android 8.0)
**Target Device:** Huawei BAH2-L09 (Android 8 / EMUI 8)
**HA Communication:** WebSocket API (bidirectional)
**Provisioning:** Device Owner via ADB (`dpm set-device-owner`)

---

## Modules

### Module 1 – Kiosk / Fullscreen WebView (MVP Priority 1)

**Goal:** Fullscreen WebView locked in kiosk mode, running 24/7.

**Requirements:**
- Single-Activity architecture with Fragment-based views
- Fullscreen immersive mode (hide status bar + navigation bar)
- LockTask mode (requires Device Owner)
- Block Back, Home, Recents buttons
- WebView loading HA dashboard URL
- Handle WebView errors gracefully (no blank screens)
- Persist cookies / HA auth token across restarts

**Implementation notes:**
- Set app as Device Owner via: `adb shell dpm set-device-owner com.glance/.AdminReceiver`
- Use `Activity.startLockTask()` after setting allowed packages via DevicePolicyManager
- WebView settings: JavaScript enabled, DOM storage enabled, mixed content allowed
- Consider periodic WebView reload (e.g. every 6h) to prevent memory leaks on Android 8
- Monitor Android System WebView version — sideload update if needed for TLS/CSS compatibility

**Key classes:**
- `MainActivity` – single Activity, hosts fragments
- `DeviceAdminReceiver` – device owner component
- `KioskService` (Foreground Service) – ensures app stays alive

---

### Module 2 – Multi-View / Swipe Navigation (MVP Priority 2)

**Goal:** Swipe between multiple HA dashboards.

**Requirements:**
- Support N configurable dashboard URLs
- Horizontal swipe (ViewPager2) to switch between them
- Optional: auto-rotate views on a timer
- Indicator dots or similar minimal UI to show current view
- Lazy-load WebViews (max 2-3 in memory at a time to avoid OOM)

**Implementation notes:**
- `ViewPager2` + `FragmentStateAdapter`
- Each page = `WebViewFragment` with its own URL
- Offscreen page limit = 1 (to save memory)
- Store dashboard URLs in SharedPreferences or local config file
- Auto-rotate: simple Handler/coroutine loop that calls `viewPager.setCurrentItem()`

**Key classes:**
- `DashboardPagerAdapter` – manages WebView fragments
- `WebViewFragment` – reusable fragment wrapping a WebView
- `DashboardConfig` – data class holding URL list + rotation settings

---

### Module 3 – Auto Brightness (MVP Priority 3)

**Goal:** Dynamically adjust screen brightness based on ambient light sensor.

**Requirements:**
- Read ambient light sensor (TYPE_LIGHT)
- Map lux values to brightness (0–255)
- Smooth transitions (no abrupt jumps)
- Configurable min/max brightness bounds
- Optional: night mode override (very low brightness + warm overlay)

**Implementation notes:**
- `SensorManager` + `SensorEventListener` for TYPE_LIGHT
- Apply exponential moving average (EMA) to smooth lux readings
- Map lux → brightness using a curve (not linear — human perception is logarithmic)
- Set brightness via `WindowManager.LayoutParams.screenBrightness`
- With Device Owner, can also use `Settings.System.SCREEN_BRIGHTNESS`
- Sample rate: ~1s is sufficient, no need for SENSOR_DELAY_FASTEST

**Suggested lux-to-brightness mapping:**
```
0-10 lux    → 5-15 brightness   (dark room)
10-100 lux  → 15-80 brightness  (dim room)
100-500 lux → 80-180 brightness (normal indoor)
500+ lux    → 180-255 brightness (bright / sunlight)
```

**Key classes:**
- `BrightnessController` – sensor listener + brightness logic
- `BrightnessConfig` – min/max bounds, curve parameters

---

### Module 4 – Screen Schedule + Virtual Light (MVP Priority 4)

**Goal:** Turn screen ON/OFF on a schedule and expose tablet as a HA light entity.

**Requirements:**
- Configurable ON/OFF times (e.g. ON at 06:00, OFF at 23:00)
- Publish screen state to HA as a virtual light (`on` / `off`)
- Accept commands from HA to turn screen on/off
- Support brightness control from HA (0-255 mapped to HA 0-100%)
- React to HA automations (e.g. "TV ON → tablet screen OFF")

**Implementation notes:**
- Screen OFF: `DevicePolicyManager.lockNow()` or `PowerManager` with Device Owner
- Screen ON: acquire partial + screen wake lock, then release
- Schedule: `AlarmManager` with `setExactAndAllowWhileIdle()` for reliability
- HA integration via WebSocket API:
  - Connect to `ws://<ha-ip>:8123/api/websocket`
  - Authenticate with Long-Lived Access Token
  - Subscribe to input_boolean or light entity state changes
  - Publish state changes via `call_service` or REST API
- Virtual light entity: create `input_boolean.tablet_screen` + `template light` in HA config

**HA configuration example:**
```yaml
input_boolean:
  tablet_screen:
    name: Tablet Screen
    icon: mdi:tablet

light:
  - platform: template
    lights:
      tablet_kiosk:
        friendly_name: "Tablet Kiosk"
        value_template: "{{ states('input_boolean.tablet_screen') }}"
        turn_on:
          service: input_boolean.turn_on
          entity_id: input_boolean.tablet_screen
        turn_off:
          service: input_boolean.turn_off
          entity_id: input_boolean.tablet_screen
        level_template: "{{ state_attr('input_number.tablet_brightness', 'value') | int }}"
        set_level:
          service: input_number.set_value
          data:
            entity_id: input_number.tablet_brightness
            value: "{{ brightness }}"
```

**Key classes:**
- `ScreenController` – manages wake locks, screen on/off
- `ScheduleManager` – alarm-based scheduling
- `HAWebSocketClient` – WebSocket connection to HA
- `HAStateManager` – publishes tablet state, receives commands

---

### Module 5 – Watchdog / Stability (MVP Priority 2.5 – implement early)

**Goal:** Keep the app running 24/7 without manual intervention.

**Requirements:**
- Detect WebView crash / blank screen / unresponsive state
- Auto-restart WebView or entire app on failure
- Foreground Service to prevent OS killing the app
- Memory monitoring — trigger WebView reload before OOM
- Periodic health check (is WebView loaded? is HA reachable?)

**Implementation notes:**
- `ForegroundService` with persistent notification (required on Android 8+)
- WebView health: inject JavaScript `ping` and expect `pong` callback within timeout
- If WebView unresponsive for >30s → reload; if 3 consecutive fails → restart Fragment
- Memory check: `Runtime.getRuntime().freeMemory()` — if below threshold, reload WebView
- Use `ProcessLifecycleOwner` to detect app state
- Scheduled WebView reload every 6h as preventive measure
- Log crashes to local file for debugging

**Key classes:**
- `WatchdogService` (Foreground Service) – heartbeat + health checks
- `WebViewHealthChecker` – JS ping/pong mechanism
- `CrashLogger` – local file logging

---

### Module 6 – Settings / Debug (MVP Priority 5)

**Goal:** Hidden settings screen for configuration without redeploying.

**Requirements:**
- 5x tap gesture on dashboard to open settings
- Configure: dashboard URLs, schedule times, brightness bounds, HA connection
- Show debug info: memory usage, WebView version, uptime, HA connection status
- Export/import config as JSON
- Option to exit kiosk mode (with PIN protection)

**Implementation notes:**
- Settings stored in SharedPreferences
- `SettingsActivity` excluded from LockTask allowed list by default
- PIN check before showing settings (hardcoded or configurable)
- Display Android System WebView version: `WebView.getCurrentWebViewPackage()`

**Key classes:**
- `SettingsActivity` – configuration UI
- `AppConfig` – singleton holding all configuration, backed by SharedPreferences
- `GestureDetector` – 5x tap detection overlay

---

## Post-MVP Features (Backlog)

| Feature | Description | Complexity |
|---|---|---|
| Motion detection | Wake screen on HA motion sensor state change | Low (WebSocket subscription) |
| Night mode | Red/warm overlay + min brightness after sunset | Low |
| Anti burn-in | Shift UI by a few pixels every N minutes | Low |
| Sensor publishing | Report battery %, CPU temp, brightness to HA | Medium |
| Auto-rotate views | Cycle dashboards on a timer | Low (already partially in Module 2) |
| OTA config | Push new dashboard URLs / settings from HA | Medium |
| Battery management | Cycle charging 20-80% via smart plug + HA | Low (HA automation only) |

---

## Project Structure

**Package:** `com.glance`
**Application ID:** `com.glance`

```
app/
├── src/main/java/com/glance/
│   ├── MainActivity.kt
│   ├── AdminReceiver.kt
│   ├── GlanceApp.kt
│   ├── config/
│   │   └── AppConfig.kt
│   ├── kiosk/
│   │   ├── KioskService.kt
│   │   ├── LockTaskHelper.kt
│   │   └── BootReceiver.kt
│   ├── dashboard/
│   │   ├── WebViewFragment.kt
│   │   ├── DashboardPagerAdapter.kt
│   │   └── DashboardConfig.kt
│   ├── brightness/
│   │   ├── BrightnessController.kt
│   │   └── BrightnessConfig.kt
│   ├── screen/
│   │   ├── ScreenController.kt
│   │   └── ScheduleManager.kt
│   ├── ha/
│   │   ├── HAWebSocketClient.kt
│   │   └── HAStateManager.kt
│   ├── watchdog/
│   │   ├── WatchdogService.kt
│   │   ├── WebViewHealthChecker.kt
│   │   └── CrashLogger.kt
│   └── settings/
│       └── SettingsActivity.kt
├── src/main/res/
│   └── ...
└── build.gradle.kts
```

---

## Implementation Order & Status

```
Phase 1 (Core Kiosk) ✅ DONE
  ├── ✅ Device Owner setup + AdminReceiver
  ├── ✅ MainActivity + KioskService + LockTask
  ├── ✅ Single WebViewFragment with HA dashboard
  ├── ✅ GlanceApp (Application class, notification channels)
  ├── ✅ BootReceiver (auto-launch on boot)
  └── ✅ Immersive fullscreen + back button blocking

Phase 2 (Multi-View) ✅ DONE
  ├── ✅ ViewPager2 + DashboardPagerAdapter
  ├── ✅ Multiple WebViewFragments (offscreen limit = 1)
  ├── ✅ AppConfig for dashboard URLs (SharedPreferences)
  └── ✅ Auto-rotate with configurable interval

Phase 2.5 (Watchdog / Stability) ✅ DONE
  ├── ✅ WatchdogService (foreground service, memory monitoring)
  ├── ✅ WebViewHealthChecker (JS ping/pong, failure escalation)
  ├── ✅ CrashLogger (file-based, 1MB rotation)
  ├── ✅ Periodic WebView reload (configurable interval)
  └── ✅ ACTION_RELOAD_WEBVIEW broadcast wired to MainActivity

Phase 3 (Brightness + Schedule) ⬜ NOT STARTED
  ├── ⬜ BrightnessController (light sensor) — stub only
  ├── ⬜ ScreenController (wake/sleep) — stub only
  ├── ⬜ ScheduleManager (AlarmManager) — stub only
  └── ⬜ Settings screen (5x tap gesture detection done, SettingsActivity stub only)

Phase 4 (HA Integration) ⬜ NOT STARTED
  ├── ⬜ HAWebSocketClient (connect + auth) — stub only
  ├── ⬜ HAStateManager (publish state, receive commands) — stub only
  ├── ⬜ Virtual light entity config in HA
  └── ⬜ End-to-end: HA automation toggles tablet screen

Phase 5 (Polish + Post-MVP) ⬜ NOT STARTED
  ├── ⬜ Night mode overlay
  ├── ⬜ Anti burn-in pixel shift
  ├── ⬜ Motion detection (HA sensor subscription)
  └── ⬜ Sensor publishing to HA
```

### Current state (build verified)

- **Build:** `./gradlew assembleDebug` passes (deprecation warnings only — expected for Android 8 immersive API)
- **Next step:** Phase 3 — implement BrightnessController, ScreenController, ScheduleManager, SettingsActivity

---

## Risks & Mitigations

| Risk | Impact | Mitigation |
|---|---|---|
| WebView OOM after days of running | Screen goes blank | Periodic reload every 6h + memory monitoring |
| Old WebView on Android 8 breaks HA frontend | Dashboard doesn't render | Sideload updated Android System WebView APK |
| Tablet overheats 24/7 on charger | Hardware failure | Smart plug cycling charge 20-80% via HA |
| Device Owner removal requires factory reset | Bricked setup | Document provisioning steps, keep backup config |
| HA WebSocket disconnects silently | Tablet stops responding to commands | Heartbeat ping + auto-reconnect with exponential backoff |
| EMUI kills background services | Kiosk/watchdog dies | Whitelist in Protected Apps, disable battery optimization, use Device Owner to pin app |
| `dpm set-device-owner` fails on Huawei | Can't enable kiosk mode | Remove all accounts (Google + Huawei ID) before running dpm command |
| Kirin 659 (3 GB RAM) limited performance | WebView sluggish with multiple views | Limit offscreen pages to 1, aggressive WebView cleanup |

