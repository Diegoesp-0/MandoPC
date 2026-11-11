package com.mandopc

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    lateinit var link: Link; private set
    lateinit var pad: PadView; private set
    var state = Link.OFF; private set
    var statusText = ""; private set

    private lateinit var panel: Panel
    private lateinit var editBar: LinearLayout
    private lateinit var editName: TextView
    private lateinit var editSize: List<View>
    private lateinit var editRemove: View
    private lateinit var recBar: LinearLayout

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 28)
            window.attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        statusText = getString(R.string.status_off)
        link = Link(applicationContext, ::onStatus)
        val root = FrameLayout(this)
        buildEditBar()
        buildRecBar()
        pad = PadView(this, link, { panel.open() }, ::onSelect)
        root.addView(pad)
        for (bar in listOf(editBar, recBar))
            root.addView(bar, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
                .apply { topMargin = dp(4); bottomMargin = dp(4) })
        panel = Panel(this, root)
        if (Build.VERSION.SDK_INT >= 28) root.setOnApplyWindowInsetsListener { _, insets ->
            panel.inset(insets.displayCutout?.safeInsetRight ?: 0)
            insets
        }
        setContentView(root)
        if (!link.restore(btPermitted() && btOn())) panel.open()   // primer arranque: empezar por conectar
    }

    @Suppress("DEPRECATION")   // estas banderas siguen funcionando en todas las versiones y evitan ramificar por API
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
    }

    // El mando Bluetooth vive mientras la app se ve (onStart/onStop), no solo mientras tiene el foco: el diálogo
    // de emparejar la pausa, y retirar el mando en ese instante haría fallar el emparejamiento.
    override fun onStart() { super.onStart(); link.resume() }
    override fun onResume() { super.onResume(); panel.rebuild() }
    override fun onPause() { super.onPause(); stopRecording(false); pad.releaseAll() }
    override fun onStop() { super.onStop(); link.pause() }
    override fun onDestroy() { super.onDestroy(); link.close() }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        when {
            panel.isOpen -> panel.back()
            pad.recording -> stopRecording(false)
            pad.editing -> pad.editing = false
            else -> super.onBackPressed()
        }
    }

    private fun onStatus(s: Int, text: String) = runOnUiThread {
        state = s
        statusText = text
        pad.setStatus(s, text)
        // al conectar desde una página de conexión ya no hay nada que hacer en el panel: se va solo
        if (s == Link.ON && panel.isOpen && (panel.page == Panel.BT || panel.page == Panel.WIFI)) panel.close()
        else panel.onStatus()
    }

    // ---------- barra de edición ----------

    private fun buildEditBar() {
        editName = TextView(this).apply { textSize = 15f; typeface = font(800); setTextColor(C.BONE) }
        val smaller = iconButton('-', getString(R.string.smaller)) { pad.resize(1 / 1.08f) }
        val bigger = iconButton('+', getString(R.string.bigger)) { pad.resize(1.08f) }
        editSize = listOf(smaller, bigger)
        editRemove = pillButton(getString(R.string.remove), false) { pad.selected?.let(pad::remove) }
        editBar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(C.SHEET, dp(32).toFloat(), C.EDGE, dp(1) + 1)
            elevation = dp(8).toFloat()
            setPadding(dp(22), 0, 0, 0)
            visibility = View.GONE
            addView(editName)
            addView(smaller, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginStart = dp(10) })
            addView(bigger, dp(48), dp(48))
            addView(editRemove, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(6) })
            addView(pillButton(getString(R.string.edit_done), true) { pad.editing = false },
                LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(12) })
        }
    }

    private fun onSelect(el: El?) {
        editBar.visibility = if (pad.editing) View.VISIBLE else View.GONE
        // la barra va abajo, salvo que el control elegido esté ahí: entonces sube para no taparlo
        (editBar.layoutParams as FrameLayout.LayoutParams).gravity =
            Gravity.CENTER_HORIZONTAL or if (el != null && el.y > 0.72f) Gravity.TOP else Gravity.BOTTOM
        editBar.requestLayout()
        editName.text = el?.let(pad::name) ?: getString(R.string.edit_hint)
        editSize.forEach { it.visibility = if (el != null) View.VISIBLE else View.GONE }
        editRemove.visibility = if (el != null && pad.isExtra(el)) View.VISIBLE else View.GONE
    }

    // ---------- grabación de combinaciones ----------

    private fun buildRecBar() {
        recBar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(C.SHEET, dp(32).toFloat(), C.EDGE, dp(1) + 1)
            elevation = dp(8).toFloat()
            setPadding(dp(20), 0, 0, 0)
            visibility = View.GONE
            addView(View(context).apply { background = rounded(C.B, dp(6).toFloat()) },
                LinearLayout.LayoutParams(dp(12), dp(12)).apply { marginEnd = dp(12) })
            addView(TextView(context).apply { text = getString(R.string.rec_hint); textSize = 15f; typeface = font(800); setTextColor(C.BONE) })
            addView(pillButton(getString(R.string.cancel), false) { stopRecording(false) },
                LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(16) })
            addView(pillButton(getString(R.string.rec_save), true) { stopRecording(true) },
                LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(8) })
        }
    }

    /** Empieza a grabar lo que se pulse en el mando; se sigue enviando a la PC mientras tanto. */
    fun record() { pad.record(); recBar.visibility = View.VISIBLE }

    private fun stopRecording(save: Boolean) { pad.stopRecording(save); recBar.visibility = View.GONE }

    // ---------- Bluetooth: permisos y diálogos del sistema ----------

    private fun adapter(): BluetoothAdapter? = getSystemService(BluetoothManager::class.java)?.adapter

    fun btPermitted() = Build.VERSION.SDK_INT < 31 || BT_PERMISSIONS.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    fun btOn() = adapter()?.isEnabled == true
    fun askBluetooth() = requestPermissions(BT_PERMISSIONS, 1)

    @SuppressLint("MissingPermission")
    fun btName(): String = adapter()?.name ?: Build.MODEL

    @SuppressLint("MissingPermission")
    fun bonded(): List<BluetoothDevice> = adapter()?.bondedDevices.orEmpty()
        .sortedByDescending { it.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.COMPUTER }

    @SuppressLint("MissingPermission")
    fun enableBluetooth() { runCatching { startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) } }

    @SuppressLint("MissingPermission")
    fun makeDiscoverable() {
        runCatching {
            startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120))
        }
    }

    override fun onRequestPermissionsResult(code: Int, permissions: Array<String>, results: IntArray) {
        // si Android ya no muestra el diálogo (se negó antes "para siempre"), lo único que queda es abrir los ajustes de la app
        if (!btPermitted() && !shouldShowRequestPermissionRationale(Manifest.permission.BLUETOOTH_CONNECT))
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        panel.rebuild()
    }

    private companion object {
        val BT_PERMISSIONS = arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
    }
}
