package com.rover.gateway

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * Three tabs: Settings | Control (direct BLE joystick) | Log.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private val handler = Handler(Looper.getMainLooper())
    private var logOffset = 0

    private lateinit var etBroker: EditText
    private lateinit var etUser: EditText
    private lateinit var etPass: EditText
    private lateinit var etPeriod: EditText
    private lateinit var etRoverId: EditText
    private lateinit var etTgToken: EditText
    private lateinit var etTgChat: EditText
    private lateinit var tvStatus: TextView
    private lateinit var tvLog: TextView
    private lateinit var scrollLog: ScrollView
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button

    private lateinit var btnTabSettings: Button
    private lateinit var btnTabControl: Button
    private lateinit var btnTabLog: Button
    private lateinit var settingsPanel: LinearLayout
    private lateinit var controlPanel: LinearLayout
    private lateinit var logPanel: LinearLayout

    private lateinit var tvBle: TextView
    private lateinit var tvLive: TextView
    private lateinit var joyPad: FrameLayout
    private lateinit var knob: View
    private val knobR = 46f

    private var lightOn = false
    private var joyActive = false
    private var buttonHold = false
    private var lastJoySend = 0L
    private var lastSpeed = 0
    private var lastSteer = 0

    // Watchdog: if the main thread "stalls", send a stack dump to TG.
    private val mainTick = java.util.concurrent.atomic.AtomicLong()
    @Volatile private var watchdogStop = false
    private var lastWdReport = 0L

    private val needsPermissions = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        add(Manifest.permission.BLUETOOTH_SCAN)
        add(Manifest.permission.BLUETOOTH_CONNECT)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS) // for FG notifications
    }.toTypedArray()

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { }

    private fun dp(): Int = resources.displayMetrics.density.toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("cfg", MODE_PRIVATE)
        // Screen stays on while the control panel is open (prevents
        // disconnection from sleep during a static scene).
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val dp = dp()

        fun label(t: String): TextView = TextView(this).apply {
            text = t; setPadding(0, 4 * dp, 0, 0)
        }

                /* ---------- Settings fields ---------- */
        etBroker = EditText(this).apply { hint = "MQTT broker (ssl://host:8883)" }
        etUser = EditText(this).apply { hint = "MQTT user" }
        etPass = EditText(this).apply { hint = "MQTT pass" }
        etPeriod = EditText(this).apply { hint = "Telemetry period (ms)" }
        etRoverId = EditText(this).apply { hint = "Rover ID (rover/<id>/...)" }
        etTgToken = EditText(this).apply { hint = "TG bot token" }
        etTgChat = EditText(this).apply { hint = "TG chat id" }
        btnStart = Button(this).apply { text = "▶ Start" }
        btnStop = Button(this).apply { text = "■ Stop" }
        tvStatus = TextView(this).apply { text = "Not running" }

        settingsPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        settingsPanel.addView(label("MQTT broker"))
        settingsPanel.addView(etBroker)
        settingsPanel.addView(label("Login"))
        settingsPanel.addView(etUser)
        settingsPanel.addView(label("Password"))
        settingsPanel.addView(etPass)
        settingsPanel.addView(label("Telemetry period (ms)"))
        settingsPanel.addView(etPeriod)
        settingsPanel.addView(label("Rover ID"))
        settingsPanel.addView(etRoverId)
        settingsPanel.addView(label("TG bot token"))
        settingsPanel.addView(etTgToken)
        settingsPanel.addView(label("TG chat id"))
        settingsPanel.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(btnStart, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(btnStop, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        })
        settingsPanel.addView(tvStatus)

        /* ---------- Control tab ---------- */
        tvBle = TextView(this).apply {
            text = "BLE: not connected"
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
        }
        tvLive = TextView(this).apply {
            text = "bat: -- V    L: --%    R: --%    TEMP: --°C    TILT: --°"
            textSize = 13f
            typeface = Typeface.MONOSPACE
        }

        // Joystick: pad + circular knob
        joyPad = FrameLayout(this).apply {
            setBackgroundColor(0xFFE8E8E8.toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (200 * dp)
            )
        }
        knob = View(this).apply {
            setBackgroundResource(android.R.drawable.btn_default)
            layoutParams = FrameLayout.LayoutParams((knobR * 2).toInt(), (knobR * 2).toInt())
        }
        joyPad.addView(knob)
        joyPad.setOnTouchListener { _, e -> handleJoy(e) }

        val btnStopBig = Button(this).apply {
            text = "■ STOP"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.RED)
            textSize = 18f
        }
        val btnLight = Button(this).apply { text = "Light 💡" }
        val btnPing = Button(this).apply { text = "Ping" }
        btnLight.setOnClickListener {
            lightOn = !lightOn
            btnLight.text = if (lightOn) "Light: ON" else "Light: OFF"
            sendBle(GatewayService.EXTRA_CMD to "light", GatewayService.EXTRA_ON to lightOn)
        }
        btnPing.setOnClickListener { sendBle(GatewayService.EXTRA_CMD to "ping") }
        btnStopBig.setOnClickListener { sendBle(GatewayService.EXTRA_CMD to "stop") }

        controlPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        controlPanel.addView(tvBle)
        controlPanel.addView(tvLive)
        controlPanel.addView(label("Pull stick: up = forward, sideways = turn"))
        controlPanel.addView(joyPad)

        // Панель направлений: дискретные кнопки (держать для движения).
        // Надёжнее джойстика: команда уходит только при смене направления,
        // плюс повторяется в pollTabs, пока кнопка зажата.
        controlPanel.addView(TextView(this).apply {
            text = "Кнопки направления (удерживай)"
            textSize = 12f
            setPadding(0, 6 * dp, 0, 0)
        })
        val padH = 54 * dp
        fun dirBtn(text: String, speed: Int, steer: Int) = Button(this).apply {
            this.text = text
            textSize = 12f
            setOnTouchListener { _, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        buttonHold = true
                        driveTo(speed, steer)
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        buttonHold = false
                        driveTo(0, 0)
                        true
                    }
                    else -> false
                }
            }
        }
        fun padRow(vararg btns: Button) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            for (b in btns) {
                addView(b, LinearLayout.LayoutParams(0, padH, 1f))
            }
        }
        controlPanel.addView(padRow(
            dirBtn("В←", 70, -45),
            dirBtn("Вперёд", 100, 0),
            dirBtn("В→", 70, 45),
        ))
        controlPanel.addView(padRow(
            dirBtn("Н←", -70, -45),
            dirBtn("Назад", -100, 0),
            dirBtn("Н→", -70, 45),
        ))

        controlPanel.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(btnStopBig, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
            addView(btnLight, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
            addView(btnPing, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        })
        controlPanel.addView(label("Mines (50ms pulse)"))
        controlPanel.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            for (i in 0 until 3) {
                val b = Button(this@MainActivity).apply { text = "Mine ${i + 1}" }
                b.setOnClickListener { sendBle(GatewayService.EXTRA_CMD to "mine", GatewayService.EXTRA_CHANNEL to i) }
                addView(b, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
            }
        })
        controlPanel.addView(TextView(this).apply {
            text = "To control the rover (BLE) just tap \"Start\" and wait for connection."
            textSize = 12f
            setPadding(0, 8 * dp, 0, 0)
        })

        /* ---------- Log tab ---------- */
        tvLog = TextView(this).apply {
            textSize = 11f
            setTextIsSelectable(true)
            typeface = Typeface.MONOSPACE
            setPadding(8 * dp, 8 * dp, 8 * dp, 8 * dp)
            setTextColor(0xFF000000.toInt())
        }
        scrollLog = ScrollView(this).apply {
            addView(tvLog)
        }
        logPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(scrollLog, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }

        /* ---------- Tab buttons ---------- */
        btnTabSettings = tabButton("Settings")
        btnTabControl = tabButton("Control")
        btnTabLog = tabButton("Log")
        btnTabSettings.setOnClickListener { showTab(settingsPanel) }
        btnTabControl.setOnClickListener { showTab(controlPanel) }
        btnTabLog.setOnClickListener { showTab(logPanel) }

        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(btnTabSettings, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(btnTabControl, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(btnTabLog, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12 * dp, 12 * dp, 12 * dp, 12 * dp)
            addView(tabs)
            addView(settingsPanel)
            addView(controlPanel)
            addView(logPanel, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        setContentView(root)

        btnStart.setOnClickListener { startGateway() }
        btnStop.setOnClickListener { stopGateway() }

        startWatchdog()
        handler.post(pollTabs)
    }

    /**
     * Раз в ~5.5с проверяет, что главный поток жив (рукоятка пульса).
     * Если пульс не выполнился за 3.5с — главный поток заблокирован, шлём
     * в ТГ стек main-thread, а полный дамп всех потоков пишем в лог приложения.
     */
    private fun startWatchdog() {
        val t = Thread {
            // Снимаем стек именно с main-потока (не с вачдог-потока!).
            val mainThread = Looper.getMainLooper().thread
            while (!watchdogStop && !isDestroyed) {
                Thread.sleep(3000)
                val stamp = System.currentTimeMillis()
                handler.post { mainTick.set(stamp) }
                // Небольшое окно: если main обработал tick сразу — stuck будет ~500мс.
                // Старый код спал 4000мс ПОСЛЕ post и всегда получал фантомные 4000мс.
                Thread.sleep(500)
                val stuck = System.currentTimeMillis() - mainTick.get()
                if (stuck > 1200) {
                    // Только один поток, без getAllStackTraces: он паркует ВСЕ потоки
                    // и сам генерит «зависание» на следующем цикле.
                    val stack = mainThread.stackTrace.take(15)
                    val sb = StringBuilder("WATCHDOG: главный поток занят $stuck мс\n")
                    stack.forEach { sb.append("    $it\n") }
                    GatewayService.appendLog("SYS", sb.toString())
                    val now = System.currentTimeMillis()
                    if (now - lastWdReport > 60_000) { // не флудить ТГ повторами
                        lastWdReport = now
                        val top = stack.joinToString(" | ").take(400)
                        TgNotify.report(prefs, "[Rover] Main thread stuck: ${stuck}ms. $top")
                    }
                }
            }
        }
        t.isDaemon = true
        t.start()
    }

    override fun onDestroy() {
        watchdogStop = true
        super.onDestroy()
    }

    private fun tabButton(text: String): Button = Button(this).apply {
        this.text = text
        setOnClickListener { }
    }

    private fun showTab(panel: LinearLayout) {
        settingsPanel.visibility = if (panel === settingsPanel) View.VISIBLE else View.GONE
        controlPanel.visibility = if (panel === controlPanel) View.VISIBLE else View.GONE
        logPanel.visibility = if (panel === logPanel) View.VISIBLE else View.GONE
        btnTabSettings.setBackgroundColor(if (panel === settingsPanel) 0xFFCCE0FF.toInt() else Color.TRANSPARENT)
        btnTabControl.setBackgroundColor(if (panel === controlPanel) 0xFFCCE0FF.toInt() else Color.TRANSPARENT)
        btnTabLog.setBackgroundColor(if (panel === logPanel) 0xFFCCE0FF.toInt() else Color.TRANSPARENT)
    }

    /* ---------------- Джойстик ---------------- */

    private fun handleJoy(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { joyActive = true; updateJoy(e) }
            MotionEvent.ACTION_MOVE -> if (joyActive) updateJoy(e)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                joyActive = false
                centerKnob()
                sendBle(GatewayService.EXTRA_CMD to "drive", GatewayService.EXTRA_SPEED to 0, GatewayService.EXTRA_STEER to 0)
            }
        }
        return true
    }

    private fun updateJoy(e: MotionEvent) {
        val cx = joyPad.width / 2f
        val cy = joyPad.height / 2f
        val r = max(30f, min(cx, cy) - knobR)
        val dx = (e.x - cx).coerceIn(-r, r)
        val dy = (e.y - cy).coerceIn(-r, r)
        moveKnob(cx + dx, cy + dy)

        val now = System.currentTimeMillis()
        if (now - lastJoySend < 60) return
        lastJoySend = now

        val speed = (-dy / r * 100).toInt().coerceIn(-100, 100)
        val steer = (dx / r * 100).toInt().coerceIn(-100, 100)
        lastSpeed = speed
        lastSteer = steer
        if (speed != 0 || steer != 0) {
            sendBle(GatewayService.EXTRA_CMD to "drive", GatewayService.EXTRA_SPEED to speed, GatewayService.EXTRA_STEER to steer)
        }
    }

    private fun centerKnob() { moveKnob(joyPad.width / 2f, joyPad.height / 2f) }

    private fun moveKnob(x: Float, y: Float) {
        val lp = knob.layoutParams as FrameLayout.LayoutParams
        lp.leftMargin = (x - knobR).toInt()
        lp.topMargin = (y - knobR).toInt()
        knob.layoutParams = lp
    }

    /* ---------------- Команды в BLE ---------------- */

    private fun sendBle(vararg parts: Pair<String, Any>) {
        val intent = Intent(this, GatewayService::class.java).setAction(GatewayService.ACTION_BLE_CMD)
        for ((k, v) in parts) {
            when (v) {
                is String -> intent.putExtra(k, v)
                is Int -> intent.putExtra(k, v)
                is Boolean -> intent.putExtra(k, v)
                is Float -> intent.putExtra(k, v)
            }
        }
        // startService из фона (когда мост остановлен) кидает
        // IllegalStateException — глотаем, чтобы не ронять приложение.
        runCatching { startService(intent) }
    }

    /** Единый путь отдачи команды движения (джойстик и кнопки направлений). */
    private fun driveTo(speed: Int, steer: Int) {
        lastSpeed = speed
        lastSteer = steer
        sendBle(GatewayService.EXTRA_CMD to "drive", GatewayService.EXTRA_SPEED to speed, GatewayService.EXTRA_STEER to steer)
    }

    /* ---------------- Фоновый поллинг (статус/телеметрия/лог) ---------------- */

    private val pollTabs = object : Runnable {
        override fun run() {
            // Keepalive: пока зажата кнопка или джойстик, повторяем последнюю команду,
            // чтобы ESP не остановил моторы вачдогом (CMD_TIMEOUT_MS).
            if ((joyActive || buttonHold) && (lastSpeed != 0 || lastSteer != 0)) {
                sendBle(GatewayService.EXTRA_CMD to "drive", GatewayService.EXTRA_SPEED to lastSpeed, GatewayService.EXTRA_STEER to lastSteer)
            }

            tvBle.text = if (GatewayService.bleConnected) "BLE: подключён — РОВЕР ГОТОВ"
            else if (GatewayService.running) "BLE: подключение..."
            else "BLE: не подключён (нажми «Запустить» в Настройках)"

            val t = GatewayService.lastTelemetry
            if (t != null) {
                tvLive.text = String.format(
                    Locale.US,
                    "бат: %.1f V    L: %d%%    R: %d%%    TEMP: %d°C    TILT: %.1f°",
                    t.batteryVolts / 10.0, t.leftPwm, t.rightPwm, t.tempC, t.tiltTenths / 10.0,
                )
            }

            val buf = GatewayService.logBuffer
            val newLogs: String? = synchronized(buf) {
                if (buf.size <= logOffset) {
                    null
                } else {
                    val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
                    val sb = StringBuilder()
                    for (i in logOffset until buf.size) {
                        sb.appendLine("${fmt.format(Date(buf[i].first))}  ${buf[i].second}")
                    }
                    logOffset = buf.size
                    sb.toString()
                }
            }
            if (newLogs != null) {
                val lines = (tvLog.text.toString() + "\n" + newLogs).split("\n")
                if (lines.size > 500) {
                    tvLog.text = lines.takeLast(500).joinToString("\n")
                } else {
                    tvLog.text = lines.joinToString("\n")
                }
                scrollLog.post { scrollLog.fullScroll(ScrollView.FOCUS_DOWN) }
            }
            tvStatus.text = prefs.getString("last_status", "Готов") ?: "Готов"
            handler.postDelayed(this, 300)
        }
    }

    override fun onResume() {
        super.onResume()
        loadPrefs()
        showTab(settingsPanel)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            permLauncher.launch(needsPermissions)
        }
    }

    private fun startGateway() {
        savePrefs()
        logOffset = 0
        tvLog.text = ""
        // Снять с контроля Doze: иначе при выключенном экране сон рвёт BLE/MQTT.
        requestBatteryExemption()
        val intent = Intent(this, GatewayService::class.java).setAction(GatewayService.ACTION_START)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
        showTab(controlPanel)
    }

    private fun requestBatteryExemption() {
        if (Build.VERSION.SDK_INT < 23) return
        val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            runCatching {
                startActivity(
                    Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")),
                )
            }
        }
    }

    private fun stopGateway() {
        val intent = Intent(this, GatewayService::class.java).setAction(GatewayService.ACTION_STOP)
        startService(intent)
    }

    private fun savePrefs() {
        prefs.edit()
            .putString(GatewayService.KEY_BROKER, etBroker.text.toString())
            .putString(GatewayService.KEY_USER, etUser.text.toString())
            .putString(GatewayService.KEY_PASS, etPass.text.toString())
            .putString(GatewayService.KEY_SENSOR_PERIOD, etPeriod.text.toString())
            .putString(GatewayService.KEY_ROVER_ID, etRoverId.text.toString())
            .putString(TgNotify.KEY_TG_TOKEN, etTgToken.text.toString())
            .putString(TgNotify.KEY_TG_CHAT, etTgChat.text.toString())
            .apply()
    }

    private fun loadPrefs() {
        etBroker.setText(prefs.getString(GatewayService.KEY_BROKER, ""))
        etUser.setText(prefs.getString(GatewayService.KEY_USER, ""))
        etPass.setText(prefs.getString(GatewayService.KEY_PASS, ""))
        etPeriod.setText(prefs.getString(GatewayService.KEY_SENSOR_PERIOD, "2000"))
        etRoverId.setText(prefs.getString(GatewayService.KEY_ROVER_ID, "demo"))
        etTgToken.setText(prefs.getString(TgNotify.KEY_TG_TOKEN, TgNotify.DEFAULT_TOKEN))
        etTgChat.setText(prefs.getString(TgNotify.KEY_TG_CHAT, TgNotify.DEFAULT_CHAT))
    }
}