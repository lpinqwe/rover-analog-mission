# Rover Architecture — C2 & C3 Mermaid Diagrams

## C2 — System Context

The Rover system consists of four main containers interacting over BLE and MQTT:

```mermaid
C4Context
    title C2 — System Context: Rover telepresence system

    Person(operator, "Operator", "Remote user controlling the rover via Telegram or browser")
    Person(phoneOperator, "Phone Operator", "Local user with direct BLE control in the Android app")

    System_Boundary(roverSystem, "Rover System") {
        System(firmware, "ESP32-S3 Firmware", "Rover control: motors, sensors, camera; BLE GATT server")
        System(gateway, "Android Gateway", "Bridges BLE and MQTT; phone sensor source; control UI")
        System(bot, "Telegram Bot", "Remote control and status notifications via Telegram")
        System(desktop, "Desktop App", "Browser / Electron control and telemetry UI")
        SystemDb(broker, "MQTT Broker", "Public broker: HiveMQ / EMQX / Mosquitto")
    }

    BiRel(firmware, gateway, "BLE (binary protocol, 13/18 bytes)")
    BiRel(gateway, broker, "MQTT (JSON topics)")
    BiRel(bot, broker, "MQTT")
    BiRel(desktop, broker, "MQTT / WSS")

    Rel(operator, bot, "Telegram commands / status")
    Rel(operator, desktop, "Control + telemetry UI")
    Rel(phoneOperator, gateway, "On-device joystick UI")
```

### Actors

| Actor | Description |
|-------|-------------|
| **Rover Hardware** | ESP32-S3-Nano with L298N motor driver, camera, IMU, temperature sensor |
| **Phone Sensors** | Android device accelerometer/gyroscope (gravity, tilt) |
| **Operator** | Remote Telegram/browser user |
| **Phone Operator** | Local user driving directly via BLE |

### Key Interactions

1. **ESP32 ↔ Android Gateway** via BLE (bidirectional commands and telemetry)
2. **Android Gateway ↔ MQTT Broker** via MQTT (publishes telemetry, subscribes to commands)
3. **Telegram Bot ↔ MQTT Broker** via MQTT (receives telemetry, sends commands)
4. **Desktop App ↔ MQTT Broker** via MQTT/WSS (receives telemetry, sends commands)

---

## C3 — Component Level

Everything inside the system boundary, per container:

```mermaid
C4Component
    title C3 — Component: internal structure of each container

    Person(operator, "Operator")

    System_Boundary(roverSystem, "Rover System") {
        SystemDb(broker, "MQTT Broker", "HiveMQ / EMQX / Mosquitto")

        Container_Boundary(fw, "ESP32-S3 Firmware (C++)") {
            Component(roverCore, "Rover Core", "Main loop, state machine, command processing")
            Component(bleSvc, "BLE Service", "NimBLE GATT server: CMD write / TELEMETRY notify")
            Component(motorDrv, "Motor Driver", "L298N / MD12A (MC33926) / stub, switch in config.h")
            Component(proto, "Protocol", "Binary encode/decode, magic+version+seq")
            Component(sensorHub, "Sensor Hub", "MPU6050 IMU, temperature, battery voltage")
            Component(watchdog, "Watchdog", "30s motor-safety timeout on BLE loss")
        }

        Container_Boundary(and, "Android Gateway (Kotlin)") {
            Component(mainAct, "MainActivity", "Settings / Control / Log tabs, joystick")
            Component(gwSvc, "GatewayService", "Foreground service, BLE | MQTT bridge")
            Component(ble, "BleClient", "BLE central, self-healing scan/reconnect/backoff")
            Component(mqtt, "MqttClient", "Paho MQTT v3 wrapper, ensureConnected auto-repair")
            Component(sensorHubA, "SensorHub", "Gravity/gyro readings -> sway/tilt commands")
            Component(tg, "TgNotify", "Rate-limited Telegram alerts (6 msgs/min)")
        }

        Container_Boundary(tb, "Telegram Bot (Node.js)") {
            Component(cmdHandler, "Command Handler", "/start, /status, callback buttons")
            Component(botMqtt, "MQTT Client", "Subscribe telemetry, publish commands")
            Component(statusRep, "Status Reporter", "Periodic status, error/crash alerts")
            Component(yt, "YouTube Parser", "Validate + forward URLs to the rover")
        }

        Container_Boundary(da, "Desktop App (HTML/JS)") {
            Component(ui, "UI Renderer", "Connection, video, telemetry widgets")
            Component(joy, "Joystick", "Touch/mouse steering -> MQTT commands")
            Component(statD, "Status Display", "Battery, motors, temp, tilt, GPS, gyro")
            Component(mqttUi, "MQTT Client", "Paho MQTT over WSS")
        }
    }

    Rel(operator, mainAct, "joystick / buttons")
    Rel(mainAct, gwSvc, "bound service / intents")
    Rel(gwSvc, ble, "BLE commands / telemetry callbacks")
    Rel(ble, bleSvc, "BLE GATT (binary)")
    Rel(gwSvc, mqtt, "publish / subscribe")
    Rel(sensorHubA, gwSvc, "sway/tilt commands")
    Rel(tg, gwSvc, "alerts on errors")

    Rel(mqtt, broker, "MQTT")
    Rel(botMqtt, broker, "MQTT")
    Rel(mqttUi, broker, "WSS")
    Rel(cmdHandler, botMqtt, "commands in / status out")
    Rel(botMqtt, statusRep, "telemetry -> Telegram")
    Rel(joy, mqttUi, "movement commands")
    Rel(mqttUi, statD, "incoming telemetry")

    UpdateRelStyle(mqtt, broker, $textColor="blue", $lineColor="blue", $offsetX="-180")
    UpdateRelStyle(botMqtt, broker, $textColor="blue", $lineColor="blue", $offsetX="120")
    UpdateRelStyle(mqttUi, broker, $textColor="blue", $lineColor="blue", $offsetX="120")
    UpdateRelStyle(ble, bleSvc, $textColor="green", $lineColor="green")
```

### ESP32 Firmware (C3)

| Component | Responsibility |
|-----------|----------------|
| **Rover Core** | Main control loop, state machine, command processing |
| **BLE Service** | NimBLE GATT server for communication (CMD / TELEMETRY) |
| **Motor Driver** | Abstracted motor control (L298N, MD12A/MC33926, or stub) |
| **Protocol** | Binary protocol for commands and telemetry (C→S: 13 bytes, S→C: 18 bytes) |
| **Sensor Hub** | IMU (MPU6050), temperature, voltage monitoring |
| **Watchdog** | 30-second safety timeout, kills motors on disconnect |

### Android Gateway (C3)

| Component | Responsibility |
|-----------|----------------|
| **MainActivity** | Settings UI (broker, credentials), Control tab (BLE joystick), Log tab |
| **GatewayService** | Foreground service bridging BLE ↔ MQTT, owns all networking |
| **BleClient** | Self-healing BLE central with auto-scan, reconnect, backoff |
| **MqttClient** | Thin Paho MQTT v3 wrapper with auto-reconnect via `ensureConnected()` |
| **SensorHub** | Reads phone gravity sensors, sends tilt data to ESP32 |
| **TgNotify** | Rate-limited Telegram notifications (6 msgs/min global limit) |

### Telegram Bot (C3)

| Component | Responsibility |
|-----------|----------------|
| **Command Handler** | Processes /start, /status, button callbacks |
| **MQTT Client** | Subscribes to `esptelemetry`, `phonebattery`; publishes commands |
| **Status Reporter** | Periodic status updates, crash reports |
| **YouTube Parser** | Validates YouTube URLs and forwards to rover |

### Desktop App (C3)

| Component | Responsibility |
|-----------|----------------|
| **UI Renderer** | Web-based control interface (video, buttons, connection) |
| **MQTT Client** | Paho MQTT over WSS |
| **Joystick** | Touch/mouse joystick for rover movement |
| **Status Display** | Real-time telemetry (battery, motors, temperature, tilt, GPS) |

---

## Sequence Diagrams

### Command Flow (Phone → Rover)

```mermaid
sequenceDiagram
    participant PU as Phone UI
    participant GS as GatewayService
    participant BC as BleClient
    participant ESP as ESP32 Firmware

    PU->>GS: tap "Forward"
    GS->>BC: writeCommand(move, lPwm, rPwm)
    BC->>ESP: BLE write (13 bytes, seq=N)
    ESP-->>BC: BLE notify (18 bytes telemetry)
    BC-->>GS: onTelemetry(data)
    GS-->>PU: update live display
```

### Telemetry Flow (Rover → Phone / Bot / Desktop)

```mermaid
sequenceDiagram
    participant ESP as ESP32 Firmware
    participant GS as GatewayService
    participant BR as MQTT Broker
    participant TB as Telegram Bot
    participant DA as Desktop App

    ESP->>GS: BLE notify (18 bytes)
    GS->>BR: publish esptelemetry (JSON)
    BR-->>TB: subscribe esptelemetry
    BR-->>DA: subscribe esptelemetry
    TB-->>TB: update status message
    DA-->>DA: update widgets
    GS->>BR: publish phonebattery (JSON)
    BR-->>TB: battery / status alerts
```

### BLE Connection Lifecycle

```mermaid
sequenceDiagram
    participant BC as BleClient
    participant BS as Android BLE Stack
    participant ESP as ESP32 Firmware

    BC->>BS: startScan()
    BS->>ESP: LE scan advertising
    ESP-->>BS: advertisement (name "ROVER-S3")
    BS-->>BC: onScanResult("ROVER-S3")
    BC->>BS: connectGatt()
    BS->>ESP: GATT connect
    ESP-->>BS: connection established
    BS-->>BC: onConnectionStateChange(CONNECTED)
    BC->>BS: discoverServices()
    BS-->>BC: onServicesDiscovered()
    BC->>BS: setCharacteristicNotification(TELEM)
    ESP-->>BC: onCharacteristicChanged(telemetry)
```

---

## MQTT Topics

| Topic | Publisher | Subscriber | Payload |
|-------|-----------|------------|---------|
| `rover/{id}/cmd` | Gateway, Bot, Desktop | ESP32 | Binary command (13 bytes) |
| `rover/{id}/telemetry` | ESP32 | Gateway, Bot, Desktop | Binary telemetry (18 bytes) |
| `esptelemetry` | Gateway | Bot, Desktop | JSON telemetry |
| `phonebattery` | Gateway | Bot, Desktop | JSON battery info |
| `phonestatus` | Gateway | Bot, Desktop | JSON status |
| `phonegravity` | Gateway | ESP32 | JSON gravity vectors |
| `phonegyro` | Gateway | ESP32 | JSON gyro data |

---

## Binary Protocol

### Command (Phone/Bot/Desktop → Rover): 13 bytes

| Offset | Field | Size | Description |
|--------|-------|------|-------------|
| 0 | magic | 1 | 0xA7 |
| 1 | version | 1 | Protocol version |
| 2 | seq | 1 | Sequence number (0–255) |
| 3 | type | 1 | Command type (0–12) |
| 4 | channel | 1 | Pin/channel index |
| 5–8 | value_a | 4 | Primary value (int32) |
| 9–12 | value_b | 4 | Secondary value (int32) |

### Telemetry (Rover → Phone/Bot/Desktop): 18 bytes

| Offset | Field | Size | Description |
|--------|-------|------|-------------|
| 0 | magic | 1 | 0xA8 |
| 1 | version | 1 | Protocol version |
| 2 | seq | 1 | Sequence number |
| 3 | status | 1 | Status code |
| 4 | battery_voltage | 2 | Battery voltage (mV) |
| 6 | left_pwm | 1 | Left motor PWM (%) |
| 7 | right_pwm | 1 | Right motor PWM (%) |
| 8 | left_encoder | 4 | Left encoder pulses |
| 12 | right_encoder | 4 | Right encoder pulses |
| 16 | temperature | 1 | Temperature (°C) |
| 17 | tilt | 1 | Tilt angle (degrees) |

---

## Safety Features

1. **BLE Watchdog**: 30-second timeout kills motors if no commands received
2. **MQTT Reconnect**: Auto-reconnect with exponential backoff
3. **TG Rate Limiting**: Global 6 msgs/min limit prevents chat flooding
4. **Motor Limits**: PWM clamped to 0–100%, steering ratio limited
5. **Sequence Validation**: Commands checked for correct sequence numbers