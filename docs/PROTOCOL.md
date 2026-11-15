# EKD01 display — BLE protocol (the part Robo Bike uses)

What the app needs to talk to an EKD01 display over Bluetooth LE. The protocol was worked out by the author for
interoperability: by analysing the display vendor's Android app and by observing a real display. Everything here
is either confirmed on a real display or marked as an assumption. Code: `app/src/main/kotlin/dev/donrobo/ebike/proto/`
(frames, auth, reports, navigation, error codes) and `ble/` (connection, request/response matching).

## Safety rules

The display is a gateway to the motor controller. Robo Bike only uses the reads and writes listed under
"Registers the app uses" and keeps to these rules; anyone extending the app should too:

- **Never send opcodes `0x07`–`0x0B` to target `0xA3`** (or any target). They are the firmware update (OTA)
  commands; the display can flash the motor controller with them. Bricking risk.
- **Do not write to `0xA3` or `0xA1`.** A write might be forwarded to the controller; nothing the app needs lives
  there.
- The app never changes speed limits, wheel size or motor parameters. (The vendor app cannot either: the speed
  limit is only settable in the display's own menu.)

## Transport

| What | UUID |
|---|---|
| Service (Nordic UART Service) | `6e400001-b5a3-f393-e0a9-e50e24dcca9e` |
| Write (phone → display) | `6e400002-b5a3-f393-e0a9-e50e24dcca9e` |
| Notify (display → phone) | `6e400003-b5a3-f393-e0a9-e50e24dcca9e` |

- Advertisement: flags `0x06`, only the HID service UUID `0x1812`, local name `EKD01-BF ` **with a trailing
  space**, manufacturer data with the last four bytes of the MAC. A scan filter on the exact name or on the UART
  service never matches; the app pairs through Android's Companion Device chooser (name pattern `EKD01.*`) and
  then connects by the stored address.
- Bonding (Just Works, no PIN) is needed before notifications can be enabled: unbonded, the CCCD write fails with
  ATT error `0x0E` and the display drops the link.
- Connection sequence: connect (LE transport) → bond → request MTU 512 → enable notify on `6e400003`
  (retried up to 3 times, 500 ms apart) → authenticate (below) → ready.
- Writes are not split: the longest frame the app sends (navigation, 27 bytes) needs an ATT MTU ≥ 30.

## Frame layout

```
55 AA  LEN  SRC  DST  OP  REG  DATA[LEN]  CK_L CK_H
```

- `LEN` = number of DATA bytes; the whole frame is `LEN + 9` bytes.
- Phone address `0x11`. Outgoing frames: `SRC = 0x11`, `DST = target`. Incoming: `SRC = target`, `DST = 0x11`.
- Checksum: `ck = 0xFFFF ^ sum(frame[2 .. len-3])` (every byte after `55 AA` and before the checksum),
  stored little-endian.
- Multi-byte integers are little-endian unless noted otherwise.
- One outstanding request per register; the vendor app uses a 1 s timeout per request.
- The display may answer a read with **more bytes than asked** (its own register length); the app keeps the
  first `len` bytes.

### Opcodes

| OP | Direction | Meaning |
|---|---|---|
| `0x01` | → | Read register. DATA = 1 byte, the expected length |
| `0x02` | → | Write register. DATA = exactly the register's length |
| `0x03` | → | Command (target `0xF1` only: navigation) |
| `0x04` | ← | Read response, DATA = register contents |
| `0x05` | ← | Write acknowledgement, 1 byte: `0` = OK, `1`/`2` = error |
| `0x06` | ← | Unsolicited report (from `0x10`), REG = report ID |
| `0x07`–`0x0B` | ↔ | Firmware update — **never send** (see safety rules) |
| `0x20` | ↔ | Authentication |

## Authentication

1. Read target `0x10` REG `0` (4 bytes) → challenge `c0 c1 c2 c3` (new on every read).
2. `block = c0 c1 c2 c3` followed by 12 zero bytes.
3. `response = AES-128-ECB-encrypt(key, block)` with the 16-byte ASCII key `2CTDU40qNyCgTjb1`.
4. Send `55 AA 10 11 10 20 00 <response, 16 bytes> CK CK`.
5. The display answers `55 AA 01 10 11 20 00 00 CK CK`; DATA `00` = accepted.

The key is the same for every display. It was obtained by analysing the vendor's app for interoperability
(it is a fixed constant there; no per-device derivation was found) and is confirmed against real displays.
After authentication all traffic is plaintext.

## Registers the app uses

Targets: `0x10` display core, `0xA5` display ("meter") info and settings, `0xA3` controller info mirror (read
only), `0xF1` feature block and navigation.

| Target | REG | Len | Access | Meaning |
|---|---|---|---|---|
| `0x10` | 0 | 4 | R | auth challenge |
| `0x10` | 62 | 4 | W, R | clock: UTC seconds (see "Time sync") |
| `0x10` | 66 | 4 | W | clock: time-zone offset in seconds, signed |
| `0x10` | 70 | 4 | R | third clock field, only logged |
| `0xA5` | 0 | 24 | R | display hardware version (ASCII, NUL-padded) |
| `0xA5` | 24 | 24 | R | display software version |
| `0xA5` | 72 | 16 | R | display serial number (the app shows only its last four characters) |
| `0xA5` | 120 | 16 | R | drive error history (see "Error codes") |
| `0xA5` | 166 | 2 | W | headlight: `[1, 0]` on, `[0, 0]` off; acked `00`, confirmed on a real bike |
| `0xA3` | 24 | 24 | R | motor controller software version (all zero on the test bike) |
| `0xF1` | 1 | 26 | R | feature block (below) |

The headlight is only switched while the bike is unlocked (report 1 byte 3 = 1) and the feature block has the lamp
switch flag, as the vendor app does.

### Feature block (`0xF1` REG 1, 26 bytes)

| Byte | Bits | Flag |
|---|---|---|
| 2 | 0–1 / 2–3 / 4–5 / 6–7 | coefficient selectors: max+speed / average speed / trip / odometer |
| 3 | 0 / 1 / 2 / 7 | lamp switch / push notifications / date-time / BLE unlock |
| 4 | 0 | firmware update controls |
| 5 | 0 | tyre pressure |
| 6 | 3 | unit setting |
| 7 | 7 / 0–2 | assist level setting / minimum level |
| 8 | 7 / 1–3 / 0 | backlight setting / max / min |
| 9 | 7 / 1–3 / 0 | BLE unlock level / max / min |
| 11 | 7 | error codes available |

Shorter answers are ignored (the vendor app requires ≥ 26 bytes); a display that does not answer is treated as
unknown.

## Reports

Pushed by the display (op `0x06`, SRC `0x10`) without a request once authenticated.

### ID 1 – Bike (live)

About every 0.5 s, 21 bytes.

| Byte | Field |
|---|---|
| 0 | current drive error code, 0 = none (see "Error codes") |
| 3 | lock state: 1 = unlocked, 0 = locked |
| 4 | headlight on (≠ 0) |
| 5 | current assist level |
| 6 | number of assist levels |
| 7 | battery % (voltage-derived: it sags under load and recovers) |
| 9–10 | speed, u16, **0.01 km/h** (the vendor app's "/10 × coefficient"; measured on recorded rides: only /100 makes the integrated speed match the odometer) |
| 11–14 | trip, u32, 10 m units |
| 15–18 | odometer, u32, 10 m units |

Example (bike standing, level 1 of 5, 72 %, trip and odometer 0.70 km):
`00 00 00 01 00 01 05 48 00 00 00 46 00 00 00 46 00 00 00 00 00`.

## Time sync

The vendor app writes three registers of target `0x10`: 62 (UTC seconds), 66 (offset seconds, little-endian)
and 70 (local seconds). Its code suggests big-endian for 62 and 70, but the never-synced factory value read from
a display (`e9 31 ee 5f` = 2020-12-31) only makes sense little-endian, and 70 reads back as about 62 + 2 s, not
UTC + offset. Robo Bike therefore writes 62 (little-endian by default, big-endian selectable in Diagnose) and 66,
does not write 70, reads 62 back and logs whether the value was kept.

## Navigation payload

Turn-by-turn hints are **commands**, not register writes: op `0x03`, target `0xF1`, REG `0`, DATA starts with
`[seq, cmd]`. No answer is expected. Confirmed on a real display (the arrow and distance appear; no capability bit
needed).

| Step | DATA | Example frame |
|---|---|---|
| Start (seq = 1) | `01 01` | `55 AA 02 11 F1 03 00 01 01 F6 FE` |
| Update (seq 2, 3, …) | `seq 02` + 16 bytes | `55 AA 12 11 F1 03 00 02 02 78 00 00 03 FA 00 00 02 00 00 00 00 DC 05 00 00 8C FC` (120 m → right, then 250 m → left, 1500 m to go) |
| Arrived | `seq 02`, icon 10, rest 0 | `55 AA 12 11 F1 03 00 03 02 00 00 00 0A 00 00 00 00 00 00 00 00 00 00 00 00 D9 FE` |
| End | `seq+1 03` | `55 AA 02 11 F1 03 00 04 03 F1 FE` |

`seq` is one byte and wraps 255 → 0 during a long navigation; gaps are tolerated.

### Update layout (DATA, 18 bytes)

| Index | Size | Field |
|---|---|---|
| 0 | u8 | seq |
| 1 | u8 | cmd = `0x02` |
| 2–4 | u24 | distance to the next maneuver, metres |
| 5 | u8 | icon of the next maneuver |
| 6–8 | u24 | distance from that maneuver to the one after, metres |
| 9 | u8 | icon of the maneuver after next |
| 10–12 | u24 | distance to the third maneuver, metres |
| 13 | u8 | icon of the third maneuver |
| 14–17 | u32 | remaining route distance, metres |

Unused maneuvers are 0. Units are always metres; no street names, times or speed limits are sent.

### Icon codes

| Code | Maneuver |
|---|---|
| 0 | unknown |
| 1 | straight |
| 2 | left |
| 3 | right |
| 4 | slight left |
| 5 | slight right |
| 6 | sharp left |
| 7 | sharp right |
| 8 | U-turn |
| 10 | arrived |

## Error codes

The display exposes drive errors in two places, one byte per code:

- **Live**: byte 0 of report 1, 0 = no error.
- **History**: register `0xA5:120`, read only if the feature block's "error codes" flag is set. The app uses the
  first 16 bytes; empty slots are `0x00` (and `0xFF`, erased flash). The test display answers with 32 bytes:
  16 zero bytes followed by the start of register 136 (the BLE name), i.e. its own register map gives 120 a length
  of 32.

How the firmware encodes a code into the byte (BCD such as `0x21` → 21, binary, or ASCII digit pairs) could not be
settled because no real error has been observed yet. The decoder (`proto/ErrorCodes.kt`) uses BCD for the live
byte and an automatic choice (ASCII pairs if all used bytes are ASCII digits, else BCD) for the history; the raw
bytes are always kept. Texts for the codes (`res/values*/strings_errors.xml`) follow Bafang's public display
error table (DP E12 user manual, section "Error code definition"), condensed.
