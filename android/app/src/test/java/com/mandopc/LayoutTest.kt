package com.mandopc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutTest {
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    @Test fun layoutSurvivesSaveAndLoad() {
        val saved = parseLayout(DEFAULT_LAYOUT).joinToString("\n")
        assertEquals(17, parseLayout(saved).size)
        assertEquals(saved, parseLayout(saved).joinToString("\n"))
    }

    @Test fun everyStyleKeepsAllControlsAndOnlyMovesThem() {
        fun el(style: Int, kind: Char, mask: Int) = defaultLayout(style).first { it.kind == kind && it.mask == mask }
        for (style in listOf(XBOX, PLAYSTATION, SWITCH)) {
            assertEquals(17, defaultLayout(style).size)
            assertEquals(defaultLayout(XBOX).map { "${it.kind}${it.mask}" }, defaultLayout(style).map { "${it.kind}${it.mask}" })
        }
        assertTrue(el(XBOX, 'S', 0).y < el(XBOX, 'D', 0).y)                 // Xbox: stick izquierdo arriba, cruceta abajo
        assertTrue(el(PLAYSTATION, 'S', 0).y > el(PLAYSTATION, 'D', 0).y)   // PlayStation: al revés
        // Switch es solo apariencia: mismos sitios que Xbox, y el botón de abajo (el que envía A) se llama B
        assertEquals(defaultLayout(XBOX).joinToString("\n"), defaultLayout(SWITCH).joinToString("\n"))
        assertEquals(listOf("B", "A", "Y", "X"), listOf(0x1000, 0x2000, 0x4000, 0x8000).map { buttonName(it, SWITCH) })
        assertEquals("ZR", buttonName(RT, SWITCH))
        assertEquals("L1", buttonName(0x100, PLAYSTATION))
    }

    @Test fun recordedCombinationSurvivesSaveAndLoad() {
        val steps = listOf(Step(0, 0x1000, 0), Step(120, 0x1000 or LT, 0x7FFFL or (0x8001L shl 16)), Step(35, 0, -1L))
        val text = encodeSteps(steps)
        assertTrue(text.none { it == ';' || it == '\n' })   // debe caber en una línea del diseño guardado
        assertEquals(text, encodeSteps(decodeSteps(text)))
        assertEquals(listOf(0, 120, 35), decodeSteps(text).map { it.wait })
        assertEquals(-1L, decodeSteps(text).last().sticks)
        val saved = El('C', text, 1, -1, 0, 0.5f, 0.6f, 0.08f).toString()
        assertEquals(text, parseLayout(saved).single().label)
    }

    @Test fun wifiPacketMatchesServerFormat() {   // mismo vector que pc/prueba.py: A + LT, stick izq. (32767, -32767), stick der. Y = 1
        val sticks = 0x7FFFL or (0x8001L shl 16) or (1L shl 48)
        assertEquals("010000000010ff00ff7f018000000100", hex(encode(1, 0x1000 or LT, sticks)))
    }

    @Test fun hidReportIsExactlyWhatTheDescriptorDeclares() {
        // suma los bits de cada campo Input del descriptor (elementos cortos de HID: prefijo + 0, 1, 2 o 4 bytes de dato)
        var bits = 0; var size = 0; var count = 0; var i = 0
        while (i < HID_DESCRIPTOR.size) {
            val prefix = HID_DESCRIPTOR[i].toInt() and 0xFF
            val len = intArrayOf(0, 1, 2, 4)[prefix and 3]
            val value = if (len > 0) HID_DESCRIPTOR[i + 1].toInt() and 0xFF else 0
            when (prefix and 0xFC) { 0x74 -> size = value; 0x94 -> count = value; 0x80 -> bits += size * count }
            i += 1 + len
        }
        assertEquals(i, HID_DESCRIPTOR.size)   // el descriptor se recorre sin desbordarse: ningún elemento truncado
        assertEquals(bits, encodeHid(0, 0).size * 8)
    }

    @Test fun hidReportMapsButtonsHatAndAxes() {
        // A + LT + cruceta arriba-derecha, stick izquierdo a tope derecha y arriba
        val sticks = 0x7FFFL or (0x7FFFL shl 16)
        //            botones 1 y 9 | hat 1 (NE) | X = 32767 | Y = -32767 (arriba en HID) | Z, Rz = 0
        assertEquals("0101" + "01" + "ff7f" + "0180" + "0000" + "0000", hex(encodeHid(0x1000 or LT or 1 or 8, sticks)))
        assertEquals("0000" + "08" + "0000".repeat(4), hex(encodeHid(0, 0)))   // todo suelto: hat fuera de rango
    }
}
