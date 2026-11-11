package com.mandopc

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.widget.ImageView
import android.widget.TextView

/** Paleta: tinta para la carcasa, hueso para las leyendas, y color solo donde significa algo (A, B, X, Y). */
object C {
    const val SHELL = 0xFF131A2C.toInt()
    const val SHELL_HI = 0xFF1D2846.toInt()
    const val SHEET = 0xFF182036.toInt()
    const val CAP = 0xFF2B3757.toInt()
    const val CAP_HI = 0xFF3A4870.toInt()
    const val SIDE = 0xFF090D18.toInt()
    const val WELL = 0xFF0D1322.toInt()
    const val EDGE = 0xFF42507A.toInt()
    const val LINE = 0xFF283251.toInt()
    const val BONE = 0xFFEEF1F7.toInt()
    const val MUTED = 0xFF96A1BD.toInt()
    const val A = 0xFF52D273.toInt()
    const val B = 0xFFF45D62.toInt()
    const val X = 0xFF5AA8FF.toInt()
    const val Y = 0xFFF7CA4A.toInt()
    const val PINK = 0xFFF28DC4.toInt()   // el cuadrado de PlayStation
}

private val fonts = HashMap<Int, Typeface>()

/** Nunito con el peso pedido (la fuente es variable: un solo archivo para todos los pesos). */
fun Context.font(weight: Int): Typeface = fonts.getOrPut(weight) {
    Typeface.Builder(assets, "nunito.ttf").setFontVariationSettings("'wght' $weight").build() ?: Typeface.DEFAULT_BOLD
}

fun Context.dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()

fun rounded(fill: Int, radius: Float, stroke: Int = 0, strokeWidth: Int = 0) = GradientDrawable().apply {
    setColor(fill)
    cornerRadius = radius
    if (strokeWidth > 0) setStroke(strokeWidth, stroke)
}

/** Fondo con respuesta al toque; `dark` para superficies claras. */
fun pressable(content: Drawable?, radius: Float, dark: Boolean = false): Drawable =
    RippleDrawable(ColorStateList.valueOf(if (dark) 0x33000000 else 0x33FFFFFF), content, rounded(Color.WHITE, radius))

fun Context.pillButton(label: CharSequence, primary: Boolean, onClick: () -> Unit) = TextView(this).apply {
    text = label
    textSize = 15f
    typeface = font(800)
    gravity = Gravity.CENTER
    setTextColor(if (primary) C.SHELL else C.BONE)
    minHeight = dp(48)
    minWidth = dp(48)
    setPadding(dp(22), 0, dp(22), 0)
    val r = dp(24).toFloat()
    background = pressable(if (primary) rounded(C.BONE, r) else rounded(0, r, C.EDGE, dp(1) + 1), r, dark = primary)
    isClickable = true
    isFocusable = true
    setOnClickListener { onClick() }
}

fun Context.iconButton(kind: Char, description: CharSequence, onClick: () -> Unit) = ImageView(this).apply {
    setImageDrawable(Glyph(kind, C.BONE))
    contentDescription = description
    val p = dp(12)
    setPadding(p, p, p, p)
    background = pressable(null, dp(24).toFloat())
    isClickable = true
    isFocusable = true
    setOnClickListener { onClick() }
}

/**
 * Iconos de línea dibujados sobre una rejilla de 24: b Bluetooth, w WiFi, e editar, t turbo, c combinación, r restablecer,
 * < volver, x cerrar, + y -.
 */
class Glyph(private val kind: Char, color: Int) : Drawable() {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color; strokeWidth = 2f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()

    private fun line(vararg v: Float) {
        path.moveTo(v[0], v[1])
        for (i in 2 until v.size step 2) path.lineTo(v[i], v[i + 1])
    }

    override fun draw(c: Canvas) {
        val s = bounds.width() / 24f
        c.save()
        c.translate(bounds.left.toFloat(), bounds.top.toFloat())
        c.scale(s, s)
        path.rewind()
        when (kind) {
            'b' -> line(7f, 7.5f, 17f, 16.5f, 12f, 21f, 12f, 3f, 17f, 7.5f, 7f, 16.5f)
            'w' -> {
                for (r in floatArrayOf(4.5f, 9f, 13.5f)) path.addArc(12 - r, 19 - r, 12 + r, 19 + r, 225f, 90f)
                p.style = Paint.Style.FILL
                c.drawCircle(12f, 19f, 1.4f, p)
            }
            'e' -> { line(5f, 19f, 5.8f, 15.2f, 15.5f, 5.5f, 18.5f, 8.5f, 8.8f, 18.2f, 5f, 19f); line(13.5f, 7.5f, 16.5f, 10.5f) }
            't' -> line(13f, 3f, 5f, 13.5f, 11f, 13.5f, 10f, 21f, 19f, 10f, 13f, 10f, 13f, 3f)
            'c' -> {   // tres pasos encadenados
                for (x in floatArrayOf(4.5f, 12f, 19.5f)) path.addCircle(x, 12f, 2.3f, Path.Direction.CW)
                line(6.8f, 12f, 9.7f, 12f); line(14.3f, 12f, 17.2f, 12f)
            }
            'r' -> { path.addArc(4.5f, 5f, 19.5f, 20f, -60f, 300f); line(4.7f, 5.4f, 8.25f, 6f, 7f, 9.4f) }
            '<' -> line(14.5f, 5f, 8f, 12f, 14.5f, 19f)
            'x' -> { line(6f, 6f, 18f, 18f); line(18f, 6f, 6f, 18f) }
            '+' -> { line(12f, 5f, 12f, 19f); line(5f, 12f, 19f, 12f) }
            '-' -> line(5f, 12f, 19f, 12f)
        }
        p.style = Paint.Style.STROKE
        c.drawPath(path, p)
        c.restore()
    }

    override fun setAlpha(alpha: Int) { p.alpha = alpha }
    override fun setColorFilter(filter: ColorFilter?) { p.colorFilter = filter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
