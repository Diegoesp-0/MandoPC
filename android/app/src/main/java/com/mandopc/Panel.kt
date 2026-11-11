package com.mandopc

import android.annotation.SuppressLint
import android.os.Build
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.max
import kotlin.math.min

/** Panel lateral con todo lo que no es jugar: elegir la conexión, editar el mando y las teclas turbo. */
class Panel(private val a: MainActivity, root: FrameLayout) {
    companion object { const val HOME = 0; const val BT = 1; const val WIFI = 2; const val TURBO = 3; const val COMBO = 4 }

    private val list = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
    private val sheet = ScrollView(a).apply {
        setBackgroundColor(C.SHEET)
        isFillViewport = true
        isClickable = true   // que un toque en una zona vacía no llegue al mando de debajo
        elevation = a.dp(16).toFloat()
        visibility = View.GONE
        addView(list, ViewGroup.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }
    private val scrim = View(a).apply {
        setBackgroundColor(0xB3080C16.toInt())
        contentDescription = a.getString(R.string.close)
        visibility = View.GONE
        setOnClickListener { close() }
    }
    private val sheetWidth: Int
    private var statusText: TextView? = null
    private var statusDot: View? = null
    private var ask: Triple<Char, Int, () -> Unit>? = null   // confirmación pendiente: dónde se muestra, texto del botón, qué hace

    // formulario de tecla turbo
    private val masks = intArrayOf(0x1000, 0x2000, 0x4000, 0x8000, 0x100, 0x200, LT, RT)
    private var tButton = 0
    private var tTimes = 5
    private var tHz = 10

    var page = HOME; private set
    val isOpen get() = sheet.visibility == View.VISIBLE

    init {
        val dm = a.resources.displayMetrics
        val long = max(dm.widthPixels, dm.heightPixels)
        sheetWidth = min(long, max(a.dp(380), long * 46 / 100))
        root.addView(scrim, MATCH_PARENT, MATCH_PARENT)
        root.addView(sheet, FrameLayout.LayoutParams(sheetWidth, MATCH_PARENT, Gravity.END))
        inset(0)
    }

    /** Margen extra a la derecha para que la cámara o el borde curvo no tapen el contenido. */
    fun inset(right: Int) = list.setPadding(a.dp(24), a.dp(14), a.dp(24) + right, a.dp(28))

    fun open(p: Int = HOME) {
        val wasOpen = isOpen
        ask = null
        page = p
        build()
        sheet.scrollTo(0, 0)
        scrim.animate().cancel(); sheet.animate().cancel()
        if (!wasOpen) { scrim.alpha = 0f; sheet.translationX = sheetWidth.toFloat() }
        scrim.visibility = View.VISIBLE; sheet.visibility = View.VISIBLE
        scrim.animate().alpha(1f).setDuration(180)
        sheet.animate().translationX(0f).setDuration(220).setInterpolator(DecelerateInterpolator())
    }

    fun close() {
        if (!isOpen) return
        hideKeyboard()
        scrim.animate().alpha(0f).setDuration(160).withEndAction { scrim.visibility = View.GONE }
        sheet.animate().translationX(sheetWidth.toFloat()).setDuration(180).setInterpolator(AccelerateInterpolator())
            .withEndAction { sheet.visibility = View.GONE }
    }

    fun back() = if (page != HOME) open(HOME) else close()

    /** El estado de la conexión cambió. */
    fun onStatus() {
        if (!isOpen) return
        if (page == WIFI) showStatus()   // sin reconstruir: no borrar lo que se esté escribiendo
        else if (page != TURBO) rebuild()
    }

    fun rebuild() {
        if (!isOpen) return
        val y = sheet.scrollY
        build()
        sheet.post { sheet.scrollTo(0, y) }
    }

    private fun build() {
        list.removeAllViews()
        statusText = null; statusDot = null
        when (page) { BT -> bluetooth(); WIFI -> wifi(); TURBO -> turbo(); COMBO -> combo(); else -> home() }
    }

    // ---------- páginas ----------

    private fun home() {
        header(str(R.string.app_name), false)
        statusRow(4)
        if (a.link.mode != Link.NONE) add(a.pillButton(str(R.string.disconnect), false) { a.link.disconnect() }, 14, WRAP_CONTENT)
        heading(R.string.group_connection)
        row('b', str(R.string.bt_title), str(R.string.bt_summary), mark(Link.HID)) { open(BT) }
        divider()
        row('w', str(R.string.wifi_title), str(R.string.wifi_summary), mark(Link.WIFI)) { open(WIFI) }
        heading(R.string.group_pad)
        add(label(str(R.string.style_title), 16.5f, C.BONE, 800), 12)
        chips(listOf("Xbox", "PlayStation", "Switch"), a.pad.style, 3) { i ->
            // recolocar borra lo que el usuario haya movido a mano: solo entonces se pregunta
            // (tocar la que ya está elegida también vale: devuelve los controles a su sitio)
            if (a.pad.customized()) ask = Triple('s', R.string.style_do) { a.pad.applyStyle(i) } else a.pad.applyStyle(i)
            rebuild()
        }
        confirm('s', R.string.style_confirm)
        row('e', str(R.string.edit_title), str(R.string.edit_summary)) { close(); a.pad.editing = true }
        divider()
        val count = a.pad.turbos().size
        row('t', str(R.string.turbo_title), str(R.string.turbo_summary), if (count > 0) label("$count", 15f, C.BONE, 800) else null) { open(TURBO) }
        divider()
        val combos = a.pad.combos().size
        row('c', str(R.string.combo_title), str(R.string.combo_summary), if (combos > 0) label("$combos", 15f, C.BONE, 800) else null) { open(COMBO) }
        divider()
        row('r', str(R.string.reset_title), str(R.string.reset_summary)) { ask = Triple('r', R.string.reset_do) { a.pad.reset() }; rebuild() }
        confirm('r', R.string.reset_confirm)
        val version = a.packageManager.getPackageInfo(a.packageName, 0).versionName
        add(label(str(R.string.version, version ?: ""), 12.5f, C.MUTED), 28)
    }

    private fun mark(mode: Int) =
        if (a.link.mode == mode && a.state == Link.ON) label(str(R.string.connected), 13.5f, C.BONE, 800) else null

    @SuppressLint("MissingPermission")   // btPermitted() se comprueba en la primera rama
    private fun bluetooth() {
        header(str(R.string.bt_title), true)
        add(label(str(R.string.bt_intro), 14.5f, C.MUTED), 4)
        when {
            Build.VERSION.SDK_INT < 28 -> add(label(str(R.string.bt_old), 15f, C.BONE), 20)
            !a.btPermitted() -> {
                add(label(str(R.string.bt_permission), 15f, C.BONE), 20)
                add(a.pillButton(str(R.string.bt_permission_do), true) { a.askBluetooth() }, 14, WRAP_CONTENT)
            }
            !a.btOn() -> {
                add(label(str(R.string.bt_off), 15f, C.BONE), 20)
                add(a.pillButton(str(R.string.bt_enable), true) { a.enableBluetooth() }, 14, WRAP_CONTENT)
            }
            a.link.mode == Link.WIFI && a.state == Link.ON ->   // no cortar un WiFi que funciona solo por mirar esta página
                add(a.pillButton(str(R.string.bt_switch), true) { a.link.hid(null) }, 20, WRAP_CONTENT)
            else -> {
                if (a.link.mode != Link.HID) a.link.hid(null)   // elegir este modo es empezar a anunciarse como mando
                statusRow(20)
                step(1, str(R.string.bt_step1), a.pillButton(str(R.string.bt_visible), false) { a.makeDiscoverable() })
                step(2, str(R.string.bt_step2, a.btName()))
                step(3, str(R.string.bt_step3))
                heading(R.string.bt_paired)
                val devices = a.bonded()
                if (devices.isEmpty()) add(label(str(R.string.bt_none), 14f, C.MUTED), 8)
                for (d in devices) {
                    val here = a.link.host == d.address
                    row(null, d.name ?: d.address, str(if (here) R.string.connected else R.string.bt_tap)) { a.link.hid(d) }
                    divider()
                }
                add(label(str(R.string.bt_repair), 13.5f, C.MUTED), 18)
            }
        }
    }

    private fun wifi() {
        header(str(R.string.wifi_title), true)
        add(label(str(R.string.wifi_intro), 14.5f, C.MUTED), 4)
        statusRow(20)
        add(a.pillButton(str(R.string.wifi_search), true) { hideKeyboard(); a.link.wifi("") }, 18)
        add(label(str(R.string.wifi_manual), 14f, C.MUTED), 30)
        val ip = EditText(a).apply {
            hint = str(R.string.wifi_hint)
            setText(a.link.savedIp())
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            textSize = 16f
            typeface = a.font(600)
            setTextColor(C.BONE)
            setHintTextColor(C.MUTED and 0x99FFFFFF.toInt())
            background = rounded(C.SHELL, a.dp(14).toFloat(), C.EDGE, a.dp(1) + 1)
            setPadding(a.dp(16), 0, a.dp(16), 0)
            minHeight = a.dp(48)
        }
        fun go() { hideKeyboard(); a.link.wifi(ip.text.toString().trim()) }
        ip.setOnEditorActionListener { _, _, _ -> go(); true }
        val bar = LinearLayout(a).apply { gravity = Gravity.CENTER_VERTICAL }
        bar.addView(ip, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        bar.addView(a.pillButton(str(R.string.wifi_connect), false) { go() },
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = a.dp(10) })
        add(bar, 10)
    }

    private fun turbo() {
        header(str(R.string.turbo_title), true)
        add(label(str(R.string.turbo_intro), 14.5f, C.MUTED), 4)
        heading(R.string.turbo_button)
        chips(masks.map { buttonName(it, a.pad.style) }, tButton, 4) { tButton = it; rebuild() }
        heading(R.string.turbo_times)
        stepper(if (tTimes == 0) str(R.string.turbo_hold) else "$tTimes", { tTimes = max(0, tTimes - 1) }, { tTimes = min(50, tTimes + 1) })
        heading(R.string.turbo_speed)
        stepper(str(R.string.turbo_speed_value, tHz), { tHz = max(1, tHz - 1) }, { tHz = min(20, tHz + 1) })
        add(a.pillButton(str(R.string.turbo_add), true) {
            a.pad.add(El('B', "turbo", masks[tButton], tTimes, tHz, 0.5f, 0.62f, 0.08f))
            close()
        }, 26)
        val mine = a.pad.turbos()
        if (mine.isNotEmpty()) heading(R.string.turbo_yours)
        for (e in mine) {
            val what = if (e.turbo == 0) str(R.string.turbo_item_hold, e.hz) else str(R.string.turbo_item, e.turbo, e.hz)
            row(null, a.pad.name(e), what, a.pillButton(str(R.string.remove), false) { a.pad.remove(e); rebuild() })
            divider()
        }
    }

    private fun combo() {
        header(str(R.string.combo_title), true)
        add(label(str(R.string.combo_intro), 14.5f, C.MUTED), 4)
        add(a.pillButton(str(R.string.combo_record), true) { close(); a.record() }, 20)
        val mine = a.pad.combos()
        if (mine.isNotEmpty()) heading(R.string.combo_yours)
        for (e in mine) {
            val ms = runCatching { decodeSteps(e.label) }.getOrDefault(emptyList()).sumOf { it.wait }
            row(null, a.pad.name(e), str(R.string.combo_item, "%.1f".format(ms / 1000.0)),
                a.pillButton(str(R.string.remove), false) { a.pad.remove(e); rebuild() })
            divider()
        }
    }

    // ---------- piezas ----------

    private fun str(id: Int, vararg args: Any) = a.getString(id, *args)

    private fun label(s: CharSequence, size: Float, color: Int, weight: Int = 600) = TextView(a).apply {
        text = s
        textSize = size
        typeface = a.font(weight)
        setTextColor(color)
        setLineSpacing(0f, 1.2f)
    }

    private fun <T : View> add(v: T, top: Int = 0, width: Int = MATCH_PARENT): T {
        list.addView(v, LinearLayout.LayoutParams(width, WRAP_CONTENT).apply { topMargin = a.dp(top) })
        return v
    }

    private fun header(title: String, sub: Boolean) {
        val bar = LinearLayout(a).apply { gravity = Gravity.CENTER_VERTICAL }
        if (sub) bar.addView(a.iconButton('<', str(R.string.back)) { open(HOME) }, a.dp(48), a.dp(48))
        bar.addView(label(title, 22f, C.BONE, 800), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        bar.addView(a.iconButton('x', str(R.string.close)) { close() }, a.dp(48), a.dp(48))
        // los iconos llevan 12 dp de aire propio: se compensa para que queden alineados con el texto
        (add(bar).layoutParams as LinearLayout.LayoutParams).setMargins(if (sub) -a.dp(14) else 0, 0, -a.dp(12), 0)
    }

    private fun heading(id: Int) = add(label(str(id), 13.5f, C.MUTED, 800), 26)

    /** Opciones excluyentes: la elegida va rellena. */
    private fun chips(labels: List<String>, selected: Int, perRow: Int, onPick: (Int) -> Unit) {
        val r = a.dp(23).toFloat()
        for (first in labels.indices step perRow) {
            val bar = LinearLayout(a)
            for (i in first until min(first + perRow, labels.size)) {
                val on = i == selected
                bar.addView(label(labels[i], 15.5f, if (on) C.SHELL else C.BONE, 800).apply {
                    gravity = Gravity.CENTER
                    minHeight = a.dp(46)
                    background = pressable(if (on) rounded(C.BONE, r) else rounded(0, r, C.EDGE, a.dp(1) + 1), r, dark = on)
                    isClickable = true
                    isFocusable = true
                    isSelected = on
                    setOnClickListener { onPick(i) }
                }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { if (i > first) marginStart = a.dp(8) })
            }
            add(bar, 10)
        }
    }

    /** Si hay una confirmación pendiente para este punto de la página, la muestra aquí. */
    private fun confirm(at: Char, message: Int) {
        val (where, yes, action) = ask ?: return
        if (where != at) return
        add(label(str(message), 15f, C.BONE), 12)
        val bar = LinearLayout(a)
        bar.addView(a.pillButton(str(R.string.cancel), false) { ask = null; rebuild() })
        bar.addView(a.pillButton(str(yes), true) { action(); ask = null; rebuild() },
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = a.dp(12) })
        add(bar, 12)
    }

    private fun divider() { add(View(a).apply { setBackgroundColor(C.LINE) }).layoutParams.height = a.dp(1) }

    private fun row(icon: Char?, title: String, summary: String, trailing: View? = null, onClick: (() -> Unit)? = null) {
        val row = LinearLayout(a).apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = a.dp(64)
            setPadding(0, a.dp(10), 0, a.dp(10))
        }
        if (icon != null) row.addView(ImageView(a).apply { setImageDrawable(Glyph(icon, C.BONE)) },
            LinearLayout.LayoutParams(a.dp(24), a.dp(24)).apply { marginEnd = a.dp(18) })
        val texts = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(label(title, 16.5f, C.BONE, 800))
        texts.addView(label(summary, 13.5f, C.MUTED))
        row.addView(texts, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        if (trailing != null) row.addView(trailing, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = a.dp(12) })
        if (onClick != null) {
            row.background = pressable(null, 0f)
            row.isClickable = true
            row.isFocusable = true
            row.setOnClickListener { onClick() }
        }
        add(row)
    }

    /** Paso numerado de un proceso que sí es una secuencia (emparejar). */
    private fun step(n: Int, text: String, action: View? = null) {
        val row = LinearLayout(a)
        row.addView(label("$n", 14f, C.SHELL, 800).apply {
            gravity = Gravity.CENTER
            includeFontPadding = false
            background = rounded(C.BONE, a.dp(13).toFloat())
        }, LinearLayout.LayoutParams(a.dp(26), a.dp(26)).apply { marginEnd = a.dp(14) })
        val column = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        column.addView(label(text, 15f, C.BONE))
        if (action != null) column.addView(action, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = a.dp(10) })
        row.addView(column, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        add(row, 18)
    }

    private fun stepper(value: String, minus: () -> Unit, plus: () -> Unit) {
        val bar = LinearLayout(a).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(C.SHELL, a.dp(24).toFloat(), C.LINE, a.dp(1) + 1)
        }
        bar.addView(a.iconButton('-', str(R.string.less)) { minus(); rebuild() }, a.dp(48), a.dp(48))
        bar.addView(label(value, 16f, C.BONE, 800).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        bar.addView(a.iconButton('+', str(R.string.more)) { plus(); rebuild() }, a.dp(48), a.dp(48))
        add(bar, 10)
    }

    private fun statusRow(top: Int) {
        val row = LinearLayout(a).apply { gravity = Gravity.CENTER_VERTICAL }
        statusDot = View(a).also { row.addView(it, LinearLayout.LayoutParams(a.dp(10), a.dp(10)).apply { marginEnd = a.dp(12) }) }
        statusText = label("", 14.5f, C.BONE).also { row.addView(it, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)) }
        add(row, top)
        showStatus()
    }

    private fun showStatus() {
        statusText?.text = a.statusText
        val color = when (a.state) { Link.ON -> C.BONE; Link.ERROR -> C.B; Link.BUSY -> C.MUTED; else -> C.EDGE }
        statusDot?.background = rounded(color, a.dp(5).toFloat())
    }

    private fun hideKeyboard() {
        a.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(sheet.windowToken, 0)
    }
}
