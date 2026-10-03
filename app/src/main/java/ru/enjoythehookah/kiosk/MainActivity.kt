package ru.enjoythehookah.kiosk

import android.annotation.SuppressLint
import android.app.Activity
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
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLink(intent)
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        if (!prefs.paused) {
            Kiosk.applyOwnerPolicies(this)
            enterLock()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
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
        } else if (!askedPinning) {
            // без режима владельца — обычное закрепление экрана (Android спросит подтверждение)
            askedPinning = true
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
        add(if (prefs.wake) "💡  Будить погасший экран: ВКЛ" else "💡  Будить погасший экран: ВЫКЛ") {
            prefs.wake = !prefs.wake
            toast(if (prefs.wake) "Экран будет включаться сам" else "Экран больше не будится")
        }
        add("🔑  Сменить PIN") { changePin() }
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
                    "Будить экран: " + (if (prefs.wake) "да" else "нет")
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
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            visibility = View.GONE
        }
        root.addView(offline, FrameLayout.LayoutParams(-1, -1))
        if (w.url != prefs.url) w.loadUrl(prefs.url)
        hideSystemBars()
    }

    private val retry = Runnable { web?.reload() }

    private fun setOffline(on: Boolean) {
        offline?.visibility = if (on) View.VISIBLE else View.GONE
        ui.removeCallbacks(retry)
        if (on) ui.postDelayed(retry, 10_000)
    }

    /** Связь страницы с планшетом: window.EnjoyKiosk в JavaScript. */
    inner class Bridge {
        /** Яркость экрана 0.02–1, или -1 — как в настройках планшета. */
        @JavascriptInterface
        fun setBrightness(v: Double) {
            runOnUiThread {
                val lp = window.attributes
                lp.screenBrightness = if (v < 0) WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE else v.coerceIn(0.02, 1.0).toFloat()
                window.attributes = lp
            }
        }

        @JavascriptInterface
        fun info(): String = JSONObject()
            .put("app", BuildConfig.VERSION_NAME)
            .put("owner", Kiosk.isOwner(this@MainActivity))
            .put("kiosk", !prefs.paused)
            .toString()

        @JavascriptInterface
        fun reload() {
            runOnUiThread { web?.reload() }
        }
    }

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
                Intent.ACTION_SCREEN_OFF -> if (prefs.wake && kioskActive()) ui.postDelayed(wakeUp, 2_500)
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
            ui.post { if (offline?.visibility == View.VISIBLE) web?.reload() }
        }
    }

    private fun registerNetworkWatch() {
        try { (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).registerDefaultNetworkCallback(netWatch) } catch (_: Exception) {}
    }

    // ---------- мелочи ----------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
