# Rover — Internet Control (PC/TG ↔ MQTT ↔ Phone ↔ BLE ↔ ESP32-S3)

```
PC console (desktop/index.html) ─┐
TG bot (bot/)                     ├──► MQTT (cloud, free) ─► Phone gateway ─► BLE ─► ESP32-S3 ─► motors/mines/legs/light
Phone telemetry (GPS/gyro/battery) ──────────────────────────────────────────►
Phone camera ─► YouTube stream ─► PC (watching)
```

The rover works **either from a PC console or via the TG bot** — both can run simultaneously.

## Contents

| Folder | What it is |
|---|---|
| `firmware/` | ESP32-S3 firmware (Arduino). BLE, motors, light, mines, legs, watchdog, tilt protection |
| `gateway/` | Android "phone gateway" app: MQTT↔BLE, phone sensor telemetry |
| `desktop/index.html` | PC console: browser-based joystick + video (YouTube) + telemetry |
| `bot/` | TG bot controller (Node.js): arrows, stop, light, mines, status |

## Quick start

1. **Firmware**: `firmware/rover/` — this is an Arduino sketch (open `firmware/rover/rover.ino`). Board: **ESP32-S3-Nano** (ESP32 board package), BLE is built into the core — no extra library needed.
2. **MQTT broker**: set your own broker address in gateway/console/bot settings
    (phone: `ssl://...:8883`, PC/bot: `wss://...:8884/mqtt`). Enter your broker credentials in gateway, console, and bot.
3. **Gateway**: install the APK on your phone, grant permissions (GPS, Bluetooth), enter broker and rover ID, tap "Start". The phone scans for `ROVER-S3` over BLE and connects.
4. **PC**: open `desktop/index.html` in a browser, select broker (`wss://...`) and rover ID, paste YouTube stream URL. Move the joystick.
5. **TG**: `BOT_TOKEN=... npm start` in `bot/`.

## Protocol (packet signature)

Commands and telemetry are binary format (see `firmware/src/proto.h` and its mirror `Protocol.kt`).

### Command (PC/TG → ESP32), over BLE
`[0x52][seq][cmd][payload...][xor-all-prev]`

| cmd | payload |
|---|---|
| `0x01 DRIVE` | `int8 speed(-100..100), int8 steer(-100..100)` (differential drive) |
| `0x02 STOP` | — |
| `0x03 LIGHT` | `uint8 0/1` |
| `0x04 MINE` | `uint8 channel` |
| `0x05 LEG` | `uint8 channel, int8 pos(-100..100)` |
| `0x06 PING` | — |
| `0x07 RESET` | — |

Telemetry frame (10 bytes): `[0x54][flags][bat*10][Lpwm][Rpwm][mcu_temp][ack_seq][ack_status][tilt*10][leg_bits]`.

> Video is not WebRTC (as discussed): the phone camera streams to **YouTube Live** (use YouTube/StreamYard app), PC embeds it. Latency 3–10 s — fine for "eyes", not for precision joystick.

## MQTT topics

Prefix: `rover/<rover_id>/` (default `demo`).

| Topic | Direction | Format |
|---|---|---|
| `cmd` | PC→phone | `{"speed":-1..1,"steer":-1..1}` — continuous joystick |
| `action` | PC/TG→phone | `{"type":"stop"}` \| `{"type":"light","on":bool}` \| `{"type":"mine","channel":n}` \| `{"type":"leg","channel":n,"pos":-1..1}` \| `{"type":"ping"}` |
| `esptelemetry` | phone→PC | battery, L/R PWM, tilt, MCU temp, ack |
| `sensors` | phone→PC | GPS, IMU, phone battery (see `SensorHub`) |
| `status` | phone→PC | BLE and MQTT online/offline |

## Hardware: ESP32-S3-Nano + L298N

`config.h` → `MOTOR_DRIVER_TYPE 1` (L298N, IN1/IN2 + PWM on EN).

| ESP32-S3-Nano | L298N | Purpose |
|---|---|---|
| GPIO4 | IN1 | Left motor direction 1 |
| GPIO5 | IN2 | Left motor direction 2 |
| GPIO6 | ENA | Left motor PWM (speed) |
| GPIO7 | IN1 | Right motor direction 1 |
| GPIO8 | IN2 | Right motor direction 2 |
| GPIO9 | ENB | Right motor PWM (speed) |
| 5V | +12V/+5V | Motor power supply |
| GND | GND | Common ground (must connect to battery!) |

Other GPIOs: battery divider → `GPIO3`, light `GPIO10`, mines `GPIO11/12/13`, legs (servo) `GPIO14/15`. All configurable in `config.h`.

## Notes

- **Motor driver**: `MOTOR_DRIVER_TYPE` — `1` = L298N (IN1/IN2/PWM), `2` = MD12A (PWM+DIR), `0` = stub (log only).
- **Legs and tilt sensor** — stubs: connect `ESP32Servo` and MPU/lis3, enable in `config.h` (`HAS_TILT_SENSOR`, `LEG_*`).
- **Watchdog**: if BLE telemetry is flowing but no commands arrive for >1 s — motors stop, robot doesn't roll away.
- **Tilt protection**: `TILT_LIMIT_DEG=45°` cuts motors; currently no sensor — flag only (enable sensor).
- **Auto-reconnect**: the phone scans for `ROVER-S3`; the ESP32 re-advertises after disconnect.
