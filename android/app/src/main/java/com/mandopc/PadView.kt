package com.mandopc

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

private const val PILLS = 0x100 or 0x200 or LT or RT or 0x10 or 0x20   // LB, RB, gatillos, Start y Back son alargados

/**
 * La cara del mando: dibuja cada control como una tecla en relieve que se hunde al pulsarla, convierte los
 * toques en estado para [Link] y, en modo edición, deja mover y redimensionar cada elemento.
 */
@SuppressLint("ViewConstructor")
class PadView(
    ctx: Context, private val link: Link,
    private val onMenu: () -> Unit, private val onSelect: (El?) -> Unit,
) : View(ctx) {
    private val prefs = ctx.getSharedPreferences("pad", 0)
    var style = prefs.getInt("style", XBOX)
        private set
    private var els = prefs.getString("layout", null)?.let { runCatching { parseLayout(it) }.getOrNull() }
        ?: defaultLayout(style)
    var selected: El? = null
        private set(v) { field = v; onSelect(v) }
    var editing = false
        set(v) { field = v; owner.clear(); if (!v) save(); selected = null; invalidate() }

    private var state = Link.OFF
    private var status = ctx.getString(R.string.status_off)
    private val owner = ConcurrentHashMap<Int, El>()            // dedo -> elemento que está tocando
    private val firing = ConcurrentHashMap.newKeySet<El>()      // turbos y combinaciones en marcha ahora mismo
    private val playing = AtomicBoolean()
    private val thumb = FloatArray(4)                           // posición de los sticks, solo para dibujar
    private var dpad = 0                                        // direcciones pulsadas, solo para dibujar
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val path = Path()
    private val arm = Path()
    private val dash = DashPathEffect(floatArrayOf(10f, 9f), 0f)
    private val legend = ctx.font(800)
    private val readout = ctx.font(600)
    private var shell: Shader? = null
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private var grabX = 0f
    private var grabY = 0f
    private var drag = false
    private var moved = false
    private val pinch = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean { resize(d.scaleFactor); return true }
    })

    init {
        contentDescription = ctx.getString(R.string.pad_description)
        // La versión 1.1 cambiaba de sitio A/B y X/Y en Switch; desde la 1.2 solo cambian los nombres.
        // Un diseño guardado con aquella versión se corrige una única vez, sin tocar lo demás.
        if (prefs.getInt("v", 1) < 2) {
            if (style == SWITCH) for ((m, n) in listOf(0x1000 to 0x2000, 0x4000 to 0x8000)) {
                val a = els.firstOrNull { it.kind == 'B' && it.turbo < 0 && it.mask == m } ?: continue
                val b = els.firstOrNull { it.kind == 'B' && it.turbo < 0 && it.mask == n } ?: continue
                val x = a.x; val y = a.y; a.x = b.x; a.y = b.y; b.x = x; b.y = y
            }
            prefs.edit().putInt("v", 2).apply()
            save()
        }
    }

    private fun save() = prefs.edit().putString("layout", els.joinToString("\n")).apply()
    fun add(el: El) { els.add(el); save(); editing = true; selected = el; invalidate() }
    fun remove(el: El) { els.remove(el); selected = null; save(); invalidate() }
    fun reset() { els = defaultLayout(style); selected = null; save(); invalidate() }

    /** Cambia a la distribución de otra familia de mandos. Recoloca los controles; las teclas turbo se conservan. */
    fun applyStyle(s: Int) {
        style = s
        prefs.edit().putInt("style", s).apply()
        els = (defaultLayout(s) + els.filter(::isExtra)).toMutableList()
        selected = null
        save()
        invalidate()
    }

    /** ¿El usuario movió o redimensionó algo respecto a la distribución de fábrica? */
    fun customized(): Boolean {
        fun shape(list: List<El>) = list.filterNot(::isExtra).map { "${it.kind}${it.mask} ${it.x} ${it.y} ${it.r}" }
        return shape(els) != shape(defaultLayout(style))
    }
    fun resize(factor: Float) { selected?.let { it.r = (it.r * factor).coerceIn(0.03f, 0.45f); invalidate() } }
    fun releaseAll() { owner.clear(); thumb.fill(0f); dpad = 0; link.update(0, 0); invalidate() }
    fun turbos() = els.filter { it.turbo >= 0 }
    fun combos() = els.filter { it.kind == 'C' }
    /** Teclas que añadió el usuario (turbo y combinaciones): se pueden quitar y sobreviven a un cambio de distribución. */
    fun isExtra(e: El) = e.turbo >= 0 || e.kind == 'C'

    // ---------- grabar una combinación ----------

    private var rec: MutableList<Step>? = null
    private var recAt = 0L
    val recording get() = rec != null

    fun record() { releaseAll(); rec = mutableListOf() }

    /** Termina de grabar. Con [save] y algo grabado, crea la tecla y entra en edición para colocarla. */
    fun stopRecording(save: Boolean) {
        val steps = rec ?: return
        rec = null
        releaseAll()
        if (save && steps.isNotEmpty())
            add(El('C', encodeSteps(steps), (combos().maxOfOrNull { it.mask } ?: 0) + 1, -1, 0, 0.5f, 0.62f, 0.08f))
    }

    private fun capture(buttons: Int, sticks: Long) {
        val steps = rec ?: return
        val last = steps.lastOrNull()
        val now = SystemClock.uptimeMillis()
        // el táctil da una posición de stick por muestra: se guarda como mucho una cada 16 ms, y siempre la de soltar
        val stickMoved = sticks != (last?.sticks ?: 0L) && (sticks == 0L || now - recAt >= 16)
        if (steps.size < 600 && (buttons != (last?.buttons ?: 0) || stickMoved)) {
            steps.add(Step(if (last == null) 0 else (now - recAt).toInt(), buttons, sticks))
            recAt = now
        }
    }

    private fun play(el: El) {
        if (!playing.compareAndSet(false, true)) return   // una combinación a la vez: se pisarían entre sí
        firing.add(el)
        thread {
            for (s in runCatching { decodeSteps(el.label) }.getOrDefault(emptyList())) {
                Thread.sleep(s.wait.toLong())
                link.combo(s.buttons, s.sticks)
            }
            Thread.sleep(40)        // si la grabación se cortó con algo pulsado, que el juego llegue a verlo
            link.combo(0, 0)
            firing.remove(el)
            playing.set(false)
            postInvalidate()
        }
    }

    fun setStatus(s: Int, text: String) { state = s; status = text; invalidate() }

    fun name(e: El): String = when (e.kind) {
        'S' -> context.getString(if (e.mask == 0) R.string.name_stick_l else R.string.name_stick_r)
        'D' -> context.getString(R.string.name_dpad)
        'M' -> context.getString(R.string.name_menu)
        else -> legendOf(e)
    }

    private fun legendOf(e: El) = if (e.kind == 'C') "C${e.mask}" else buttonName(e.mask, style) + when {
        e.turbo < 0 -> ""
        e.turbo == 0 -> "∞"
        else -> "×${e.turbo}"
    }

    // El color es parte del nombre del botón: los de Xbox, los de PlayStation, y ninguno en Switch.
    private fun tint(mask: Int) = when (style) {
        SWITCH -> C.BONE
        PLAYSTATION -> when (mask) { 0x1000 -> C.X; 0x2000 -> C.B; 0x4000 -> C.PINK; 0x8000 -> C.A; else -> C.BONE }
        else -> when (mask) { 0x1000 -> C.A; 0x2000 -> C.B; 0x4000 -> C.X; 0x8000 -> C.Y; else -> C.BONE }
    }

    private fun pill(e: El) = e.kind == 'B' && e.turbo < 0 && e.mask and PILLS != 0
    private fun dist(e: El, x: Float, y: Float) =
        hypot((x - e.x * width) / (if (pill(e)) 1.3f else 1f), y - e.y * height) / (e.r * height)
    private fun hit(x: Float, y: Float) = els.minByOrNull { dist(it, x, y) }?.takeIf { dist(it, x, y) <= 1.2f }

    // ---------- toques ----------

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (editing) return editTouch(e)
        val i = e.actionIndex
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                requestUnbufferedDispatch(e)   // los movimientos llegan sin esperar al vsync: menos latencia en los sticks
                val el = hit(e.getX(i), e.getY(i)) ?: return true
                if (el.kind == 'M') { if (!recording) { releaseAll(); onMenu() }; return true }
                owner[e.getPointerId(i)] = el
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                if (el.turbo >= 0) fire(el) else if (el.kind == 'C') play(el)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> owner.remove(e.getPointerId(i))
            MotionEvent.ACTION_CANCEL -> owner.clear()
        }
        var buttons = 0
        var sticks = 0L
        thumb.fill(0f)
        for ((id, el) in owner) {
            val p = e.findPointerIndex(id)
            if (p < 0) continue
            val dx = (e.getX(p) - el.x * width) / (el.r * height)
            val dy = (e.getY(p) - el.y * height) / (el.r * height)
            when (el.kind) {
                'B' -> if (el.turbo < 0) buttons = buttons or el.mask
                'D' -> {   // bits XInput: arriba 1, abajo 2, izquierda 4, derecha 8 (las diagonales activan dos)
                    if (dy < -0.3f) buttons = buttons or 1
                    if (dy > 0.3f) buttons = buttons or 2
                    if (dx < -0.3f) buttons = buttons or 4
                    if (dx > 0.3f) buttons = buttons or 8
                }
                'S' -> {
                    val n = max(0.75f, hypot(dx, dy))   // tope al 75 % del radio: no hace falta llegar al borde
                    val sx = dx / n
                    val sy = -dy / n                    // XInput: Y positiva hacia arriba
                    thumb[el.mask * 2] = sx
                    thumb[el.mask * 2 + 1] = sy
                    val xy = ((sx * 32767).toInt() and 0xFFFF).toLong() or (((sy * 32767).toInt() and 0xFFFF).toLong() shl 16)
                    sticks = sticks or (xy shl (el.mask * 32))
                }
            }
        }
        dpad = buttons and 0xF
        capture(buttons, sticks)
        link.update(buttons, sticks)
        invalidate()
        return true
    }

    /** Tecla turbo: pulsa y suelta el botón `turbo` veces (o mientras se mantenga, si turbo = 0). */
    private fun fire(el: El) {
        if (!firing.add(el)) return
        thread {
            val half = 500L / el.hz.coerceIn(1, 20)
            var n = 0
            while (if (el.turbo == 0) owner.containsValue(el) else n++ < el.turbo) {
                link.turbo(el.mask, true); postInvalidate(); Thread.sleep(half)
                link.turbo(el.mask, false); postInvalidate(); Thread.sleep(half)
            }
            firing.remove(el)
            postInvalidate()
        }
    }

    private fun editTouch(e: MotionEvent): Boolean {
        pinch.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val el = hit(e.x, e.y)
                drag = el != null
                moved = false
                if (el != null) { selected = el; grabX = e.x - el.x * width; grabY = e.y - el.y * height }
            }
            MotionEvent.ACTION_POINTER_DOWN -> drag = false   // segundo dedo = pellizco; que no salte al soltar uno
            MotionEvent.ACTION_MOVE -> selected?.takeIf { drag }?.let {
                val nx = e.x - grabX
                val ny = e.y - grabY
                if (moved || hypot(nx - it.x * width, ny - it.y * height) > slop) {
                    moved = true
                    it.x = (nx / width).coerceIn(0f, 1f)
                    it.y = (ny / height).coerceIn(0f, 1f)
                }
            }
        }
        invalidate()
        return true
    }

    // ---------- dibujo ----------

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        shell = RadialGradient(w / 2f, h * 0.42f, w * 0.72f, C.SHELL_HI, C.SHELL, Shader.TileMode.CLAMP)
    }

    override fun onDraw(c: Canvas) {
        paint.style = Paint.Style.FILL
        paint.shader = shell
        c.drawPaint(paint)
        paint.shader = null
        if (editing) {   // rejilla de puntos: señal de que ahora se colocan cosas, no se juega
            paint.color = 0x24FFFFFF
            val step = height / 10f
            var y = step / 2
            while (y < height) { var x = step / 2; while (x < width) { c.drawCircle(x, y, 2f, paint); x += step }; y += step }
        }
        for (e in els) {
            val cx = e.x * width
            val cy = e.y * height
            val r = e.r * height
            val depth = max(4f, r * 0.13f)   // cuánto sobresale la tecla y, por tanto, cuánto se hunde
            when (e.kind) {
                'S' -> stick(c, e, cx, cy, r, depth)
                'D' -> cross(c, cx, cy, r, depth)
                else -> button(c, e, cx, cy, r, depth)
            }
            if (e === selected) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 4f
                paint.color = C.BONE
                val pad = r * 0.16f + 6f
                val hw = (if (pill(e)) r * 1.3f else r) + pad
                val hh = (if (pill(e)) r * 0.72f else r) + pad
                c.drawRoundRect(cx - hw, cy - hh, cx + hw, cy + hh + depth, hh, hh, paint)
                paint.style = Paint.Style.FILL
            }
        }
        if (!editing) leds(c)
    }

    private fun button(c: Canvas, e: El, cx: Float, cy: Float, r: Float, depth: Float) {
        val tint = tint(e.mask)
        val down = !editing && (owner.containsValue(e) || (e in firing && (e.kind == 'C' || link.turboDown(e.mask))))
        val hw = if (pill(e)) r * 1.3f else r
        val hh = if (pill(e)) r * 0.72f else r
        val top = cy + if (down) depth else 0f
        paint.style = Paint.Style.FILL
        paint.color = C.SIDE
        c.drawRoundRect(cx - hw, cy - hh + depth, cx + hw, cy + hh + depth, hh, hh, paint)
        paint.color = if (down) tint else C.CAP
        c.drawRoundRect(cx - hw, top - hh, cx + hw, top + hh, hh, hh, paint)
        if (!down) {   // canto: del color del botón en A, B, X, Y; discreto en el resto
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = max(2.5f, r * 0.055f)
            paint.color = if (tint == C.BONE) C.EDGE else tint
            val inset = paint.strokeWidth / 2
            c.drawRoundRect(cx - hw + inset, top - hh + inset, cx + hw - inset, top + hh - inset, hh, hh, paint)
        }
        paint.color = if (down) C.SHELL else tint
        when {
            e.kind == 'M' -> {   // tres rayas
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = r * 0.13f
                paint.strokeCap = Paint.Cap.ROUND
                for (k in -1..1) c.drawLine(cx - r * 0.38f, top + k * r * 0.3f, cx + r * 0.38f, top + k * r * 0.3f, paint)
                paint.strokeCap = Paint.Cap.BUTT
            }
            e.mask == 0x400 && e.turbo < 0 -> {   // Home: un aro, como el botón guía de un mando
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = r * 0.14f
                c.drawCircle(cx, top, r * 0.4f, paint)
            }
            style == PLAYSTATION && e.turbo < 0 && e.mask and 0xF000 != 0 -> {   // ✕ ○ □ △ dibujados, no escritos
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = r * 0.13f
                paint.strokeCap = Paint.Cap.ROUND
                paint.strokeJoin = Paint.Join.ROUND
                val s = r * 0.4f
                when (e.mask) {
                    0x1000 -> { c.drawLine(cx - s, top - s, cx + s, top + s, paint); c.drawLine(cx + s, top - s, cx - s, top + s, paint) }
                    0x2000 -> c.drawCircle(cx, top, s * 1.08f, paint)
                    0x4000 -> c.drawRect(cx - s, top - s, cx + s, top + s, paint)
                    else -> {
                        path.rewind()
                        path.moveTo(cx, top - s * 1.1f); path.lineTo(cx + s * 1.15f, top + s * 0.85f); path.lineTo(cx - s * 1.15f, top + s * 0.85f)
                        path.close()
                        c.drawPath(path, paint)
                    }
                }
                paint.strokeCap = Paint.Cap.BUTT
                paint.strokeJoin = Paint.Join.MITER
            }
            else -> {
                paint.style = Paint.Style.FILL
                val s = legendOf(e)
                text(c, s, cx, top, if (pill(e)) min(hh * 1.05f, hw * 2.6f / s.length) else r * min(0.95f, 2.3f / s.length))
            }
        }
        if (isExtra(e)) {   // aro exterior: discontinuo si la tecla repite (turbo), continuo si reproduce una combinación
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = max(3f, r * 0.05f)
            if (e.turbo >= 0) paint.pathEffect = dash
            paint.color = tint
            c.drawCircle(cx, cy + depth / 2, r * 1.2f + depth / 2, paint)
            paint.pathEffect = null
        }
        paint.style = Paint.Style.FILL
    }

    private fun stick(c: Canvas, e: El, cx: Float, cy: Float, r: Float, depth: Float) {
        val held = !editing && owner.containsValue(e)
        paint.style = Paint.Style.FILL
        paint.color = C.WELL
        c.drawCircle(cx, cy, r, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = max(2.5f, r * 0.03f)
        paint.color = C.EDGE
        c.drawCircle(cx, cy, r, paint)
        val tx = cx + thumb[e.mask * 2] * r * 0.42f
        val ty = cy - thumb[e.mask * 2 + 1] * r * 0.42f
        val cap = r * 0.56f
        paint.style = Paint.Style.FILL
        paint.color = C.SIDE
        c.drawCircle(tx, ty + depth, cap, paint)
        paint.color = if (held) C.CAP_HI else C.CAP
        c.drawCircle(tx, ty, cap, paint)
        paint.style = Paint.Style.STROKE
        paint.color = if (held) C.BONE else C.EDGE
        c.drawCircle(tx, ty, cap - paint.strokeWidth / 2, paint)
        paint.color = 0x30FFFFFF   // anillo de agarre, como la goma de un stick
        c.drawCircle(tx, ty, cap * 0.58f, paint)
        paint.style = Paint.Style.FILL
    }

    private fun cross(c: Canvas, cx: Float, cy: Float, r: Float, depth: Float) {
        val lit = if (editing) 0 else dpad
        val top = cy + if (lit != 0) depth * 0.6f else 0f
        val a = r * 0.36f
        val k = r * 0.13f
        fun shape(y: Float) {
            path.rewind(); arm.rewind()
            path.addRoundRect(cx - r, y - a, cx + r, y + a, k, k, Path.Direction.CW)
            arm.addRoundRect(cx - a, y - r, cx + a, y + r, k, k, Path.Direction.CW)
            path.op(arm, Path.Op.UNION)
        }
        paint.style = Paint.Style.FILL
        paint.color = C.SIDE
        shape(cy + depth); c.drawPath(path, paint)
        paint.color = C.CAP
        shape(top); c.drawPath(path, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = max(2.5f, r * 0.03f)
        paint.color = C.EDGE
        c.drawPath(path, paint)
        paint.style = Paint.Style.FILL
        val t = r * 0.13f
        for ((bit, ux, uy) in ARROWS) {   // flechas grabadas; la dirección pulsada se enciende
            paint.color = if (lit and bit != 0) C.BONE else C.MUTED
            val ox = cx + ux * r * 0.68f
            val oy = top + uy * r * 0.68f
            path.rewind()
            path.moveTo(ox + ux * t, oy + uy * t)
            path.lineTo(ox - ux * t * 0.6f - uy * t, oy - uy * t * 0.6f + ux * t)
            path.lineTo(ox - ux * t * 0.6f + uy * t, oy - uy * t * 0.6f - ux * t)
            path.close()
            c.drawPath(path, paint)
        }
    }

    /** Cuatro LED como los de un mando: recorren al buscar, uno fijo al conectar, rojo si algo falló. */
    private fun leds(c: Canvas) {
        val y = height * 0.955f
        val led = height * 0.011f
        val gap = height * 0.036f
        paint.typeface = readout
        paint.textSize = height * 0.036f
        paint.textAlign = Paint.Align.LEFT
        val space = height * 0.03f
        var x = (width - (gap * 3 + led * 2 + space + paint.measureText(status))) / 2 + led
        val moving = state == Link.BUSY && ValueAnimator.areAnimatorsEnabled()
        val lit = when (state) {
            Link.ON, Link.ERROR -> 1
            Link.BUSY -> if (moving) 1 shl ((SystemClock.uptimeMillis() / 170) % 4).toInt() else 15
            else -> 0
        }
        for (i in 0..3) {
            val on = lit and (1 shl i) != 0
            val color = if (state == Link.ERROR) C.B else C.BONE
            if (on) { paint.color = color and 0x2CFFFFFF; c.drawCircle(x, y, led * 2f, paint) }
            paint.color = if (on) color else C.EDGE
            c.drawCircle(x, y, led, paint)
            x += gap
        }
        paint.color = if (state == Link.ON) C.BONE else C.MUTED
        c.drawText(status, x - gap + led + space, y - (paint.ascent() + paint.descent()) / 2, paint)
        paint.textAlign = Paint.Align.CENTER
        if (moving) postInvalidateOnAnimation()
    }

    private fun text(c: Canvas, s: String, x: Float, y: Float, size: Float) {
        paint.typeface = legend
        paint.textSize = size
        c.drawText(s, x, y - (paint.ascent() + paint.descent()) / 2, paint)
    }

    private companion object {
        val ARROWS = listOf(Triple(1, 0f, -1f), Triple(2, 0f, 1f), Triple(4, -1f, 0f), Triple(8, 1f, 0f))
    }
}
