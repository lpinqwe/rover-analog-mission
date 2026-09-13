/**
 * Rover controller via Telegram (alternative to PC console).
 *
 * Can work with ANY bot token, including the one used to ship the APK.
 * The chat you message IS the controller.
 *
 * Bridge: Telegram <-> MQTT <-> Phone gateway <-> BLE <-> ESP32.
 *
 * Run:
 *   BOT_TOKEN=1234:... ROVER_CHAT_ID=663450648 npm start
 *   (ROVER_CHAT_ID optional — without it the bot replies in any chat)
 *
 * Bot commands:
 *   /start   — menu with buttons
 *   /status  — current state (GPS, battery, tilt)
 */

import TelegramBot from "node-telegram-bot-api";
import mqtt from "mqtt";
import dotenv from "dotenv";

dotenv.config();

const TOKEN = process.env.BOT_TOKEN;
if (!TOKEN) {
  console.error("Set BOT_TOKEN in env (.env) and restart.");
  process.exit(1);
}

const ALLOWED_CHAT = process.env.ROVER_CHAT_ID;
const MOVES = {
  fw: { speed: 1.0, steer: 0.0 },
  bw: { speed: -1.0, steer: 0.0 },
  lt: { speed: 0.0, steer: 1.0 },
  rt: { speed: 0.0, steer: -1.0 },
};
const brokerUrl = process.env.BROKER_URL || "";
const brokerUser = process.env.MQTT_USER || "";
const brokerPass = process.env.MQTT_PASS || "";

const bot = new TelegramBot(TOKEN, { polling: true });

// --- MQTT ---
const mq = mqtt.connect(brokerUrl, brokerUser
  ? { username: brokerUser, password: brokerPass }
  : {});
const TOPIC = (s) => `rover/${process.env.ROVER_ID || "demo"}/${s}`;
const pub = (topic, obj) => mq.publish(topic, JSON.stringify(obj), { qos: 0 });

let lastTelemetry = {};
let lastSensors = {};

mq.on("connect", () => {
  console.log("mqtt ok");
  mq.subscribe([TOPIC("esptelemetry"), TOPIC("sensors"), TOPIC("status")]);
});
mq.on("message", (t, p) => {
  const j = JSON.parse(p.toString());
  if (t.endsWith("/esptelemetry")) lastTelemetry = j;
  else if (t.endsWith("/sensors")) lastSensors = j;
});

// --- Telegram ---
const keyboard = (keepBtns) => ({
  reply_markup: {
    keyboard: [
      [{ text: "fw ⬆" }, { text: "lt ⬅" }, { text: "rt ➡" }, { text: "bw ⬇" }],
      [{ text: "Stop ⏹" }, { text: "Light 💡" }, { text: "Ping" }],
      [{ text: "Mine 1" }, { text: "Mine 2" }, { text: "Mine 3" }],
      [{ text: "/status" }],
    ],
    resize_keyboard: true,
  },
});

bot.on("message", async (msg) => {
  const chatId = msg.chat.id;
  const text = (msg.text || "").trim();

  // If ROVER_CHAT_ID is set — the rover is controlled only from this chat
  if (ALLOWED_CHAT && String(chatId) !== String(ALLOWED_CHAT)) {
    return;
  }

  if (!text) return;
  const cmd = text.toLowerCase();

  if (cmd.startsWith("/start")) {
    await bot.sendMessage(
      chatId,
      "Rover remote. Buttons: arrows = movement, Stop, Light, Mines.\nHold movement briefly — between taps the rover keeps driving until you stop or steer.",
      keyboard(),
    );
    return;
  }
  if (cmd.startsWith("/status") || cmd === "status") return replyStatus(chatId);

  publisher(chatId, cmd);
});

// Send command to MQTT. Short bursts on arrow buttons.
async function publisher(chatId, cmd) {
  const move = MOVES[cmd];
  if (move) {
    pub(TOPIC("cmd"), { speed: move.speed, steer: move.steer });
    // The phone gateway decides (BLE speed/ramp). The bot sends once —
    // the robot drives until the next command. For a burst send stop after 800ms.
    setTimeout(() => pub(TOPIC("cmd"), { speed: 0, steer: 0 }), 800);
    await bot.sendMessage(chatId, `Moving ${cmd}...`);
    return;
  }
  if (cmd === "стоп" || cmd === "stop") { pub(TOPIC("action"), { type: "stop" }); return replyOk(chatId, "Stop"); }
  if (cmd === "свет" || cmd === "light") {
    // toggle unknown — ask state. Simplest: on/off.
    const key = keyboard();
    await bot.sendMessage(chatId, "Choose:", {
      reply_markup: {
        inline_keyboard: [
          [{ text: "ON", callback_data: "light:1" }, { text: "OFF", callback_data: "light:0" }],
        ],
      },
    });
    return;
  }
  if (cmd.startsWith("мина") || cmd.startsWith("mine")) {
    const parts = cmd.split(/\s+/);
    const i = (parts.length > 1 ? parseInt(parts[1], 10) : 1) - 1;
    pub(TOPIC("action"), { type: "mine", channel: Math.max(0, i) });
    return replyOk(chatId, `Mine ${i + 1} triggered`);
  }
  if (cmd === "пинг" || cmd === "ping") { pub(TOPIC("action"), { type: "ping" }); return replyOk(chatId, "Ping"); }
}

bot.on("callback_query", async (q) => {
  const chatId = q.message.chat.id;
  const data = q.data;
  if (data.startsWith("light:")) {
    pub(TOPIC("action"), { type: "light", on: data === "light:1" });
    await bot.answerCallbackQuery(q.id, { text: "Light: " + (data === "light:1" ? "ON" : "OFF") });
  }
});

function replyStatus(chatId) {
  const t = lastTelemetry, s = lastSensors;
  const txt = [
    "Rover status:",
    `🔌 BLE: ${t.ble_connected ? "connected" : "no"} | Phone batt.: ${s.battery_pct ?? "?"}%`,
    `🔋 Rover battery: ${t.battery ?? "?"}V | MCU: ${t.mc_temp_c ?? "?"}°C`,
    `🎚 L=${t.left_pwm ?? "?"}% R=${t.right_pwm ?? "?"}%`,
    `🧭 Tilt: ${t.esp_tilt_deg ?? "?"}° (${t.tilted ? "⚠️ protection" : "ok"})`,
    `📡 GPS: ${s.gps ? s.gps.lat.toFixed(5) + ", " + s.gps.lon.toFixed(5) : "none"}`,
  ].join("\n");
  bot.sendMessage(chatId, txt, keyboard());
}

function replyOk(chatId, s) {
  bot.sendMessage(chatId, "✓ " + s, keyboard());
}

console.log("bot ready");
