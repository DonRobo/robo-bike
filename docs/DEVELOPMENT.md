# Developing Robo Bike

Kotlin + Jetpack Compose, minSdk 31. Protocol notes: [PROTOCOL.md](PROTOCOL.md). Build: `./gradlew :app:assembleDebug`. Unit tests: `./gradlew :app:testDebugUnitTest`.
A unit test lives in the package of the code it tests and is named after it (`maintenance/Maintenance.kt` →
`maintenance/MaintenanceTest`); screenshot tests stay in `screenshots/`.
Gradle runs on the JDK on PATH (17 or newer; CI uses 21, 24 works with Gradle 8.14.7 / AGP 8.13). The Android SDK
location goes into `local.properties` (`sdk.dir=...`, gitignored) or `ANDROID_HOME`.

Before a pull request, run what CI runs (`.github/workflows/android.yml`):

```
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:assembleRelease
```

Lint must stay at zero errors.

## Screenshots

**App screens** (Robolectric + Roborazzi, JVM only, no device):

```
./gradlew :app:recordRoborazziDebug
```

writes `app/build/screenshots/<name>.png`, rendered 390 dp wide at 2x (780 px), the width of the design artboards. (The design drafts the screens were built
from — "artboards" below — are not part of the repo; the screenshot tests are the visual reference.) The tests live in
`app/src/test/kotlin/dev/donrobo/ebike/screenshots/`; `compose.screenshot("name", Tab.X) { MyScreen(...) }` wraps a
composable in the theme plus the bottom bar for tab `X` (omit the tab for sub-screens). The device size comes from
`@Config(qualifiers = "de-$PHONE")` (`de-`/`en-` picks the language; change `h…dp` for taller pages).
`components_de.png` is a catalogue of all shared components.
Screenshot tests also cover the **scrolled state**: `compose.scrolled("name", Tab.X, Scroll.UNDER) { ... }` (qualifier
`SCROLL_PHONE`, 390 x 844 dp) renders the screen with edge-to-edge system bars (insets dispatched, status bar and
gesture handle or 3 buttons drawn over it, `SystemBars.GESTURE`/`BUTTONS`) and scrolls it by touch, so the top bar's
nested scroll really runs: `Scroll.COLLAPSING` (large title half collapsed, finger still down), `Scroll.UNDER`
(content under the collapsed bar and tabs), `Scroll.ToEnd` (end padding, last item above the bottom bar / gesture
area). A new screen that scrolls adds at least an `UNDER` and a `ToEnd` shot (`<name>_scrolled_de`, `<name>_end_de`). `screenshotWindows("name", Tab.X) { ... }` captures the
whole screen including Material dialog and bottom-sheet windows (use it for dialogs/sheets; it drives a plain activity
because a text field in a dialog never lets the compose rule go idle under Robolectric). In a plain
`testDebugUnitTest` run the screenshot tests render but write nothing.

The store screenshots in `fastlane/metadata/android/<locale>/images/phoneScreenshots/` are copies of some of these
PNGs (`start`, `ride`, `navigation`, `handlebar`, `light_evening`, `stats`, `rides`; `_en` for en-US, `_de` for
de-DE); re-copy them after visible UI changes.

## Structure for feature work

- `ui/theme`: `RoboBikeTheme` = Material 3 on the Robo Bike scheme (Material tonal palette from `BrandSeed`, the
  green #1F7A5C, via MaterialKolor) or, with the setting "Farben vom Hintergrundbild"
  (`GeneralSettings.wallpaperColors`), on the wallpaper's (Material You); light or dark as the system is
  (`appColorScheme`). `AppColors` are the app's tokens, derived from that scheme by `ColorScheme.toAppColors(dark)`;
  `Context.appColors()` serves code outside composition (notifications, widget). Roles: page = `background`, cards =
  `surfaceContainer` (tonal, no border), settings row icon badges = `primaryContainer`, progress track =
  `secondaryContainer`, danger = `error*`. Amber tones (light on, consumed battery, waiting, maintenance) are fixed
  colours with a meaning, only harmonized with the scheme's primary, so they stay amber with any wallpaper. Map
  colours are fixed (`mapLand`, `mapPark`, `mapWater`). `AppShapes`: 8/12/16/24/28 (chips, banners, text
  fields/segments, cards, hero cards and sheets); buttons keep Material's pill. `AppTypography` holds the artboards'
  named styles and is mapped onto the Material type scale (`toMaterial()`). `AppTheme.colors`/`AppTheme.type` are
  for bespoke drawings and big numbers; Material components pick their colours from the scheme by themselves.
  Fonts: Outfit (bundled, OFL) for big numbers and page titles, the system font for all other text.
  `Modifier.cssLineBox(style)` gives big numbers the artboards' CSS `line-height` < 1.
- `ui/components`: the shared components, thin wrappers around **stock Material 3** components. Never hand-draw a
  look-alike of a standard control; if a variant is missing, add it here on top of the Material component.

  | Wrapper | Material 3 component |
  |---|---|
  | `ScreenFrame` / `Screen` | `Scaffold`; routes the content's nested scroll to the header's behaviour (pinned for `BackHeader`, exit-until-collapsed for `PageHeader`), insets (edge-to-edge) and page padding (`pagePadding`) |
  | `BackHeader` | `TopAppBar` (pinned; takes Material's scrolled-under container colour when content scrolls under it) with back `IconButton`; `overMedia` = transparent with tonal round buttons over a map until content scrolls under it (Fahrtdetail) |
  | `PageHeader` | `LargeTopAppBar` with the page title, collapsing as content scrolls (exit-until-collapsed); `subtitle`/`below` sit under it and do not collapse |
  | `BottomNavBar` | `NavigationBar` + `NavigationBarItem` |
  | `AppCard`, `HeroCard` | `Card` on `surfaceContainer`, tonal, no border (`onClick` = clickable card) |
  | `ListGroup` + `ListRow`, `SwitchRow`, `RadioRow`, `ValueRow`, `AppListItem` | `ListItem` rows in a card, whole row is the target (`clickable`/`toggleable`/`selectable`), trailing `Switch`/`RadioButton` |
  | `SwitchCard` | one `SwitchRow` in a card |
  | `AppSwitch` | `Switch` |
  | `SegmentedControl` | `SingleChoiceSegmentedButtonRow`; `SegmentedStyle.HEADER` / `HeaderTabs` = `PrimaryTabRow` |
  | `PillChip(Group)` | single-select `FilterChip`s |
  | `AppButton` (`ButtonKind`) | `Button`, `FilledTonalButton`, `OutlinedButton`, `TextButton` (+ `DANGER_*` in the error colour) |
  | `RoundIconButton`, `Stepper` | `IconButton` / `FilledTonalIconButton`, `OutlinedIconButton` |
  | `ProgressBar`, `StepIndicator` | determinate `LinearProgressIndicator` |
  | `AppTextField` | `OutlinedTextField` (label, suffix, supporting/error text) |
  | `AppDialog`, `TextInputDialog`, `ConfirmDialog` | `AlertDialog` with `DialogButton`/`CancelButton` text buttons |
  | `StatusBanner`, `StatusChip`, `IconBadge` | tonal `Surface` (status, not interactive) |

  Bespoke on purpose: big numbers (`NumberWithUnit`, `ValueTile`, `StatColumn`), `SegmentBar`, `MonoBlock`, charts
  (`components/chart`, sun curve), the map and its overlays, the Lenkermodus HUD (its buttons are Material buttons).
  List items bring their own 16 dp side padding: put them in a card without horizontal padding (`ListGroup`,
  `AppCard(contentPadding = PaddingValues(vertical = 4.dp))`), or use `Modifier.bleed(16.dp)` inside a padded card.
  Bottom sheets: `ModalBottomSheet` with full-bleed list items; dialogs: `AppDialog` (secondary actions go into the
  content, the button row holds confirm/dismiss only).
- `ui/Routes.kt` + `ui/AppShell.kt`: one route per artboard. Each screen is `@Composable fun XScreen(nav: AppNavigator)`
  in its own package (`ui/range`, `ui/light`, `ui/nav`, `ui/handlebar`, `ui/rides`, `ui/stats`, `ui/bike`,
  `ui/settings`, `ui/diagnostics`, `ui/setup`); keep the signature.
  Screen wiring helpers in `ui/components/Locale.kt`: `rememberActiveBike()` (the active bike as state),
  `currentLocale()` in Compose / `ctx.uiLocale()` outside it (widget, notifications: the same app language), and
  `formatAsOf(...)` for the "Stand 14:32" of last known values.
- Strings: one file per feature, `res/values/strings_<feature>.xml` (English, default) and
  `res/values-de/strings_<feature>.xml`. Prefix keys with the feature name. No literal UI text in code.
  Words and states several features show ("Erlauben", "Licht an", "Nicht verbunden", "Speichern") live once
  in `strings_common.xml` (`common_*`, `status_*`); reuse them instead of adding a copy under a feature key.
- Settings: declare keys next to the feature, e.g. `object LightSettings { val mode = Setting.enum("light.mode", LightMode.AUTO, Scope.PER_BIKE) }`,
  read/write with `ctx.settings[key]` / `settings.get(key, bikeId)` / `settings.flow(key, bikeId)`. Per-bike data
  (A2) uses the active bike (`ctx.bikes.active()`); per-bike files go in `ctx.bikes.dir(id)`. Schema changes go into
  `settings/Migration.kt` with a test.
- Units: format distances/speeds only through `UnitFormatter` (`rememberUnitFormatter()` in Compose) and
  `unitLabel(...)`. Numbers in the rider's unit (charts, steppers, parsing) and per-distance values (% per km/mi)
  come from `UnitFormatter` / `UnitSystem` too (`toUnit`, `fromUnit`, `perDistance`, `distanceUnit`); no
  hand-written km/mi factors elsewhere.
- Cross-feature state: small holders such as `range/RangeState`, `maintenance/MaintenanceState` (null = unknown);
  the owning feature is their single writer. `Repo` and `Locations` follow one pattern: private mutable flows,
  public read-only `StateFlow`s, and internal publish functions grouped by their writer.
- Bike data: `Repo.live` is the connected bike and its latest report (`Live(bikeId, report)`), non-null only while
  connected. Read a bike through `Repo.snapshot(settings, bikeId)` / `Repo.snapshotFlow(...)` (Compose:
  `rememberSnapshot(bikeId)`): live if it is the connected bike, else its `LastKnown` (`asOfMs` set). Never combine
  `Repo.conn` with a report yourself; the active bike need not be the connected one (A2).
- Connection status: `ConnectionStatus` (`CONNECTED`, `CONNECTING`, `LOST`, `WAITING`, `OFF`) from the pure
  `connectionStatus(...)`, the same for Start, Lenkermodus, widget and notification (Compose:
  `ConnectionController.status()` / `rememberConnectionStatus`); texts only through `status.textRes(detail)`
  (`status_*` in `strings_common.xml`).
- Text outside Compose (widget, notifications): `Measure.text(ctx)` / `UnitLabel.res()` from `units/UnitText.kt`.

## Shared platform APIs (map, location, ride data, error log)

Use these instead of building your own. Pure logic is unit-tested next to it (`ui/components/map/MapModelTest`,
`location/LocationPolicyTest`, `ride/RideDataTest`, `errors/ErrorLogTest`).

### Map — `ui/components/map`

`BikeMap(content, modifier, mode, onClick, onPick)`: MapLibre Native (`org.maplibre.gl:android-sdk`) with the
OpenFreeMap "Positron" style (no key, no account), recoloured to the artboard palette; overlays in the artboards'
look; the camera fits all content; the OSM/OpenMapTiles credit is always shown. `MapMode.CARD` = no gestures,
`onClick` makes the whole map one tap target; `MapMode.INTERACTIVE` = pan/zoom, camera fitted only once,
`onPick` receives the tapped point (e.g. home location). Under Robolectric and in previews `BikeMap` draws
`MapStandIn` (same overlays on the land colour), so screen screenshot tests need nothing special
(`screenshots/MapScreenshotTest` shows every overlay).

```kotlin
BikeMap(
    MapContent(
        tracks = listOf(MapTrack(samples.mapNotNull { s -> s.lat?.let { GeoPoint(it, s.lon!!) } })), // F3
        heat = heatLines,                                    // F8: HeatLines.build(tracks), off the main thread
        ring = MapRing(here, radiusM = 32_000.0),            // R4
        markers = listOf(MapMarker(here, MarkerKind.CURRENT), MapMarker(home, MarkerKind.HOME)), // or PARKED (W3)
    ),
    Modifier.fillMaxWidth().height(230.dp).clip(RoundedCornerShape(22.dp)),
    onClick = { nav.open(Routes.FULL_MAP) },
)
```

### Location — `location/`

One provider (`LocationProvider`, run by `BikeService`) for all features; read it through `Locations`:

- `Locations.fix: StateFlow<LocationFix?>`: newest fix of this process (point, time, accuracy, altitude).
  `Locations.best(ctx)`: newest of live fix, system last known and the persisted last position.
- `Locations.status: StateFlow<LocationStatus>`: `mode` (`OFF`; `OCCASIONAL` = one low-power fix on connect, and
  every 10 min while connected only while "Komme ich heim?" is on and a home is set
  (`LocationPolicy.periodicFixesWanted`); `CONTINUOUS` = GPS, only while `RideSettings.gpsTrack` is on, Q3) and `limit`, what is
  missing: `NO_PERMISSION`, `SYSTEM_LOCATION_OFF`, `NEEDS_BACKGROUND` (service started in the background with only
  "while in use": Android grants no location; fix = "allow all the time" or open the app), `TRACK_NEEDS_PRECISE`.
  `Locations.permissions(ctx)` works without the service. The decision is the pure `LocationPolicy.decide`.
- `Locations.requestFix(ctx)`: one low-power fix now (UI, e.g. "use my position as home").
- `Locations.parked(ctx, bikeId)`: `ParkedPosition(atMs, fix)` recorded when the connection to that bike's display
  ended (W3); `fix` is null if no position was known.

```kotlin
val status by Locations.status.collectAsStateWithLifecycle()
if (status.limit == LocationLimit.NEEDS_BACKGROUND) ExplainAllowAllTheTime()
val parkedAt = Locations.parked(ctx, bike.id)?.fix?.point
```

### Ride data — `ride/`

Ride CSVs (`bikes/<id>/rides/*.csv`) stay the only source. `RideIndex` caches one summary per file and checks
each entry against the file's size and mtime on every load (bumping `RideIndex.VERSION` rebuilds all), so
summaries cannot disagree with the files. Columns: `epochMs,lat,lon,speedKmh,batteryPct,gear,tripKm,odoKm,altM`;
`gear` is the assist level, `altM` is new (8-column files from before still parse, with `altM = null`).

- `RideRepository.summaries(ctx, bikeId): Flow<List<RideSummary>>`, newest first; updates on ride start, every
  30 s while recording, ride end and delete. `RideSummary`: distance, duration, moving time, battery
  start/end/used, max speed, climb, `hasTrack`, `levels: List<LevelStats>` (km, battery %, time per assist level).
- `RideRepository.samples(ride)`, `tracks(rides)` (positioned samples, for the heatmap), `delete(ride)`,
  `exportGpx(ctx, ride)` (share Uri, F9).
- `RideStats.aggregate(rides, StatsPeriod.MONTH, LocalDate.now())`: total plus per-day (week/month) or per-month
  (year) buckets with km, time, battery % and `charges` (F6). `RideStats.levelConsumption(rides)`: battery % per
  km per assist level across rides (R3).

```kotlin
val rides by remember(bike.id) { RideRepository.summaries(ctx, bike.id) }.collectAsStateWithLifecycle(emptyList())
val month = RideStats.aggregate(rides, StatsPeriod.MONTH, LocalDate.now())
```

- `RideTemperatures.read(csv)`: outdoor temperature at ride start/end (`RideTemperature`, °C, either may be null),
  from `<ride>.weather` next to the CSV ("start|end,epochMs,°C" per line). The recorder takes it from Open-Meteo
  (`Weather.current`, cache shared with the light automation) at the newest known position, once at start and once
  at end; no file = no temperature (older rides, no position or network). Not in `RideSummary` / the index yet.
- `RideSummary.shape` (`RideShape`, 32 points along the GPS path, cached in the index) draws thumbnails and feeds
  `RouteMatcher` (recurring routes, F5; method documented there). `RecurringRoutes.groups(ctx, bikeId)` gives
  `RouteGroups` (routes + route per ride id); names are the per-bike setting `RouteSettings.routes`.

### Charts — `ui/components/chart`

`LineChart(points, xRange, yTicks, xTicks, contentDescription, stepped, average)` (Fahrtdetail look) and
`BarChart(values, xTicks, reference, contentDescription)` (Statistik look); `ChartScale.ticks/downsample/reference`
for nice axes. Labels are formatted by the caller (units, locale).

### Backup — `backup/`

`Backup` (zip: manifest, all settings with types, all files of `filesDir` except caches and `log.txt`) and `Restore`
(staged, journaled install, completed or discarded in `App.onCreate`). New feature data in `filesDir` or `Settings`
is included automatically. Screen: route `Routes.BACKUP` (Einstellungen › Export und Sicherung).

### Error log — `errors/`

`ErrorLogStore.entries(ctx, bikeId): Flow<List<ErrorEntry>>`, newest first. `LIVE` entries come from byte 0 of the
live report (time, odometer, position if fresh; one entry per episode of `DriveErrorTracker`, see below). `HISTORY` entries come from the error
register `0xA5:120`, read once per connection (only entries not seen before and not already logged live; the real
time is unknown, `seenAtMs` = when the app first saw it). `rawHex` keeps the bytes; `entry.error` decodes them with
the current `ErrorCodes` rules (`code`, `known`; texts via `ErrorCodes.resourceKey(code)`).

## Notifications, widget, tile (`notify/`, `widget/`)

**Alerts**: one module for every notification a feature wants to raise. You own the *condition*; `notify/Alerts.kt`
owns channels (one per kind, so the rider can mute it in system settings), texts, the rider's on/off setting,
de-duplication and the tap target. Report the condition as often as you like (every report is fine):

```kotlin
Alerts.show(ctx, bikeId, Alert.HomeRange(remainingKm = 9.0, homeKm = 7.0))        // R5, condition holds
Alerts.resolve(ctx, bikeId, AlertKind.HOME_RANGE)                                 // condition no longer holds
Alerts.show(ctx, bikeId, Alert.ChargeReminder(batteryPct = 18, rideId = ride.id)) // W4, once per ride
Alerts.show(ctx, bikeId, Alert.MaintenanceDue(itemKey = "chain@1250", itemName = "Kette schmieren", dueInKm = 46.0)) // W1
```

- Same `key` (home: one episode; charge: the ride id; maintenance: item + interval, e.g. `"chain@<due odo>"`)
  = same alert: posted once, later calls only update the text silently; a rider who dismissed it is not bothered
  again. `resolve` ends the episode (home, charge, maintenance: the notification is removed too). If the same key
  comes back within the kind's quiet time (30 min home, 6 h charge, 1 day maintenance) it is shown silently, so a
  value flapping around a threshold does not ring twice. The memory is persisted per bike.
- Taps open Reichweite (home), Rad (maintenance) or Start (charge, drive error).
- Drive errors are wired already: one tracker (`errors/DriveErrorTracker`, run by `DriveErrorWatcher` in
  `BikeService` over `Repo.live`) turns byte 0 of report 1 into episodes (a code that flickers back within a minute
  is the same episode; it ends after a quiet minute, another code or a lost link). Its two subscribers are the error
  log (`ErrorLogWriter`: one entry and one log line per episode) and the alert (`DriveErrorAlerts`: shown once with
  title and advice from `strings_errors.xml`, resolved when the episode ends). Screens read the current error from
  `errors/DriveErrorState.current` (raw byte, code, first seen; null as soon as the byte is 0) and its text from
  `driveErrorText(ctx, code)`.
- Settings (`NotifySettings`): `homeRange`, `charge`, `maintenance`, `driveError` (row "Erinnerungen") and
  `connectionDetails` (row "Akku in der Verbindungsmitteilung"); strings `notif_setting_*`.

**Widget and connection notification** render one value, `widget/BikeGlance` (`bikeGlance(...)`: the bike's
`Snapshot` and `ConnectionStatus`; the widget shows the active bike, the notification the bike the service is
connected to; range from `RangeState`). The quick-settings tile reads the live report directly; it shares the
light switch state with every other surface. `BikeWidget.Updater` redraws the widget while
the service runs (connection/light at once, battery/range at most once a minute), and it is redrawn once when the
service stops; call `BikeWidget.refresh(ctx)` after changing something it shows while the service is not running
(the active bike is followed already: `BikeWidget.followActiveBike`, started by `App`). Every hand light switch
(Start, Lenkermodus, widget, tile, notification) goes through `light/ManualLight` (`set(ctx, on, from)`), and every surface shows the switch as
`light/LightControl` (`LightControl.of(liveReport, lampSwitch)`: ON / OFF only while connected, unlocked and the
display has a lamp switch; else LOCKED / NOT_CONNECTED / NO_LAMP).

## Simulated bike (debug builds only)

The debug build has its own app id, `dev.donrobo.ebike.debug` ("Robo-Bike Debug"), so it installs next to a
release install with separate data; the release app (`dev.donrobo.ebike`) only takes release-signed updates.

For an emulator (or any phone without the bike): a fake EKD01 display that `BikeService` talks to instead of
Bluetooth, controlled with `adb`. Code: `app/src/debug/kotlin/dev/donrobo/ebike/sim/`, bundled ride
`app/src/debug/assets/sim/ride.csv`, receiver in `app/src/debug/AndroidManifest.xml`. The release APK contains none
of it (only the stub `app/src/release/.../sim/SimulatorHook.kt`, which returns null).

**How it plugs in.** `Ekd01Link` (auth, framing, request/response matching, the "answer longer than asked" cut) runs
over a `LinkTransport`: `GattTransport` for the real display, the simulator's transport when the active bike's
display MAC is `02:00:00:00:51:4D` (`SimulatorHook`, the one branch in `BikeService.loop`). Everything
above the transport is production code. The fake display (`FakeDisplay`) speaks the byte protocol of
[PROTOCOL.md](PROTOCOL.md) and mirrors the real device: AES challenge per read of `0x10:0`; register contents from
a register dump of a real display (serial number made up); reads answer with the firmware's own length (`0xA5:120` → 32 bytes);
registers the real display never answered stay silent (timeout); writes are acked `00` and reflected (lamp
`0xA5:166` → light bit of the next report, `0xA5:164` → assist level, `0x10:14` → lock state, clock `0x10:62/66/70`
read back as written; the simulated clock does not tick); navigation op-3 frames get no answer and are recorded
(`sim display: shows …` in the log); report 1 every 500 ms, report 9 every second. Assumption where the real device
was never observed: the answer to the feature block `0xF1:1` (default advertises lamp, notifications, clock, BLE
unlock, unit, level, backlight, unlock level, error codes; `set --es features none` = no answer as an unknown display).

**Time.** The app always runs on real time; nothing in it is faked. `speedup` only compresses the scenario: at 10x a
real second advances the ride by 10 s, reports still come twice a second, and each carries what the bike showed at
that ride moment. So with speedup > 1 the recorder writes one sample per real second, a recorded ride lasts
duration / speedup while covering the full distance (average speed speedup times too high), and the 5-minute
end-of-ride rule, the 60 s idle stop of the service, alert quiet times and the 10-minute location fixes all count
real time. Only speedup 1 is a fully truthful run; use a high speedup to see values move, not to check ride stats.

**Scenarios.** `ride` (default ride: one real 25-minute, 9.58 km ride, positions/absolute
time/odometer removed, speed scale corrected; battery 100 → 88 % with its real sag and recovery; level 5 throughout;
afterwards the bike stands still with the last values) and `still` (speed 0; the initial scenario). Manual values
(`set`) override the scenario until released. The display's memory (odometer, trip, battery, error history) survives
process restarts (preferences file `sim_display`); light goes off and speed to 0 when the display is switched off.

### Setup on the emulator (once)

```sh
./gradlew :app:installDebug
P=dev.donrobo.ebike.debug
for p in BLUETOOTH_CONNECT BLUETOOTH_SCAN POST_NOTIFICATIONS ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION; do
  adb shell pm grant $P android.permission.$p; done
adb shell dumpsys deviceidle whitelist +$P      # lets the receiver start the foreground service from the background
adb shell am start -n $P/.ui.MainActivity       # first launch: clears the "stopped" state; shows setup (no display yet)
sim() { adb shell am broadcast -f 0x20 -n $P/.sim.SimControlReceiver --es cmd "$@"; }   # bash/zsh helper
sim pair                                        # the active bike (or a new one) gets the simulated display
adb shell am force-stop $P && adb shell am start -n $P/.ui.MainActivity   # now opens on Start, not setup
```

Without adb: Diagnose (debug builds only) has a card "Simuliertes Display" that pairs the active bike with the
simulated display, switches it on (= `start`) and off (= `off`) and picks the scenario (standing, ride 1x, ride 10x).
Its code (`sim/SimActions.kt`, `SimulatorHook.DiagnosticsCard`) and strings (`src/debug/res`) are in the debug source
set; the release stub draws nothing.

`pair` is the debug stand-in for setup step 1: it runs the same `BikeCompanion.adopt` as the chooser result, minus
the Companion Device dialog (the real pairing path is unchanged). Expected log: `display 02:00:00:00:51:4D paired with
bike <id>` and `sim: simulated display 02:00:00:00:51:4D given to bike <id> (<name>), now active`. Optional
`--es name Name`. Because the emulator has no companion association, the Connect button would still open the system
chooser; cancelling it starts the service anyway, which then connects to the simulator. Prefer `sim start`.

Values with spaces need inner quotes because `adb shell` joins arguments: `--es title "'150 m - Nach links abbiegen'"`.
Numbers may be given as `--ei`, `--ef` or `--es`; booleans as `--ez` or `--es true|false`. Every command logs a line
starting with `sim:`. Read the log with `adb shell run-as $P cat files/log.txt` (or `adb logcat -s ebike`); the
receiver is guarded by the DUMP permission, so only the adb shell (and the system) can send these.

### Commands

| Command | Extras | What it does (as on the real bike) |
|---|---|---|
| `start` | `--es scenario ride\|still`, `--ef speedup F` (default 1) | switch the display on and press Connect (lifts a Disconnect pause, starts `BikeService`) |
| `on` | same | switch the display on only; the presence rule decides (auto-connect on, not paused, no other bike connected) |
| `off` | | switch the display off: link drops, scenario stops; like the presence "disappeared" event it ends a Disconnect pause |
| `drop` | | abrupt link loss, display stays on (out of range); the service reconnects after 5 s |
| `scenario` | `--es scenario ride\|still`, `--ef speedup F` | restart with that scenario (also while connected) |
| `set` | `--ef speed`, `--ei battery`, `--ei level`, `--ei gears`, `--ez light`, `--ez locked`, `--ef odo`, `--ef trip`, `--es features default\|none\|<52 hex>`, `--es release speed,battery,level\|all` | manual values; `light` = the button on the bike; `features` applies at the next connection |
| `error` | `--es code 21` (BCD, as the app decodes) or the byte itself, `--es raw 0x21` / `--ei raw 33`; `--ez history false` | current error (report byte 0) and, by default, the next free slot of `0xA5:120` |
| `error-clear` / `history-clear` | | report byte 0 back to 0 / empty the history register |
| `nav-maps` | `--es title`, `--es text`, `--es summary`, `--es icon LEFT` (only if the text names no turn) | a Google Maps notification: texts go through the listener's parser (`MapsExtract`) into `Repo.mapsNav`; the arrow-image classifier is not exercised |
| `nav-maps-end` | | Maps notification gone (the forwarder keeps the hint for 10 s, then ends) |
| `nav-test` / `nav-test-end` | `--ei dist 200`, `--es icon LEFT\|RIGHT\|STRAIGHT\|SLIGHT_LEFT\|…\|ARRIVED`, `--ei remaining 1500` | the Diagnose screen's test maneuver (`TestManeuver`, shown in `Repo.testNav`; ends by itself after 30 s) |
| `status` | | logs the simulator state (scenario, display values, what the display shows) and what the app sees |

Location comes from the emulator: `adb emu geo fix <lon> <lat>` (longitude first), e.g. `adb emu geo fix 16.3721 48.2085`.

### Walkthrough and expected log (files/log.txt)

1. `sim start --es scenario ride --ef speedup 10` →
   `sim: scenario ride 0/1525 s at 10.0x`, `sim: display switched on (…)`, `sim: Connect, BikeService started`,
   `sim: connecting to the simulated display 02:00:00:00:51:4D`, `sim: link up`, then the production sequence:
   `TX 11->10 op=01 reg=0 [04]`, `RX 10->11 op=04 reg=0 [<4 random bytes>]`, `TX … op=20 …`, `RX 10->11 op=20 reg=0 [00]`,
   `authenticated`; reads of `a5:0`, `a5:24`, `a5:72`, `a3:24`; `feature block 00 00 00 87 … : LAMP_SWITCH, PUSH_NOTIFY,
   DATE_TIME, BLE_UNLOCK, UNIT, GEAR, BRIGHTNESS, BLE_UNLOCK_LEVEL, ERROR_CODE`; clock writes 62/66, read-back
   `clock: wrote <s> (little-endian), offset <s>s, read back … -> accepted`, `clock: reg 70 = eb 31 ee 5f …`;
   `TX 11->a5 op=01 reg=120 [10]` answered with 32 bytes (16 zero, then `45 4b 44 30 31 2d 42 46 20 …`).
   With the light mode on Auto and a position (`geo fix`): `auto light: …` and, after dark, `TX 11->a5 op=02 reg=166 [01 00]`.
   Within a second: `Ride started: <date>_<time>.csv`. Start/Lenkermodus show live speed, battery, level; the ongoing
   notification and the widget show the bike as connected. Every minute: `sim: on, phone connected, scenario ride …`.
   After 1525 / 10 ≈ 153 s: `sim: replay finished (…)`; 5 real minutes later `Ride closed: ride ended (no movement
   for 5 min)`.
2. Light by hand (Start switch, tile, widget or notification): `light by hand (<from>): on`, `TX 11->a5 op=02 reg=166
   [01 00]`, `RX a5->11 op=05 reg=166 [00]`; the next report has the light bit (Start shows it on). `sim set --ez light
   true` instead simulates the button on the bike (no TX; the automation counts it as manual).
3. `sim set --ei battery 20` then wait for the ride to end (or `sim off` and let the service stop) → `alert charge: …`
   (threshold 30 %, charge reminders on). `sim set --es release battery` returns to the scenario's value.
4. `sim error --es code 21` → `sim: drive error raw 0x21 raised, …`, `drive error: raw 0x21 (code 21)` (once per
   episode), `alert drive_error: Speed sensor error` (German UI: the German title). The history
   entry appears at the next connection's register read (`error register: 1 new entry …`) unless already logged live.
   `sim error-clear` removes the banner at once; the episode (and the alert) ends after a quiet minute.
5. `sim nav-maps --es title "'150 m - Nach links abbiegen'" --es summary "'12 min - 3,4 km - 14:05'"` →
   `sim: Maps notification … -> NavState(distM=150, icon=LEFT, remainingM=3400), Maps nav started`, `nav: start
   (GOOGLE_MAPS)`, `TX 11->f1 op=03 reg=0 [01 01]`, `sim display: shows navigation open, no update yet`,
   `TX 11->f1 op=03 reg=0 [02 02 96 00 00 02 …]`, `sim display: shows LEFT in 150 m (box 150 m), 3400 m to go`.
   Repeat with other texts to move along; `sim nav-maps-end` → 10 s later `nav: end`, `TX … [.. 03]`,
   `sim display: shows no navigation`. (Every Maps hint goes to the display, however far away the turn is.)
6. `sim drop` → `sim: link lost (simulated link loss)`; ~5 s later `sim: connecting …`, `sim: link up`, the whole
   connect sequence again (the ride continues; it is the same display).
7. `sim off` → `sim: display switched off`, `sim: link lost (display switched off)`; the parked position is stored
   (Rad tab); Start, Lenkermodus, widget and notification say "Getrennt"; every ~25 s `sim: connecting …` /
   `connection: connect timeout`; about 75 s after `off`:
   `no connection for 60 s, stopping` (notification gone). `sim on` restarts it through the presence rule
   (`sim: display appeared, BikeService started`, or `sim: display appeared, not connecting: <reason>`).
8. Disconnect in the notification or the app → the bike is paused: `sim on` logs `… not connecting: paused by
   Disconnect until <bike> is switched off`; `sim off` logs `sim: display gone, auto-connect for bike <id> active
   again`; then `sim on` connects.

If a command logs `cannot start BikeService: …ForegroundServiceStartNotAllowedException…`, the app was in the
background and not on the power allowlist: open the app or rerun the `deviceidle whitelist` line.

## Releases

Version: `versionCode` and `versionName` in `app/build.gradle.kts`, written as literals because F-Droid's
checkupdates reads them there. For a release: bump both, add
`fastlane/metadata/android/{en-US,de-DE}/changelogs/<versionCode>.txt`, commit, then tag `v<versionName>`
(e.g. `v0.1.0`) and push the tag. `.github/workflows/release.yml` runs only for `v*` tags (never for pull
requests): it checks that the tag matches `versionName`, runs the unit tests, builds the signed release APK,
verifies the signature and attaches `robo-bike-<version>.apk` plus its SHA-256 to a GitHub Release whose notes are
the en-US changelog.

Creating a release key (once; keep the keystore and its password safe — every future update must be signed with
the same key):

```
keytool -genkeypair -keystore release.jks -storetype PKCS12 -alias robobike -keyalg RSA -keysize 4096 \
  -validity 36500 -dname "CN=Robo Bike"
```

Repository secrets for the release workflow:

| Secret | Content |
|---|---|
| `ROBOBIKE_KEYSTORE_BASE64` | the release keystore, base64 (`base64 -w0 release.jks`) |
| `ROBOBIKE_KEYSTORE_PASSWORD` | keystore password |
| `ROBOBIKE_KEY_ALIAS` | key alias |
| `ROBOBIKE_KEY_PASSWORD` | key password |

The build reads the same values from environment variables (`ROBOBIKE_KEYSTORE_FILE` = path to the keystore,
`ROBOBIKE_KEYSTORE_PASSWORD`, `ROBOBIKE_KEY_ALIAS`, `ROBOBIKE_KEY_PASSWORD`) or, locally, from a gitignored
`keystore.properties` in the repo root:

```
storeFile=/path/to/release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

Without either, `./gradlew :app:assembleRelease` produces `app-release-unsigned.apk`; that is what F-Droid builds
(it signs with its own key, or, for reproducible builds, compares against the APK on GitHub Releases).

The release build is meant to be reproducible: R8 + resource shrinking, no VCS info (`vcsInfo.include = false`),
no dependency metadata block (`dependenciesInfo`, which F-Droid and IzzyOnDroid reject), fixed zip timestamps from
AGP. Two clean builds from different checkout paths should give byte-identical unsigned APKs; please report it if
they do not. No Google Play services or other proprietary libraries: the map is MapLibre with OpenFreeMap tiles,
weather comes from Open-Meteo.

Store metadata (title, descriptions, changelogs, icon, screenshots) lives in `fastlane/metadata/android/`, the
layout F-Droid and IzzyOnDroid read.
