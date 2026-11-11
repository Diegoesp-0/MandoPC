package com.mandopc

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import java.util.concurrent.Executor

/**
 * Bluetooth directo: registra el teléfono como un mando HID para que la PC lo empareje sin instalar nada.
 * [onState] recibe Link.BUSY (esperando o conectando), Link.ON (con el equipo conectado) o Link.ERROR.
 */
@TargetApi(28)
@SuppressLint("MissingPermission")   // quien llama comprueba antes el permiso de Bluetooth
class Hid(private val ctx: Context, private val exec: Executor, private val onState: (Int, BluetoothDevice?) -> Unit) {
    private val adapter get() = ctx.getSystemService(BluetoothManager::class.java)?.adapter
    private val sdp = BluetoothHidDeviceAppSdpSettings("MandoPC", "Gamepad", "MandoPC", BluetoothHidDevice.SUBCLASS2_GAMEPAD, HID_DESCRIPTOR)
    @Volatile private var proxy: BluetoothHidDevice? = null
    @Volatile private var host: BluetoothDevice? = null
    @Volatile private var want: BluetoothDevice? = null
    @Volatile private var registered = false
    @Volatile private var pending = false

    private val callback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(plugged: BluetoothDevice?, isRegistered: Boolean) {
            registered = isRegistered
            if (!isRegistered) { host = null; return }
            (want ?: plugged)?.let { proxy?.connect(it) }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> { host = device; onState(Link.ON, device) }
                BluetoothProfile.STATE_CONNECTING -> onState(Link.BUSY, device)
                BluetoothProfile.STATE_DISCONNECTED -> if (host == null || device == host) { host = null; onState(Link.BUSY, null) }
            }
        }
    }

    private val listener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, p: BluetoothProfile) {
            if (!pending) { adapter?.closeProfileProxy(profile, p); return }   // se canceló mientras el sistema respondía
            pending = false
            proxy = p as BluetoothHidDevice
            register()
        }

        override fun onServiceDisconnected(profile: Int) { proxy = null; host = null; registered = false }
    }

    private fun register() {
        if (proxy?.registerApp(sdp, null, null, exec, callback) != true) onState(Link.ERROR, null)
    }

    /** Empieza a anunciarse como mando y, si se indica, conecta con [target]. Devuelve false si el teléfono no ofrece este perfil. */
    fun start(target: BluetoothDevice?): Boolean {
        want = target
        val p = proxy
        if (p != null) {
            if (!registered) register()
            else if (target != null && target != host) { host?.let(p::disconnect); p.connect(target) }
            return true
        }
        if (pending) return true
        runCatching { ctx.startForegroundService(Intent(ctx, HidService::class.java)) }
        pending = adapter?.getProfileProxy(ctx, listener, BluetoothProfile.HID_DEVICE) == true
        return pending
    }

    fun stop() {
        val p = proxy
        proxy = null; host = null; registered = false; pending = false
        if (p != null) runCatching { p.unregisterApp(); adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, p) }
        ctx.stopService(Intent(ctx, HidService::class.java))
    }

    fun send(report: ByteArray) {
        val h = host ?: return
        proxy?.sendReport(h, 1, report)
    }
}

/**
 * Servicio en primer plano sin lógica propia. Android retira el registro del mando Bluetooth en cuanto la app deja
 * de estar en primer plano, y eso incluye el instante en que el diálogo de emparejar la tapa: justo cuando la PC
 * pregunta qué tipo de dispositivo es. Con este servicio la app conserva la prioridad y el registro sobrevive.
 */
class HidService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("mando", getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "mando").setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(getString(R.string.notif_title)).setContentText(getString(R.string.notif_text)).setOngoing(true).build()
        runCatching {
            if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            else startForeground(1, n)
        }
        return START_NOT_STICKY
    }
}
