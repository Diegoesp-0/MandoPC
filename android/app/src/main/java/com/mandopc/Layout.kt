package com.mandopc

import java.nio.ByteBuffer
import java.nio.ByteOrder

// Los 16 bits bajos de una máscara son los botones en formato XInput; estos dos son internos para los gatillos.
const val LT = 0x10000
const val RT = 0x20000

/**
 * Un elemento del mando. x,y = centro (fracción del ancho/alto de pantalla); r = radio (fracción del alto).
 * kind: 'B' botón, 'S' stick (mask 0 = izquierdo, 1 = derecho), 'D' cruceta, 'M' menú,
 *       'C' combinación grabada (label = sus pasos codificados, mask = su número: C1, C2…).
 * turbo: -1 botón normal, 0 repite mientras se mantenga, n = n pulsaciones por toque. hz = pulsaciones por segundo.
 */
class El(val kind: Char, val label: String, val mask: Int, val turbo: Int, val hz: Int, var x: Float, var y: Float, var r: Float) {
    override fun toString() = "$kind;$label;$mask;$turbo;$hz;$x;$y;$r"
}

fun parseLayout(s: String): MutableList<El> = s.lines().filter { it.isNotBlank() }.map {
    val f = it.split(';')
    El(f[0][0], f[1], f[2].toInt(), f[3].toInt(), f[4].toInt(), f[5].toFloat(), f[6].toFloat(), f[7].toFloat())
}.toMutableList()

/** WiFi: paquete de 16 bytes, seq + XUSB_REPORT (botones, LT, RT, LX, LY, RX, RY), tal cual lo consume el programa de la PC. */
fun encode(seq: Int, buttons: Int, sticks: Long): ByteArray = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
    .putInt(seq).putShort(buttons.toShort())
    .put((if (buttons and LT != 0) 255 else 0).toByte()).put((if (buttons and RT != 0) 255 else 0).toByte())
    .putLong(sticks).array()

// ---------- Bluetooth directo: el teléfono es un mando HID ----------

/** Descriptor HID: 16 botones, cruceta como "hat" y 4 ejes de 16 bits. Informe con ID 1 y 11 bytes de datos. */
val HID_DESCRIPTOR: ByteArray = intArrayOf(
    0x05, 0x01,             // Usage Page (Generic Desktop)
    0x09, 0x05,             // Usage (Game Pad)
    0xA1, 0x01,             // Collection (Application)
    0x85, 0x01,             //   Report ID (1)
    0x05, 0x09,             //   Usage Page (Button)
    0x19, 0x01, 0x29, 0x10, //   Usage 1..16
    0x15, 0x00, 0x25, 0x01, //   Logical 0..1
    0x75, 0x01, 0x95, 0x10, //   16 campos de 1 bit
    0x81, 0x02,             //   Input (Data,Var,Abs)
    0x05, 0x01,             //   Usage Page (Generic Desktop)
    0x09, 0x39,             //   Usage (Hat switch)
    0x15, 0x00, 0x25, 0x07, //   Logical 0..7; fuera de rango = suelto
    0x35, 0x00, 0x46, 0x3B, 0x01, // Physical 0..315 grados
    0x65, 0x14,             //   Unit (grados)
    0x75, 0x04, 0x95, 0x01, //   1 campo de 4 bits
    0x81, 0x42,             //   Input (Data,Var,Abs,Null)
    0x65, 0x00,             //   Unit (ninguna)
    0x75, 0x04, 0x95, 0x01, 0x81, 0x03, // 4 bits de relleno
    0x09, 0x30, 0x09, 0x31, //   Usage X, Y   (stick izquierdo)
    0x09, 0x32, 0x09, 0x35, //   Usage Z, Rz  (stick derecho)
    0x16, 0x01, 0x80, 0x26, 0xFF, 0x7F, // Logical -32767..32767
    0x36, 0x01, 0x80, 0x46, 0xFF, 0x7F, // Physical igual
    0x75, 0x10, 0x95, 0x04, //   4 campos de 16 bits
    0x81, 0x02,             //   Input (Data,Var,Abs)
    0xC0,                   // End Collection
).map { it.toByte() }.toByteArray()

// Botón HID n.º (índice + 1) -> nuestra máscara. Es el orden que Linux y Android leen como A, B, X, Y, L1, R1, L2, R2…
private val HID_BUTTONS = intArrayOf(0x1000, 0x2000, 0, 0x4000, 0x8000, 0, 0x100, 0x200, LT, RT, 0x20, 0x10, 0x400, 0x40, 0x80, 0)

// Bits de cruceta (arriba 1, abajo 2, izquierda 4, derecha 8) -> posición del hat: 0 = arriba, en sentido horario; 8 = suelto.
private val HID_HAT = intArrayOf(8, 0, 4, 8, 6, 7, 5, 8, 2, 1, 3, 8, 8, 8, 8, 8)

/** Bluetooth: los 11 bytes del informe HID (sin el ID) que describe [HID_DESCRIPTOR]. */
fun encodeHid(buttons: Int, sticks: Long): ByteArray {
    var bits = 0
    for (i in HID_BUTTONS.indices) if (buttons and HID_BUTTONS[i] != 0) bits = bits or (1 shl i)
    val buf = ByteBuffer.allocate(11).order(ByteOrder.LITTLE_ENDIAN).putShort(bits.toShort()).put(HID_HAT[buttons and 0xF].toByte())
    for (axis in 0..3) {
        val v = (sticks shr (16 * axis)).toShort().toInt()
        buf.putShort((if (axis % 2 == 1) -v else v).toShort())   // en HID la Y crece hacia abajo; en XInput, hacia arriba
    }
    return buf.array()
}

// ---------- distribuciones ----------

const val XBOX = 0
const val PLAYSTATION = 1
const val SWITCH = 2

// Cómo se llama cada botón en cada familia de mandos, en el orden Xbox, PlayStation, Switch.
// Las máscaras son posiciones: 0x1000 es siempre el botón de abajo, que en Switch se llama B.
private val NAMES = mapOf(
    0x1000 to arrayOf("A", "✕", "B"), 0x2000 to arrayOf("B", "○", "A"),
    0x4000 to arrayOf("X", "□", "Y"), 0x8000 to arrayOf("Y", "△", "X"),
    0x100 to arrayOf("LB", "L1", "L"), 0x200 to arrayOf("RB", "R1", "R"),
    LT to arrayOf("LT", "L2", "ZL"), RT to arrayOf("RT", "R2", "ZR"),
    0x20 to arrayOf("Back", "Share", "−"), 0x10 to arrayOf("Start", "Options", "+"),
    0x400 to arrayOf("Home", "Home", "Home"), 0x40 to arrayOf("L3", "L3", "L3"), 0x80 to arrayOf("R3", "R3", "R3"),
)

fun buttonName(mask: Int, style: Int): String = NAMES[mask]?.get(style) ?: ""

/**
 * Disposición de fábrica de cada familia. Elegir una es solo apariencia: cada posición envía siempre lo mismo
 * (la de abajo, lo que un mando de Xbox llama A), se llame como se llame en pantalla.
 */
fun defaultLayout(style: Int): MutableList<El> = parseLayout(DEFAULT_LAYOUT).also { els ->
    if (style == PLAYSTATION) {   // cruceta arriba y los dos sticks abajo, simétricos
        els.first { it.kind == 'D' }.apply { x = 0.16f; y = 0.46f; r = 0.16f }
        els.first { it.kind == 'S' && it.mask == 0 }.apply { x = 0.33f; y = 0.76f; r = 0.16f }
    }
}

// ---------- combinaciones ----------

/** Un instante de una combinación grabada: `wait` ms después del anterior, el mando pasa a este estado. */
class Step(val wait: Int, val buttons: Int, val sticks: Long)

// Texto sin ';' ni saltos de línea, para que quepa en el campo `label` de un elemento guardado.
fun encodeSteps(steps: List<Step>) =
    steps.joinToString(",") { "${it.wait}.${it.buttons.toString(16)}.${java.lang.Long.toHexString(it.sticks)}" }

fun decodeSteps(s: String): List<Step> = s.split(',').filter { it.isNotEmpty() }.map {
    val f = it.split('.')
    Step(f[0].toInt(), f[1].toInt(16), java.lang.Long.parseUnsignedLong(f[2], 16))
}

// Distribución Xbox; las otras se derivan de esta en defaultLayout().
val DEFAULT_LAYOUT = """
    S;L;0;-1;0;0.16;0.46;0.19
    D;;0;-1;0;0.31;0.78;0.15
    S;R;1;-1;0;0.67;0.76;0.16
    B;A;4096;-1;0;0.86;0.63;0.075
    B;B;8192;-1;0;0.93;0.47;0.075
    B;X;16384;-1;0;0.79;0.47;0.075
    B;Y;32768;-1;0;0.86;0.31;0.075
    B;LT;65536;-1;0;0.075;0.115;0.075
    B;LB;256;-1;0;0.205;0.115;0.075
    B;RB;512;-1;0;0.795;0.115;0.075
    B;RT;131072;-1;0;0.925;0.115;0.075
    B;Back;32;-1;0;0.42;0.12;0.055
    B;Home;1024;-1;0;0.50;0.12;0.055
    B;Start;16;-1;0;0.58;0.12;0.055
    B;L3;64;-1;0;0.40;0.45;0.055
    B;R3;128;-1;0;0.60;0.45;0.055
    M;Menu;0;-1;0;0.50;0.80;0.055
""".trimIndent()
