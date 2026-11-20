package com.streamshell.car

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.view.Gravity
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView

/**
 * The car screen's views in the Dark Liquid Glass style ([CarTheme]): glass surfaces, the violet → cyan accent, icon
 * buttons with an active state, the round play button, texts, the wordmark, a progress bar and a chip. Plain Android
 * views — Android Auto gives a navigation app a drawing surface, not Compose.
 */
class CarViews(val context: Context) {
    private val c = context

    fun px(dp: Int): Int = (dp * c.resources.displayMetrics.density + HALF).toInt()

    private fun pxf(dp: Int): Float = dp * c.resources.displayMetrics.density

    /** A glass surface: [fill], rounded, with a thin light border. */
    fun glass(fill: Int = CarTheme.GLASS, radiusDp: Int = CarTheme.RADIUS_CARD, border: Boolean = true): GradientDrawable = GradientDrawable().apply {
        cornerRadius = pxf(radiusDp)
        setColor(fill)
        if (border) setStroke(px(1).coerceAtLeast(1), CarTheme.BORDER)
    }

    /** The violet → cyan accent ([shape]: GradientDrawable.RECTANGLE or OVAL). */
    fun accent(shape: Int = GradientDrawable.RECTANGLE, radiusDp: Int = CarTheme.RADIUS_CONTROL): GradientDrawable = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(CarTheme.VIOLET, CarTheme.CYAN)).apply {
        this.shape = shape
        if (shape == GradientDrawable.RECTANGLE) cornerRadius = pxf(radiusDp)
    }

    private fun ripple(content: Drawable?, radiusDp: Int, oval: Boolean = false): RippleDrawable {
        val mask = GradientDrawable().apply {
            if (oval) shape = GradientDrawable.OVAL else cornerRadius = pxf(radiusDp)
            setColor(android.graphics.Color.WHITE)
        }
        return RippleDrawable(ColorStateList.valueOf(CarTheme.PRESSED), content, mask)
    }

    fun iconButton(icon: Int, label: String, padDp: Int = CarTheme.RADIUS_CARD, onTap: () -> Unit): ImageButton = ImageButton(c).apply {
        setImageResource(icon)
        imageTintList = ColorStateList.valueOf(CarTheme.TEXT)
        scaleType = ImageView.ScaleType.FIT_CENTER
        val p = px(padDp)
        setPadding(p, p, p, p)
        contentDescription = label
        background = ripple(null, CarTheme.RADIUS_CONTROL)
        setOnClickListener { onTap() }
    }

    /** Active state of a navigation button: a soft accent fill and edge, the icon bright; inactive: muted icon. */
    fun setActive(b: ImageButton, active: Boolean, tint: Int = CarTheme.VIOLET) {
        val fill = if (active) {
            GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(withAlpha(tint, ACTIVE_ALPHA), withAlpha(CarTheme.CYAN, ACTIVE_ALPHA_2))).apply {
                cornerRadius = pxf(CarTheme.RADIUS_CONTROL)
                setStroke(px(1).coerceAtLeast(1), withAlpha(tint, ACTIVE_EDGE))
            }
        } else {
            null
        }
        b.background = ripple(fill, CarTheme.RADIUS_CONTROL)
        b.imageTintList = ColorStateList.valueOf(if (active) CarTheme.TEXT else CarTheme.TEXT_2)
        b.isSelected = active
    }

    /** The round play / pause button with the accent gradient. */
    fun playButton(onTap: () -> Unit): ImageButton = ImageButton(c).apply {
        imageTintList = ColorStateList.valueOf(android.graphics.Color.WHITE)
        scaleType = ImageView.ScaleType.FIT_CENTER
        val p = px(PLAY_PAD_DP)
        setPadding(p, p, p, p)
        background = ripple(accent(GradientDrawable.OVAL), 0, oval = true)
        elevation = pxf(ELEVATION_DP)
        setOnClickListener { onTap() }
    }

    fun text(sizeSp: Float, color: Int, bold: Boolean = false, lines: Int = 1): TextView = TextView(c).apply {
        textSize = sizeSp
        setTextColor(color)
        maxLines = lines
        ellipsize = TextUtils.TruncateAt.END
        includeFontPadding = false
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    /** "StreamShell" with the accent gradient across the text. */
    fun wordmark(): TextView = text(TITLE_MARK_SP, CarTheme.TEXT, bold = true).apply {
        text = "StreamShell"
        letterSpacing = MARK_SPACING
        addOnLayoutChangeListener { v, left, _, right, _, _, _, _, _ ->
            (v as TextView).paint.shader = LinearGradient(0f, 0f, (right - left).toFloat(), 0f, CarTheme.VIOLET, CarTheme.CYAN, Shader.TileMode.CLAMP)
        }
    }

    fun progress(): ProgressBar = ProgressBar(c, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = CarDisplay.PROGRESS_MAX
        val track = GradientDrawable().apply {
            cornerRadius = pxf(PROGRESS_DP)
            setColor(CarTheme.ELEVATED)
        }
        val layers = LayerDrawable(arrayOf(track, ClipDrawable(accent(radiusDp = PROGRESS_DP), Gravity.START, ClipDrawable.HORIZONTAL)))
        layers.setId(0, android.R.id.background)
        layers.setId(1, android.R.id.progress)
        progressDrawable = layers
        minimumHeight = px(PROGRESS_DP)
        isClickable = false
    }

    fun chip(label: String, color: Int): TextView = text(CarTheme.LABEL_SP, color, bold = true).apply {
        text = label
        val h = px(CHIP_PAD_H_DP)
        val v = px(CHIP_PAD_V_DP)
        setPadding(h, v, h, v)
        background = glass(CarTheme.ELEVATED, CHIP_RADIUS_DP)
    }

    companion object {
        private const val HALF = 0.5f
        private const val ACTIVE_ALPHA = 0x66
        private const val ACTIVE_ALPHA_2 = 0x33
        private const val ACTIVE_EDGE = 0x99
        private const val PLAY_PAD_DP = 20
        private const val ELEVATION_DP = 6
        private const val TITLE_MARK_SP = 20f
        private const val MARK_SPACING = 0.02f
        private const val PROGRESS_DP = 4
        private const val CHIP_PAD_H_DP = 10
        private const val CHIP_PAD_V_DP = 5
        private const val CHIP_RADIUS_DP = 12

        fun withAlpha(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha shl 24)
    }
}
