package com.mandopc

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Build
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

const val PORT = 27500

/**
 * Lleva el estado del mando al otro equipo: como mando Bluetooth (HID, sin nada en la PC) o por WiFi (UDP al
 * programa MandoPC). Todo el E/S ocurre en un único hilo. [onStatus] avisa de cada cambio con un estado y un texto.
 */
@SuppressLint("MissingPermission")   // MainActivity comprueba el permiso de Bluetooth antes de usar hid()
class Link(private val ctx: Context, private val onStatus: (state: Int, text: String) -> Unit) {
    companion object {
        const val OFF = 0; const val BUSY = 1; const val ON = 2; const val ERROR = 3   // estados
        const val NONE = 0; const val WIFI = 1; const val HID = 2                      // modos
    }

    private val prefs = ctx.getSharedPreferences("pad", 0)
    private val exec = ScheduledThreadPoolExecutor(1).apply { rejectedExecutionHandler = ThreadPoolExecutor.DiscardPolicy() }
    private val hid = if (Build.VERSION.SDK_INT >= 28) Hid(ctx, exec, ::onHid) else null
    private var out: ((Int, Long) -> Unit)? = null   // out, socket, seq y hidHost solo se tocan desde el hilo de exec
    private var socket: DatagramSocket? = null
    private var seq = 0
    private var retry = 0
    private var hidHost: BluetoothDevice? = null
    private var paused = false
    private val turbo = AtomicInteger()
    @Volatile private var held = 0
    @Volatile private var sticks = 0L
    @Volatile private var comboButtons = 0
    @Volatile private var comboSticks = 0L

    @Volatile var mode = NONE; private set
    /** Dirección Bluetooth del equipo conectado en modo directo, o null. */
    @Volatile var host: String? = null; private set

    init {
        exec.scheduleWithFixedDelay({
            if (paused || mode != WIFI) return@scheduleWithFixedDelay
            if (out != null) flush()                        // latido: sin él, el programa de la PC suelta todos los botones
            else if (++retry % 30 == 0) wifi(savedIp())     // se cayó (WiFi inestable, programa reiniciado): reintenta cada 3 s
        }, 100, 100, TimeUnit.MILLISECONDS)
    }

    fun update(buttons: Int, axes: Long) {
        if (buttons == held && axes == sticks) return
        held = buttons; sticks = axes
        exec.execute(::flush)
    }

    fun turbo(mask: Int, on: Boolean) {
        turbo.updateAndGet { if (on) it or mask else it and mask.inv() }
        exec.execute(::flush)
    }

    fun turboDown(mask: Int) = turbo.get() and mask != 0

    /** Lo que una combinación en reproducción añade al mando: sus botones se suman y sus sticks mandan. */
    fun combo(buttons: Int, axes: Long) {
        comboButtons = buttons; comboSticks = axes
        exec.execute(::flush)
    }

    private fun flush() {
        val o = out ?: return
        try { o(held or turbo.get() or comboButtons, if (comboSticks != 0L) comboSticks else sticks) }
        catch (e: Exception) { drop(); onStatus(ERROR, ctx.getString(R.string.status_lost)) }
    }

    private fun drop() {
        out = null; host = null
        socket?.close(); socket = null
    }

    /** Reconecta con lo último que se usó. Devuelve false si nunca se ha conectado (primer arranque). */
    fun restore(bluetoothAllowed: Boolean): Boolean {
        val last = prefs.getString("last", null) ?: return false
        if (last.startsWith("W")) wifi(last.drop(1))
        else if (bluetoothAllowed) hid(runCatching {
            ctx.getSystemService(BluetoothManager::class.java)?.adapter?.getRemoteDevice(last.drop(1))
        }.getOrNull())
        return true
    }

    fun savedIp() = prefs.getString("last", null)?.takeIf { it.startsWith("W") }?.drop(1).orEmpty()

    // ---------- WiFi ----------

    /** ip vacía = buscar el programa de la PC por difusión en la red local. */
    fun wifi(ip: String) = exec.execute {
        hid?.stop(); drop()
        mode = WIFI
        prefs.edit().putString("last", "W$ip").apply()
        onStatus(BUSY, ctx.getString(R.string.status_searching))
        val s = DatagramSocket()
        try {
            s.broadcast = true
            s.soTimeout = 400
            val to = InetAddress.getByName(ip.ifBlank { "255.255.255.255" })
            val probe = "MPAD?".toByteArray()
            val reply = DatagramPacket(ByteArray(64), 64)
            repeat(3) {
                s.send(DatagramPacket(probe, probe.size, to, PORT))
                try { s.receive(reply) } catch (e: SocketTimeoutException) { return@repeat }
                val text = String(reply.data, 0, reply.length)
                if (!text.startsWith("MPAD!")) return@repeat
                s.connect(reply.socketAddress)
                // la primera respuesta incluye despertar la radio y resolver la dirección; la latencia se mide ya en marcha
                var ms = Double.MAX_VALUE
                repeat(3) {
                    val t0 = System.nanoTime()
                    s.send(DatagramPacket(probe, probe.size))
                    try { s.receive(reply); ms = minOf(ms, (System.nanoTime() - t0) / 1e6) } catch (e: SocketTimeoutException) {}
                }
                if (ms == Double.MAX_VALUE) throw IOException("sin respuesta")
                socket = s
                out = { b, a -> s.send(DatagramPacket(encode(++seq, b, a), 16)) }
                onStatus(ON, ctx.getString(R.string.status_wifi_on, text.drop(5), "%.0f".format(ms.coerceAtLeast(1.0))))
                return@execute
            }
            throw IOException("sin respuesta")
        } catch (e: IOException) {
            s.close()
            onStatus(ERROR, ctx.getString(R.string.status_wifi_fail))
        }
    }

    // ---------- Bluetooth directo ----------

    /** Activa el modo mando Bluetooth; con [target] conecta además con ese equipo ya emparejado. */
    fun hid(target: BluetoothDevice?) = exec.execute {
        if (mode != HID) drop()
        mode = HID
        if (target != null) hidHost = target
        prefs.edit().putString("last", "H" + (hidHost?.address ?: "")).apply()
        startHid()
    }

    private fun startHid() {
        val h = hid
        if (h == null || !h.start(hidHost)) { onStatus(ERROR, ctx.getString(R.string.status_bt_unsupported)); return }
        out = { b, a -> h.send(encodeHid(b, a)) }
        if (host == null) onHid(BUSY, hidHost)
    }

    private fun onHid(state: Int, device: BluetoothDevice?) {   // siempre en el hilo de exec
        if (mode != HID) return
        val name = device?.let { it.name ?: it.address }
        when (state) {
            ON -> {
                hidHost = device; host = device?.address
                prefs.edit().putString("last", "H" + device?.address).apply()
                onStatus(ON, ctx.getString(R.string.status_bt_on, name))
            }
            BUSY -> {
                host = null
                onStatus(BUSY, if (name != null) ctx.getString(R.string.status_bt_connecting, name) else ctx.getString(R.string.status_bt_wait))
            }
            else -> onStatus(ERROR, ctx.getString(R.string.status_bt_failed))
        }
    }

    // ---------- ciclo de vida ----------

    fun disconnect() = exec.execute {
        hid?.stop(); drop()
        mode = NONE
        prefs.edit().remove("last").apply()
        onStatus(OFF, ctx.getString(R.string.status_off))
    }

    /** La app dejó de verse: el mando Bluetooth se retira (Android lo haría igual) y el WiFi deja de enviar. */
    fun pause() = exec.execute {
        paused = true
        if (mode == HID) { out = null; host = null; hid?.stop() }
    }

    fun resume() = exec.execute {
        if (!paused) return@execute
        paused = false
        if (mode == HID) startHid()
    }

    fun close() { exec.execute { hid?.stop(); drop() }; exec.shutdown() }
}
