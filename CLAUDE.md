# Robo Bike — notes for AI coding agents

Read `docs/DEVELOPMENT.md` (conventions, screenshot tests, releases) and `docs/PROTOCOL.md` (BLE protocol) first.

## Safety (the display can be bricked)
- Never send op 7–0B to 0xA3. Do not write to 0xA3/0xA1. Only use commands documented as safe in `docs/PROTOCOL.md`;
  anything new is researched and agreed with the maintainer before it is sent to a real display.

## UI
- Stock Material 3 components (switches, navigation bar, tabs, buttons, cards, list items, sliders, dialogs, sheets,
  top app bars) themed with the app's colours and fonts — never hand-drawn look-alikes of standard controls. Use the
  wrappers in `ui/components`.
- Screens: stateless `XContent(state, actions)` + pure state mapping + screenshot test; scrolling screens also get
  scrolled-state shots (`compose.scrolled`). Look at the PNGs.
- Strings in English (default) and German ("du"), no hard-coded UI text; distances only through `UnitFormatter`.
- Explanations: at most one short visible line; anything longer goes behind an ⓘ (`InfoButton` → `InfoSheet`).

## Code
- One fact lives in one place; prefer structural fixes over special cases. Pure logic outside composables, with tests.
- Code, comments, commits in English. Keep `./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
  :app:assembleRelease` green (lint: zero errors).
- No proprietary dependencies (F-Droid), no analytics, no accounts, no new network endpoints without a reason in the
  privacy section of the README.

## Public repo hygiene
- This repo is public: no personal data in code, tests, fixtures or screenshots — fake MAC addresses
  (`00:11:22:33:44:xx`), synthetic or Vienna coordinates, no real serial numbers, street names or local paths.
