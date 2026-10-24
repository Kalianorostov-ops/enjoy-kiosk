package ru.enjoythehookah.kiosk

import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import org.json.JSONObject

/**
 * Экран стола для гостей: страница https://enjoythehookah.ru/table.html?t=N&k=КЛЮЧ на весь экран.
 * Выйти можно только через скрытое меню: 5 касаний в левом верхнем углу → PIN.
 */
class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var root: FrameLayout
    private var web: WebView? = null
    private var offline: TextView? = null
    private val ui = Handler(Looper.getMainLooper())
    private var askedPinning = false
    private var lastPinAsk = 0L
    private var setupShown = false

    // ---------- запуск ----------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        setContentView(root)
        WebView.setWebContentsDebuggingEnabled(false)
        if (!prefs.paused) Kiosk.applyOwnerPolicies(this)
        handleLink(intent)
        registerScreenWatch()
        registerNetworkWatch()
        render()
        ui.postDelayed(watchdog, 60_000)
        ui.postDelayed(updCheck, 120_000)
        if (lostOn()) ui.post(locLoop)
        ui.postDelayed(awayLoop, 30_000)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("wake", false)) goWake()
        handleLink(intent)
    }

    // 🔎 1.0.17: отчёт экрана — на переднем ли плане окно программы
    private var resumedNow = false
    private var shotRes = "не делали"
    @Volatile private var lastShot = ""

    override fun onPause() {
        resumedNow = false
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        resumedNow = true
        hideSystemBars()
        ui.removeCallbacks(visitBack)
        if (!prefs.paused) {
            Kiosk.applyOwnerPolicies(this)
            if (prefs.setupAuto && System.currentTimeMillis() - prefs.setupAt > 3600_000L) Kiosk.applySetup(this)
            enterLock()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemBars()
            if (!prefs.paused) enterLock()
        }
    }

    override fun onDestroy() {
        try { unregisterReceiver(screenWatch) } catch (_: Exception) {}
        try { (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(netWatch) } catch (_: Exception) {}
        ui.removeCallbacksAndMessages(null)
        web?.destroy()
        web = null
        super.onDestroy()
    }

    private fun render() {
        if (Prefs.isTableUrl(prefs.url) && prefs.pin.length >= 4) showWeb() else showSetup(false)
    }

    // ---------- блокировка ----------

    private fun enterLock() {
        if (!Prefs.isTableUrl(prefs.url) || setupShown) return
        if (Kiosk.isLocked(this)) return
        if (Kiosk.isOwner(this)) {
            try { startLockTask() } catch (_: Exception) {}
        } else if (!askedPinning || System.currentTimeMillis() - lastPinAsk > 20_000) {
            // без режима владельца — обычное закрепление экрана (Android спросит подтверждение).
            // Если закрепление сняли — через 20 секунд предложим снова.
            askedPinning = true
            lastPinAsk = System.currentTimeMillis()
            try { startLockTask() } catch (_: Exception) {}
        }
    }

    private fun leaveLock() {
        try { stopLockTask() } catch (_: Exception) {}
    }

    private fun kioskActive() = !prefs.paused && !setupShown && Prefs.isTableUrl(prefs.url)

    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
        }
    }

    // кнопки громкости и «Назад» гостям не нужны
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE,
            KeyEvent.KEYCODE_CAMERA, KeyEvent.KEYCODE_SEARCH, KeyEvent.KEYCODE_ASSIST ->
                if (kioskActive()) return true
        }
        return super.dispatchKeyEvent(event)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (setupShown && Prefs.isTableUrl(prefs.url) && prefs.pin.length >= 4) render()
        // иначе — ничего: с экрана стола «Назад» не уводит
    }

    // ---------- скрытый вход: 5 касаний в левом верхнем углу ----------

    private var tapStart = 0L
    private var taps = 0

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN && !setupShown) {
            lastTouch = System.currentTimeMillis()
            val zone = dp(110)
            if (ev.x < zone && ev.y < zone) {
                val now = System.currentTimeMillis()
                if (now - tapStart > 4000) { tapStart = now; taps = 0 }
                taps++
                if (taps >= 5) { taps = 0; askPin { showMenu() } }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun askPin(onOk: () -> Unit) {
        if (prefs.pin.length < 4) { onOk(); return }
        val field = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "PIN"
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
        }
        val box = FrameLayout(this).apply { setPadding(dp(24), dp(8), dp(24), 0); addView(field) }
        AlertDialog.Builder(this)
            .setTitle("Введите PIN администратора")
            .setView(box)
            .setPositiveButton("OK") { _, _ ->
                if (field.text.toString() == prefs.pin) onOk() else toast("Неверный PIN")
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun showMenu() {
        val owner = Kiosk.isOwner(this)
        val labels = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()
        fun add(label: String, action: () -> Unit) { labels.add(label); actions.add(action) }
        add("🔄  Обновить экран") { web?.reload() }
        add("🔗  Сменить стол (новая ссылка или QR)") { showSetup(true) }
        if (prefs.paused) {
            add("🔒  Вернуть режим киоска") {
                prefs.paused = false
                Kiosk.applyOwnerPolicies(this)
                askedPinning = false
                render()
                enterLock()
                toast("Режим киоска включён")
            }
        } else {
            add("🔓  Выйти из киоска (временно)") {
                pause()
                toast("Киоск выключен. Вернуть — то же меню или перезагрузка планшета")
            }
        }
        add("⚙️  Настройки Android") {
            pause()
            try { startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
        }
        if (prefs.lost) add("✅  Снять режим «Планшет пропал»") { setLost(false, null); toast("Режим «Пропал» снят. Снимите его и в админке, иначе включится снова") }
        // 🚨 1.0.15: «Пропал» включился сам (нет Wi-Fi заведения) — снять и запомнить текущую сеть как сеть заведения
        if (prefs.lostAuto) add("✅  Снять «Пропал» (включился сам — нет Wi-Fi заведения)") {
            Kiosk.wifiSsid(this).let { if (it.isNotEmpty()) prefs.addHome(it) }
            prefs.awaySince = 0L
            setAutoLost(false)
            toast("Снято. Если это новая сеть заведения — она запомнена")
        }
        add("🔋  Настройки батареи (защита 80 %)") { visitSettings("battery") }
        add(if (prefs.wake) "💡  Будить погасший экран: ВКЛ" else "💡  Будить погасший экран: ВЫКЛ") {
            prefs.wake = !prefs.wake
            toast(if (prefs.wake) "Экран будет включаться сам" else "Экран больше не будится")
        }
        add("🔑  Сменить PIN") { changePin() }
        add(if (prefs.autoUpdate) "⬆️  Обновляться само: ВКЛ" else "⬆️  Обновляться само: ВЫКЛ") {
            prefs.autoUpdate = !prefs.autoUpdate
            toast(if (prefs.autoUpdate) "Программа будет обновляться сама" else "Обновление только вручную")
        }
        add("⬇️  Проверить обновление сейчас") {
            toast("Смотрю, есть ли новая версия…")
            Updater.check(this) { v, note ->
                ui.post {
                    toast(note)
                    if (v > 0 && Kiosk.isOwner(this)) {
                        AlertDialog.Builder(this)
                            .setTitle("Обновление 1.0." + v)
                            .setMessage("Скачано. Поставить сейчас? Программа перезапустится, экран стола вернётся сам.")
                            .setPositiveButton("Поставить") { _, _ -> Updater.install(this) }
                            .setNegativeButton("Потом ночью", null)
                            .show()
                    }
                }
            }
        }
        if (owner) add("♻️  Перезагрузить планшет") {
            AlertDialog.Builder(this)
                .setTitle("Перезагрузить планшет?")
                .setMessage("Экран стола вернётся сам через минуту.")
                .setPositiveButton("Перезагрузить") { _, _ -> if (!Kiosk.reboot(this)) toast("Не получилось") }
                .setNegativeButton("Отмена", null)
                .show()
        }
        if (!owner) {
            add("🏠  Выбрать рабочий стол Android") {
                pause()
                try { startActivity(Intent(Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
            }
        } else {
            add("⚠️  Снять режим владельца устройства") { confirmRemoveOwner() }
        }
        add("ℹ️  О программе") { about() }

        AlertDialog.Builder(this)
            .setTitle("Enjoy Kiosk · стол " + Prefs.tableNo(prefs.url))
            .setItems(labels.toTypedArray()) { _, i -> actions[i]() }
            .setNegativeButton("Закрыть", null)
            .show()
    }

    // ---------- 🔋 1.0.7: ненадолго открыть экран настроек Android ----------

    /** Вернуть экран стола, если сотрудник ушёл из настроек и забыл нажать «Домой». */
    private val visitBack = Runnable {
        try {
            startActivity(Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        } catch (_: Exception) {}
    }

    /**
     * Открыть экран настроек на 3 минуты: батарея (защита 80 %) или настройки программы.
     * Режим киоска не выключаем насовсем: «Домой» или 3 минуты — и снова экран стола.
     */
    private fun visitSettings(what: String) {
        if (lostOn()) return
        // 🔋 1.0.10: если Android ограничивает программу по батарее — сначала системное окно «Разрешить работать в фоне?»
        val boNeed = what == "battery" && try { !(getSystemService(Context.POWER_SERVICE) as android.os.PowerManager).isIgnoringBatteryOptimizations(packageName) } catch (_: Exception) { false }
        val tries = if (what == "battery") (if (boNeed) listOf(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, android.net.Uri.parse("package:$packageName")),
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        ) else emptyList()) + listOf(
            Intent(Intent.ACTION_POWER_USAGE_SUMMARY),
            Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS),
            Intent(Settings.ACTION_SETTINGS)
        ) else listOf(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName")),
            Intent(Settings.ACTION_SETTINGS)
        )
        leaveLock()
        Kiosk.relaxOwnerPolicies(this)
        var ok = false
        for (i in tries) {
            try {
                startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                ok = true
                break
            } catch (_: Exception) {}
        }
        if (!ok) {
            Kiosk.applyOwnerPolicies(this)
            enterLock()
            toast("Не получилось открыть настройки")
            return
        }
        toast(if (what == "battery") "Батарея → «Защита батареи» → «Защита аккумулятора». Через 3 минуты вернётся экран стола"
              else "Контроль активности → «Нет ограничений». Через 3 минуты вернётся экран стола")
        ui.removeCallbacks(visitBack)
        ui.postDelayed(visitBack, 180_000L)
    }

    // ---------- 🚨 1.0.8: «Планшет пропал» ----------

    private var lostCover: View? = null

    /** «Пропал» включён: из админки или сам (нет Wi-Fi заведения). */
    private fun lostOn() = prefs.lost || prefs.lostAuto

    private fun setLost(on: Boolean, json: String?) {
        prefs.lost = on
        if (on) {
            if (json != null) prefs.lostMsg = json
            lostUiOn()
        } else if (!prefs.lostAuto) lostUiOff()
    }

    private fun setAutoLost(on: Boolean) {
        prefs.lostAuto = on
        if (on) lostUiOn() else if (!prefs.lost) lostUiOff()
    }

    private fun lostUiOn() {
        Kiosk.grantLocation(this, true)
        if (sleeping) { sleeping = false; nightCover?.let { root.removeView(it) }; nightCover = null; cancelWake() }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        showLost()
        ui.removeCallbacks(locLoop)
        ui.post(locLoop)
        if (!prefs.paused) { Kiosk.applyOwnerPolicies(this); enterLock() }
        // 🚨 1.0.15: экран мог быть погашен на ночь — включаем, иначе картинку никто не увидит
        screenOn()
    }

    private fun lostUiOff() {
        ui.removeCallbacks(locLoop)
        lostCover?.let { root.removeView(it) }
        lostCover = null
        applyBrightness(brightness)
    }

    /** Включить погасший экран (ночной режим или кнопка питания). */
    private fun screenOn() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isInteractive) wakeUp.run()
        } catch (_: Exception) {}
    }

    // ---------- 🚨 1.0.15: «Пропал» без интернета — планшет унесли из заведения ----------

    private val AWAY_MS = 15 * 60_000L

    private fun wifiConnected(): Boolean = try {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true
    } catch (_: Exception) { false }

    /**
     * Раз в минуту: видит ли планшет Wi-Fi заведения. Нет сети заведения 15 минут подряд и не на зарядке —
     * экран «Пропал» включается сам (без интернета и без админки). Вернулся в сеть заведения — снимается сам.
     * Сети заведения программа запоминает первые сутки после обновления; ещё — из админки «📶 Wi-Fi».
     */
    private val awayLoop = object : Runnable {
        override fun run() {
            ui.removeCallbacks(this)
            try { awayCheck() } catch (_: Exception) {}
            ui.postDelayed(this, 60_000L)
        }
    }

    private fun awayCheck() {
        if (!Prefs.isTableUrl(prefs.url) || prefs.paused) return
        // ночью экран погашен — Android может сам отключить Wi-Fi; ночью не судим, чтобы не включить «Пропал» зря
        if (sleeping && !prefs.lostAuto) { prefs.awaySince = 0L; return }
        val now = System.currentTimeMillis()
        val ssid = Kiosk.wifiSsid(this)
        if (prefs.homeLearnUntil == 0L) prefs.homeLearnUntil = now + 24 * 3600_000L
        if (ssid.isNotEmpty() && now < prefs.homeLearnUntil && !lostOn()) prefs.addHome(ssid)
        val home = prefs.homeSet()
        val atHome = ssid.isNotEmpty() && ssid in home
        val unknown = ssid.isEmpty() && wifiConnected()   // подключён, но имя сети не прочитать — не судим
        if (home.isEmpty() || atHome || unknown) {
            prefs.awaySince = 0L
            if (prefs.lostAuto) setAutoLost(false)
            return
        }
        if (prefs.lostAuto) return
        if (plugged()) { prefs.awaySince = 0L; return }
        if (prefs.awaySince == 0L) { prefs.awaySince = now; return }
        if (now - prefs.awaySince >= AWAY_MS) setAutoLost(true)
    }

    /** Экран поверх всего: кому принадлежит, видеонаблюдение, телефон. Касания до страницы не доходят. */
    private fun showLost() {
        lostCover?.let { root.removeView(it) }
        var title = "Это устройство принадлежит Enjoy the Hookah"
        var text = "Устройство заблокировано и отслеживается. В зале ведётся видеонаблюдение.\nВ ваших интересах вернуть его — позвоните:"
        var phone = "8 961 427-19-99"
        try {
            val j = JSONObject(prefs.lostMsg)
            title = j.optString("title", title).ifBlank { title }
            text = j.optString("text", text).ifBlank { text }
            phone = j.optString("phone", phone).ifBlank { phone }
        } catch (_: Exception) {}
        val gold = Color.parseColor("#C79A4B")
        val ink = Color.parseColor("#F1E9DA")
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(48), dp(32), dp(48), dp(32))
        }
        // 🚨 1.0.20: setText — раньше «text = t» попадало в переменную text выше, и все строки экрана «Пропал» были пустыми
        fun tv(t: String, size: Float, color: Int, bold: Boolean) = TextView(this).apply {
            setText(t)
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
            gravity = Gravity.CENTER
            if (bold) typeface = Typeface.DEFAULT_BOLD
            setLineSpacing(0f, 1.2f)
        }
        col.addView(tv("⚠", 64f, gold, false))
        col.addView(tv(title, 34f, gold, true), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        col.addView(tv(text, 24f, ink, false), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(24) })
        col.addView(tv(phone, 52f, ink, true), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20) })
        col.addView(tv("Спасибо!", 22f, gold, false), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20) })
        val cover = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#161412"))
            isClickable = true
            isFocusable = true
            addView(col, FrameLayout.LayoutParams(-1, -1))
        }
        root.addView(cover, FrameLayout.LayoutParams(-1, -1))
        cover.bringToFront()
        lostCover = cover
        applyBrightness(1f)
        hideSystemBars()
    }

    /** Пока «пропал» — место раз в 3 минуты (если есть интернет, админка увидит его через страницу). */
    private val locLoop = object : Runnable {
        override fun run() {
            ui.removeCallbacks(this)
            if (!lostOn()) return
            requestLocation()
            // 🚨 1.0.9: экран «Пропал» всегда сверху и на полной яркости; 1.0.15: и включён
            if (lostCover?.parent == null) showLost() else { lostCover?.bringToFront(); applyBrightness(1f) }
            screenOn()
            ui.postDelayed(this, 180_000L)
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestLocation() {
        if (checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Kiosk.grantLocation(this, true)
            if (checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        }
        val lm = getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
        val provs = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31) provs.add(android.location.LocationManager.FUSED_PROVIDER)
        provs.add(android.location.LocationManager.NETWORK_PROVIDER)
        provs.add(android.location.LocationManager.GPS_PROVIDER)
        for (p in provs) {
            val on = try { lm.isProviderEnabled(p) } catch (_: Exception) { false }
            if (!on) continue
            try { lm.getLastKnownLocation(p)?.let { saveLoc(it, p) } } catch (_: Exception) {}
            try {
                if (Build.VERSION.SDK_INT >= 30) {
                    lm.getCurrentLocation(p, null, mainExecutor) { l -> if (l != null) saveLoc(l, p) }
                } else {
                    @Suppress("DEPRECATION")
                    lm.requestSingleUpdate(p, object : android.location.LocationListener {
                        override fun onLocationChanged(l: android.location.Location) { saveLoc(l, p) }
                        @Deprecated("Deprecated in Java")
                        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                        override fun onProviderEnabled(provider: String) {}
                        override fun onProviderDisabled(provider: String) {}
                    }, Looper.getMainLooper())
                }
            } catch (_: Exception) {}
        }
    }

    /** Берём самое свежее место; при равном времени (±1 мин) — более точное. */
    private fun saveLoc(l: android.location.Location, p: String) {
        try {
            val old = if (prefs.lastLoc.isEmpty()) null else JSONObject(prefs.lastLoc)
            val oldAt = old?.optLong("at") ?: 0L
            val oldAcc = old?.optDouble("acc") ?: 1e9
            val newer = l.time > oldAt + 60_000L
            val sameButBetter = l.time >= oldAt - 60_000L && l.accuracy < oldAcc
            if (old != null && !newer && !sameButBetter) return
            prefs.lastLoc = JSONObject()
                .put("lat", l.latitude).put("lon", l.longitude)
                .put("acc", l.accuracy.toDouble()).put("at", l.time).put("p", p)
                .toString()
        } catch (_: Exception) {}
    }

    private fun plugged(): Boolean = try {
        val i = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        (i?.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
    } catch (_: Exception) { false }

    private fun pause() {
        prefs.paused = true
        leaveLock()
        Kiosk.relaxOwnerPolicies(this)
    }

    private fun changePin() {
        val f = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "Новый PIN, 4–8 цифр"
            gravity = Gravity.CENTER
        }
        val box = FrameLayout(this).apply { setPadding(dp(24), dp(8), dp(24), 0); addView(f) }
        AlertDialog.Builder(this).setTitle("Новый PIN").setView(box)
            .setPositiveButton("Сохранить") { _, _ ->
                val v = f.text.toString()
                if (v.length in 4..8) { prefs.pin = v; toast("PIN изменён") } else toast("PIN — от 4 до 8 цифр")
            }
            .setNegativeButton("Отмена", null).show()
    }

    private fun confirmRemoveOwner() {
        AlertDialog.Builder(this)
            .setTitle("Снять режим владельца?")
            .setMessage("Планшет станет обычным: появятся шторка, кнопки и меню питания. Чтобы вернуть полную блокировку, понадобится сброс и настройка заново.")
            .setPositiveButton("Снять") { _, _ ->
                pause()
                Kiosk.removeOwner(this)
                toast(if (Kiosk.isOwner(this)) "Не получилось" else "Готово, планшет снова обычный")
            }
            .setNegativeButton("Отмена", null).show()
    }

    private fun about() {
        AlertDialog.Builder(this)
            .setTitle("Enjoy Kiosk " + BuildConfig.VERSION_NAME)
            .setMessage(
                "Стол: " + Prefs.tableNo(prefs.url) + "\n" +
                    "Режим владельца: " + (if (Kiosk.isOwner(this)) "да (полная блокировка)" else "нет (закрепление экрана)") + "\n" +
                    "Киоск: " + (if (prefs.paused) "выключен" else "включён") + "\n" +
                    "Будить экран: " + (if (prefs.wake) "да" else "нет") + "\n\n" +
                    "Планшет: " + Build.MANUFACTURER + " " + Build.MODEL + ", Android " + Build.VERSION.RELEASE + "\n" +
                    "WebView: " + webViewVersion() + "\n" +
                    "Wi-Fi: " + wifiLevel() + "%   IP: " + (ipAddress().ifEmpty { "нет" }) + "\n" +
                    "Включён: " + (android.os.SystemClock.elapsedRealtime() / 3600_000L) + " ч\n" +
                    "Свободно: память " + freeMemMb() + " МБ, память устройства " + freeDiskMb() + " МБ\n" +
                    "Пульс страницы: " + (if (!pulseSeen) "нет (старая версия экрана)"
                        else ((System.currentTimeMillis() - lastAlive) / 1000L).toString() + " с назад") + "\n" +
                    "Обновление: " + (if (prefs.updNote.isEmpty()) "ещё не проверялось" else prefs.updNote)
            )
            .setPositiveButton("OK", null).show()
    }

    // ---------- экран стола ----------

    @SuppressLint("SetJavaScriptEnabled")
    private fun makeWeb(): WebView = WebView(this).apply {
        setBackgroundColor(Color.BLACK)
        overScrollMode = View.OVER_SCROLL_NEVER
        isLongClickable = false
        isHapticFeedbackEnabled = false
        setOnLongClickListener { true }
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            textZoom = 100
            cacheMode = WebSettings.LOAD_DEFAULT
            allowFileAccess = false
            allowContentAccess = false
            setSupportMultipleWindows(false)
            userAgentString = userAgentString + " EnjoyKiosk/" + BuildConfig.VERSION_NAME
        }
        addJavascriptInterface(Bridge(), "EnjoyKiosk")
        webViewClient = object : WebViewClient() {
            // гостям — только наш сайт
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                request.url.host != Prefs.HOST

            override fun onPageFinished(view: WebView, url: String?) {
                if (!failed) setOffline(false)
                failed = false
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) { failed = true; setOffline(true) }
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                // страница «упала» — пересоздаём её
                root.removeView(view)
                view.destroy()
                web = null
                ui.post { render() }
                return true
            }
        }
    }

    private var failed = false

    private fun showWeb() {
        setupShown = false
        root.removeAllViews()
        val w = web ?: makeWeb().also { web = it }
        root.addView(w, FrameLayout.LayoutParams(-1, -1))
        offline = TextView(this).apply {
            text = "Нет связи с интернетом — подключаемся…"
            setTextColor(Color.parseColor("#F1E9DA"))
            setBackgroundColor(Color.parseColor("#E6161412"))
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            visibility = View.GONE
        }
        // полоска снизу: последняя загруженная страница остаётся видна гостю
        root.addView(offline, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        if (w.url != prefs.url) w.loadUrl(prefs.url)
        hideSystemBars()
        lostCover = null
        if (lostOn()) showLost()
    }

    private val retry = Runnable { web?.reload() }
    private var offlineTries = 0

    /**
     * Нет связи: показываем полоску снизу (последняя страница остаётся видна) и пробуем снова
     * с нарастающей паузой 10 с → 30 с → 2 мин, чтобы не греть планшет и не сажать батарею.
     */
    private fun setOffline(on: Boolean) {
        offline?.visibility = if (on) View.VISIBLE else View.GONE
        ui.removeCallbacks(retry)
        if (!on) { offlineTries = 0; return }
        offlineTries++
        val wait = when {
            offlineTries <= 2 -> 10_000L
            offlineTries <= 5 -> 30_000L
            else -> 120_000L
        }
        ui.postDelayed(retry, wait)
    }

    // ---------- «пульс» страницы: белый или замёрзший экран сам не вылечится ----------

    private var lastAlive = 0L
    private var pulseSeen = false
    private var misses = 0

    private val watchdog = object : Runnable {
        override fun run() {
            ui.postDelayed(this, 60_000)
            // пока страница ни разу не прислала пульс (старая версия table.html) — не вмешиваемся
            if (!pulseSeen || sleeping || !kioskActive()) return
            if (offline?.visibility == View.VISIBLE) return
            val quiet = System.currentTimeMillis() - lastAlive
            if (quiet < 180_000) return
            misses++
            lastAlive = System.currentTimeMillis()
            if (misses >= 2) {
                misses = 0
                recreateWeb()
            } else {
                web?.reload()
            }
        }
    }

    /** Пересоздать страницу целиком — когда перезагрузка не помогла. */
    private fun recreateWeb() {
        val w = web
        web = null
        try { root.removeView(w) } catch (_: Exception) {}
        w?.destroy()
        render()
    }

    // ---------- обновление программы ----------

    private var lastTouch = System.currentTimeMillis()

    private val updCheck = object : Runnable {
        override fun run() {
            ui.postDelayed(this, 6 * 3600_000L)
            if (prefs.setupAuto && !prefs.paused) Kiosk.applySetup(this@MainActivity)
            if (!prefs.autoUpdate || !Kiosk.isOwner(this@MainActivity)) return
            if (Updater.ready(this@MainActivity)) installIfCalm()
            else Updater.check(this@MainActivity) { v, _ -> if (v > 0) ui.post { installIfCalm() } }
        }
    }

    /** Ставим обновление только в спокойный момент: планшет спит или экрана не трогали 15 минут. */
    private fun installIfCalm() {
        if (!Updater.ready(this)) return
        val calm = sleeping || System.currentTimeMillis() - lastTouch > 15 * 60_000L
        if (calm) Updater.install(this)
        else ui.postDelayed({ installIfCalm() }, 5 * 60_000L)
    }

    /** Связь страницы с планшетом: window.EnjoyKiosk в JavaScript. */
    inner class Bridge {
        /** Яркость экрана 0.02–1, или -1 — как в настройках планшета. */
        @JavascriptInterface
        fun setBrightness(v: Double) {
            runOnUiThread {
                brightness = if (v < 0) WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE else v.coerceIn(0.02, 1.0).toFloat()
                if (!sleeping && !lostOn()) applyBrightness(brightness)   // 🚨 1.0.9: в режиме «Пропал» экран всегда на 100 %
            }
        }

        /** Ночной режим из админки: погасить экран на seconds секунд, потом включить самому. */
        @JavascriptInterface
        fun sleep(seconds: Int) {
            runOnUiThread { goSleep(seconds) }
        }

        @JavascriptInterface
        fun wake() {
            runOnUiThread { goWake() }
        }

        /** Сведения о планшете для админки (журнал «📟 Экраны столов»). */
        @JavascriptInterface
        fun info(): String = JSONObject()
            .put("app", BuildConfig.VERSION_NAME)
            .put("code", BuildConfig.VERSION_CODE)
            .put("owner", Kiosk.isOwner(this@MainActivity))
            .put("kiosk", !prefs.paused)
            .put("model", Build.MANUFACTURER + " " + Build.MODEL)
            .put("android", Build.VERSION.RELEASE)
            .put("webview", webViewVersion())
            .put("wifi", wifiLevel())
            .put("ip", ipAddress())
            .put("uptime", android.os.SystemClock.elapsedRealtime() / 1000L)
            .put("mem", freeMemMb())
            .put("disk", freeDiskMb())
            .put("upd", prefs.updVer)
            .put("updnote", prefs.updNote)
            .put("set", Kiosk.settingsReport(this@MainActivity))
            .put("lost", prefs.lost)
            .put("lostAuto", prefs.lostAuto)
            .put("home", prefs.homeSet().size)
            .put("frp", Kiosk.frpState(this@MainActivity))
            .put("ssid", Kiosk.wifiSsid(this@MainActivity))
            .put("wfres", prefs.wifiRes)
            .toString()

        /** Пульс страницы: пока он приходит, экран стола живой. */
        @JavascriptInterface
        fun alive() {
            lastAlive = System.currentTimeMillis()
            pulseSeen = true
            misses = 0
        }

        /** Короткая вибрация под нажатие кнопки на экране стола. */
        @JavascriptInterface
        fun vibrate(ms: Int) {
            val d = ms.coerceIn(5, 120).toLong()
            try {
                if (Build.VERSION.SDK_INT >= 31) {
                    val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as android.os.VibratorManager
                    vm.defaultVibrator.vibrate(android.os.VibrationEffect.createOneShot(d, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    val v = getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
                    @Suppress("DEPRECATION")
                    v.vibrate(android.os.VibrationEffect.createOneShot(d, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
                }
            } catch (_: Exception) {
            }
        }

        /** Перезагрузка планшета по команде из админки. */
        @JavascriptInterface
        fun reboot(): Boolean {
            runOnUiThread { toast("Перезагрузка по команде из админки…") }
            return Kiosk.reboot(this@MainActivity)
        }

        /** Заряд батареи для админки: {"level":85,"charging":true} */
        @JavascriptInterface
        fun battery(): String {
            val bm = getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
            return JSONObject()
                .put("level", bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY))
                .put("charging", bm.isCharging)
                .put("plugged", plugged())
                .toString()
        }

        /** Проверить обновление программы прямо сейчас (команда из админки). */
        @JavascriptInterface
        fun update() {
            Updater.check(this@MainActivity) { v, _ -> if (v > 0) ui.post { installIfCalm() } }
        }

        /** ⚙️ 1.0.7: «Настроить планшет» из админки. Возвращает, что поменяли. */
        /** 🚨 1.0.8: «Планшет пропал» из админки: {"title","text","phone"} */
        @JavascriptInterface
        fun lost(json: String) {
            runOnUiThread { if (!prefs.lost || prefs.lostMsg != json) setLost(true, json) }
        }

        /** Нашёлся — снять экран «Пропал». */
        @JavascriptInterface
        fun found() {
            runOnUiThread { if (prefs.lost) setLost(false, null) }
        }

        /** Последнее известное место (JSON) — страница отправляет его в админку. */
        @JavascriptInterface
        fun location(): String = prefs.lastLoc

        /** Защита от сброса по Google-аккаунту (числовой ID из админки). Возвращает 1/0/-1. */
        @JavascriptInterface
        fun setFrp(id: String): Int {
            val v = id.trim().filter { it.isDigit() }.take(30)
            if (v == prefs.frpId && (v.isEmpty() || Kiosk.frpState(this@MainActivity) == 1)) return Kiosk.frpState(this@MainActivity)
            return Kiosk.setFrp(this@MainActivity, v)
        }

        @JavascriptInterface
        fun setup(): String = Kiosk.applySetup(this@MainActivity)

        /** 🔋 Открыть экран батареи (там «Защита батареи 80 %») — сотрудник рядом с планшетом. */
        @JavascriptInterface
        fun openBattery() {
            runOnUiThread { visitSettings("battery") }
        }

        /** Открыть настройки Enjoy Kiosk (на Xiaomi там «Контроль активности» → «Нет ограничений»). */
        @JavascriptInterface
        fun openAppSettings() {
            runOnUiThread { visitSettings("app") }
        }

        @JavascriptInterface
        fun reload() {
            runOnUiThread { web?.reload() }
        }

        /** 👁 1.0.21: последний снимок экрана (base64 JPEG) — страница забирает и отправляет в админку. */
        @JavascriptInterface
        fun shotData(): String = lastShot

        /** 🔎 1.0.17: отчёт экрана для админки. */
        @JavascriptInterface
        fun dbg(): String {
            var r = "{}"
            val l = java.util.concurrent.CountDownLatch(1)
            runOnUiThread { try { r = screenReport() } catch (_: Throwable) {}; l.countDown() }
            try { l.await(2, java.util.concurrent.TimeUnit.SECONDS) } catch (_: Throwable) {}
            return r
        }

        /** 👁 1.0.11: снимок экрана стола для админки — ответ придёт в window.ethShotDone(base64 JPEG). */
        @JavascriptInterface
        fun shot() {
            runOnUiThread { takeShot() }
        }

        /**
         * ⬆️ 1.0.13: «Обновить сейчас» из админки — даже если за столом гости.
         * Есть новая программа — ставим сразу (экран вернётся сам), нет — просто перезагружаем страницу стола.
         */
        @JavascriptInterface
        fun updateNow() {
            runOnUiThread { toast("Обновление экрана стола…") }
            if (Updater.ready(this@MainActivity)) { ui.post { Updater.install(this@MainActivity) }; return }
            Updater.check(this@MainActivity) { v, _ ->
                ui.post { if (v > 0 && Updater.ready(this@MainActivity)) Updater.install(this@MainActivity) else web?.reload() }
            }
            // если проверка уже шла — поставим, как только скачается
            ui.postDelayed({ if (Updater.ready(this@MainActivity)) Updater.install(this@MainActivity) }, 90_000L)
        }

        /** 📶 1.0.11: Wi-Fi сети из админки: [{"s":"имя","p":"пароль"}]. Возвращает итог «имя ✓/✗». */
        @JavascriptInterface
        fun setWifi(json: String): String = Kiosk.setWifi(this@MainActivity, json)
    }

    // ---------- 👁 1.0.11: снимок экрана для админки ----------

    private fun takeShot() {
        val v = window.decorView
        val w = v.width
        val h = v.height
        if (w <= 0 || h <= 0) { shotRes = "окно 0×0"; return }
        val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        try {
            android.view.PixelCopy.request(window, bmp, { res ->
                if (res == android.view.PixelCopy.SUCCESS) { shotRes = "экран ок"; Thread { sendShot(bmp) }.start() }
                else { shotRes = "экран: ошибка $res, снимок программы"; bmp.recycle(); drawShot() }
            }, ui)
        } catch (e: Throwable) {
            shotRes = "экран: " + (e.message ?: e.javaClass.simpleName) + ", снимок программы"
            bmp.recycle()
            drawShot()
        }
    }

    /** 🔎 1.0.17: если Android не дал снимок экрана — рисуем то, что программа сама держит в окне. */
    private fun drawShot() {
        try {
            val w = root.width
            val h = root.height
            if (w <= 0 || h <= 0) return
            val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
            root.draw(android.graphics.Canvas(bmp))
            Thread { sendShot(bmp) }.start()
        } catch (_: Throwable) {}
    }

    /** 🔎 1.0.17: что сейчас на экране — для админки. */
    private fun screenReport(): String {
        val o = JSONObject()
        fun put(k: String, f: () -> Any?) { try { o.put(k, f() ?: JSONObject.NULL) } catch (e: Throwable) { try { o.put(k, "ошибка: " + e.javaClass.simpleName) } catch (_: Throwable) {} } }
        put("app") { BuildConfig.VERSION_NAME }
        put("interactive") { (getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive }
        put("display") { (getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager).getDisplay(0)?.state }
        put("resumed") { resumedNow }
        put("focus") { hasWindowFocus() }
        put("keyguard") { (getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager).isKeyguardLocked }
        put("lockTask") { (getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).lockTaskModeState }
        put("top") { (getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).appTasks.firstOrNull()?.taskInfo?.topActivity?.className }
        put("tasks") { (getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).appTasks.size }
        put("lost") { prefs.lost }
        put("lostAuto") { prefs.lostAuto }
        put("sleeping") { sleeping }
        put("cover") { lostCover?.let { c -> "в окне=" + (c.parent != null) + " виден=" + c.isShown + " " + c.width + "×" + c.height + " альфа=" + c.alpha } ?: "нет" }
        put("night") { nightCover?.let { "есть" } ?: "нет" }
        put("root") { (0 until root.childCount).joinToString(", ") { i -> val c = root.getChildAt(i); c.javaClass.simpleName + (if (c.visibility == View.VISIBLE) "" else "(скрыт)") + " " + c.width + "×" + c.height } }
        put("winBright") { window.attributes.screenBrightness }
        put("sysBright") { Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS) }
        put("autoBright") { Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE) }
        put("decor") { window.decorView.let { it.width.toString() + "×" + it.height + " виден=" + it.isShown + " альфа=" + it.alpha } }
        put("winFlags") { Integer.toHexString(window.attributes.flags) }
        put("shot") { shotRes }
        // 🔎 1.0.19: строки текста на картинке «Пропал» и маленький снимок того, что программа держит в окне
        put("coverText") {
            val c = lostCover as? android.view.ViewGroup
            val col = c?.getChildAt(0) as? android.view.ViewGroup
            if (col == null) "нет" else (0 until col.childCount).joinToString(" | ") { i ->
                val v = col.getChildAt(i)
                val t = (v as? TextView)?.text?.toString()?.take(12) ?: v.javaClass.simpleName
                t + " " + v.width + "×" + v.height + "@" + v.left + "," + v.top + (if (v.isShown) "" else " скрыт") +
                    ((v as? TextView)?.let { " цвет=" + Integer.toHexString(it.currentTextColor) + " " + it.textSize.toInt() + "px" } ?: "")
            } + " | колонка " + col.width + "×" + col.height
        }
        put("thumb") {
            val w = root.width; val h = root.height
            if (w <= 0 || h <= 0) null else {
                val big = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
                root.draw(android.graphics.Canvas(big))
                val sm = android.graphics.Bitmap.createScaledBitmap(big, 160, maxOf(1, 160 * h / w), true)
                big.recycle()
                val out = java.io.ByteArrayOutputStream()
                sm.compress(android.graphics.Bitmap.CompressFormat.JPEG, 60, out)
                sm.recycle()
                android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
            }
        }
        put("paused") { prefs.paused }
        put("owner") { Kiosk.isOwner(this) }
        return o.toString()
    }

    private fun sendShot(bmp: android.graphics.Bitmap) {
        try {
            val tw = minOf(720, bmp.width)
            val th = maxOf(1, (bmp.height.toLong() * tw / bmp.width).toInt())
            val sc = android.graphics.Bitmap.createScaledBitmap(bmp, tw, th, true)
            val out = java.io.ByteArrayOutputStream()
            sc.compress(android.graphics.Bitmap.CompressFormat.JPEG, 55, out)
            if (sc !== bmp) sc.recycle()
            val b64 = android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
            lastShot = b64   // 👁 1.0.21: страница забирает снимок сама (shotData), а не через вставку в команду
            shotRes = shotRes + ", готов " + (b64.length / 1024) + " КБ"
            ui.post { web?.evaluateJavascript("window.ethShotDone&&window.ethShotDone('" + b64 + "')", null) }
        } catch (_: Throwable) {
        } finally {
            bmp.recycle()
        }
    }

    // ---------- сведения о планшете ----------

    private fun webViewVersion(): String = try {
        if (Build.VERSION.SDK_INT >= 26) WebView.getCurrentWebViewPackage()?.versionName ?: "?" else "?"
    } catch (e: Throwable) { "?" }

    /** Уровень Wi-Fi в процентах (0 — не Wi-Fi). */
    private fun wifiLevel(): Int = try {
        @Suppress("DEPRECATION")
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
        @Suppress("DEPRECATION")
        val rssi = wm.connectionInfo.rssi
        if (rssi == 0 || rssi < -127) 0 else ((rssi + 100).coerceIn(0, 60) * 100 / 60)
    } catch (e: Throwable) { 0 }

    private fun ipAddress(): String = try {
        var found = ""
        val ifs = java.net.NetworkInterface.getNetworkInterfaces()
        while (ifs.hasMoreElements() && found.isEmpty()) {
            val ni = ifs.nextElement()
            if (!ni.isUp || ni.isLoopback) continue
            val addrs = ni.inetAddresses
            while (addrs.hasMoreElements()) {
                val a = addrs.nextElement()
                if (!a.isLoopbackAddress && a is java.net.Inet4Address) { found = a.hostAddress ?: ""; break }
            }
        }
        found
    } catch (e: Throwable) { "" }

    private fun freeMemMb(): Long = try {
        val mi = ActivityManager.MemoryInfo()
        (getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
        mi.availMem / 1048576L
    } catch (e: Throwable) { 0L }

    private fun freeDiskMb(): Long = try {
        val s = android.os.StatFs(filesDir.absolutePath)
        s.availableBytes / 1048576L
    } catch (e: Throwable) { 0L }

    // ---------- настройка: ссылка стола и PIN ----------

    private var urlField: EditText? = null

    private fun showSetup(change: Boolean) {
        setupShown = true
        if (!change) leaveLock()
        root.removeAllViews()
        val gold = Color.parseColor("#C79A4B")
        val ink = Color.parseColor("#F1E9DA")
        val dim = Color.parseColor("#A99F90")
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(32), dp(40), dp(32), dp(40))
        }
        fun label(t: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
            text = t; setTextColor(color); setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
            if (bold) typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(6), 0, dp(6))
        }
        col.addView(label("Enjoy Kiosk — настройка стола", 26f, gold, true))
        col.addView(label("1. В админке на компьютере: «📟 Экраны столов» → кнопка QR у нужного стола.\n2. Здесь нажмите «Сканировать QR-код стола» и наведите камеру.\n3. Придумайте PIN — он нужен, чтобы выйти из киоска (5 касаний в левом верхнем углу экрана).", 16f, dim))
        col.addView(Button(this).apply {
            text = "📷  Сканировать QR-код стола"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setOnClickListener { scan() }
        }, LinearLayout.LayoutParams(-1, dp(64)).apply { topMargin = dp(16) })
        col.addView(label("или вставьте ссылку стола:", 14f, dim))
        val uf = EditText(this).apply {
            setText(if (Prefs.isTableUrl(prefs.url)) prefs.url else "")
            hint = "https://enjoythehookah.ru/table.html?t=…&k=…"
            setTextColor(ink); setHintTextColor(dim)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            isSingleLine = true
        }
        urlField = uf
        col.addView(uf)
        col.addView(label(if (prefs.pin.length >= 4) "PIN (оставьте пустым, чтобы не менять):" else "Придумайте PIN, 4–8 цифр:", 14f, dim))
        val pf = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            setTextColor(ink); setHintTextColor(dim)
            hint = "PIN"
        }
        col.addView(pf)
        col.addView(Button(this).apply {
            text = "Сохранить и запустить"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setOnClickListener {
                val u = uf.text.toString().trim()
                val p = pf.text.toString()
                when {
                    !Prefs.isTableUrl(u) -> toast("Это не ссылка стола. Отсканируйте QR из админки.")
                    p.isNotEmpty() && p.length !in 4..8 -> toast("PIN — от 4 до 8 цифр")
                    p.isEmpty() && prefs.pin.length < 4 -> toast("Придумайте PIN")
                    else -> {
                        prefs.url = u
                        if (p.isNotEmpty()) prefs.pin = p
                        prefs.paused = false
                        askedPinning = false
                        render()
                        Kiosk.applyOwnerPolicies(this@MainActivity)
                        enterLock()
                    }
                }
            }
        }, LinearLayout.LayoutParams(-1, dp(64)).apply { topMargin = dp(20) })
        col.addView(label(
            "Режим владельца устройства: " + (if (Kiosk.isOwner(this)) "включён — полная блокировка" else "нет — будет закрепление экрана") +
                "\nВерсия " + BuildConfig.VERSION_NAME, 13f, dim))
        root.addView(ScrollView(this).apply { addView(col) }, FrameLayout.LayoutParams(-1, -1))
    }

    private fun scan() {
        val opts = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        GmsBarcodeScanning.getClient(this, opts).startScan()
            .addOnSuccessListener { b ->
                val v = b.rawValue
                if (Prefs.isTableUrl(v)) { urlField?.setText(v); toast("Стол " + Prefs.tableNo(v!!) + " — придумайте PIN и сохраните") }
                else toast("Это не QR-код стола")
            }
            .addOnFailureListener { e -> toast("Сканер недоступен: " + (e.message ?: "")) }
    }

    /** Ссылка стола, открытая из камеры («Открыть в Enjoy Kiosk»). */
    private fun handleLink(i: Intent?) {
        val s = i?.data?.toString() ?: return
        if (!Prefs.isTableUrl(s) || s == prefs.url) return
        if (!Prefs.isTableUrl(prefs.url) || prefs.pin.length < 4) {
            prefs.url = s
            if (::root.isInitialized && root.childCount > 0) showSetup(false)
        } else {
            ui.post {
                askPin {
                    prefs.url = s
                    web?.loadUrl(s)
                    toast("Теперь это экран стола " + Prefs.tableNo(s))
                }
            }
        }
    }

    // ---------- ночной режим: экран гаснет по расписанию из админки и включается сам ----------

    private var sleeping = false
    private var brightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
    private var nightCover: View? = null

    private fun applyBrightness(v: Float) {
        val lp = window.attributes
        lp.screenBrightness = v
        window.attributes = lp
    }

    private fun goSleep(seconds: Int) {
        if (lostOn()) return
        if (sleeping) { scheduleWake(seconds); return }
        sleeping = true
        ui.removeCallbacks(wakeUp)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        applyBrightness(0.01f)
        if (nightCover == null) {
            nightCover = View(this).apply { setBackgroundColor(Color.BLACK) }
            root.addView(nightCover, FrameLayout.LayoutParams(-1, -1))
        }
        scheduleWake(seconds)
        // режим владельца — гасим экран сразу; иначе он погаснет сам по таймауту экрана Android
        if (Kiosk.isOwner(this)) {
            ui.postDelayed({ if (sleeping) try { Kiosk.dpm(this).lockNow() } catch (_: Exception) {} }, 1500)
        }
    }

    private fun goWake() {
        if (!sleeping) return
        sleeping = false
        cancelWake()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        applyBrightness(brightness)
        nightCover?.let { root.removeView(it) }
        nightCover = null
        wakeUp.run()
        web?.reload()
    }

    private fun wakePending(): android.app.PendingIntent = android.app.PendingIntent.getBroadcast(
        this, 1, Intent(this, WakeReceiver::class.java),
        android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
    )

    private fun scheduleWake(seconds: Int) {
        if (seconds <= 0) return
        try {
            val am = getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            val at = System.currentTimeMillis() + seconds * 1000L
            // точно к минуте: обычный будильник в «глубоком сне» может опоздать на десятки минут
            val exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
            if (exact) am.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, at, wakePending())
            else am.setAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, at, wakePending())
        } catch (_: Exception) {
        }
    }

    private fun cancelWake() {
        try { (getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager).cancel(wakePending()) } catch (_: Exception) {}
    }

    // ---------- экран погасили кнопкой питания — включаем обратно ----------

    private val wakeUp = Runnable {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            @Suppress("DEPRECATION")
            pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
                "enjoykiosk:wake"
            ).acquire(5_000)
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        } catch (_: Exception) {
        }
    }

    private val screenWatch = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_SCREEN_OFF -> if (prefs.wake && kioskActive() && !sleeping) ui.postDelayed(wakeUp, 2_500)
                Intent.ACTION_SCREEN_ON -> ui.removeCallbacks(wakeUp)
            }
        }
    }

    private fun registerScreenWatch() {
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenWatch, f, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(screenWatch, f)
    }

    // ---------- интернет вернулся — обновляем страницу ----------

    private val netWatch = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            ui.post {
                if (offline?.visibility == View.VISIBLE) web?.reload()
                if (lostOn()) requestLocation()
            }
        }
    }

    private fun registerNetworkWatch() {
        try { (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).registerDefaultNetworkCallback(netWatch) } catch (_: Exception) {}
    }

    // ---------- мелочи ----------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
