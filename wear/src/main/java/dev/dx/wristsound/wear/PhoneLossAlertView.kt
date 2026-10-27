package dev.dx.wristsound.wear

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.*
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Native, scrollable alert: the photograph is decorative, text and dismiss remain real views. */
internal class PhoneLossAlertView(context: Context, photograph: Bitmap?, onDismiss: () -> Unit) : ScrollView(context) {
    private val density = resources.displayMetrics.density
    private val panel = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
    }
    private val condensed = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    private val heading = TextView(context).apply {
        setText(R.string.phone_left_title)
        setTextColor(Color.WHITE)
        typeface = condensed
        gravity = Gravity.CENTER
        setSingleLine()
        setAutoSizeTextTypeUniformWithConfiguration(12, 16, 1, TypedValue.COMPLEX_UNIT_SP)
        setPadding(dp(8), dp(3), dp(8), dp(3))
        background = ribbon(intArrayOf(Color.rgb(6, 9, 15), Color.rgb(20, 24, 32)), 10f)
        rotation = -3f
    }
    private val subject = TextView(context).apply {
        setText(R.string.phone_left_subject)
        setTextColor(Color.rgb(255, 220, 53))
        typeface = condensed
        gravity = Gravity.CENTER
        setSingleLine()
        setAutoSizeTextTypeUniformWithConfiguration(22, 30, 1, TypedValue.COMPLEX_UNIT_SP)
        setPadding(dp(8), dp(1), dp(8), dp(2))
        setShadowLayer(dp(2).toFloat(), 0f, dp(1).toFloat(), Color.rgb(53, 0, 0))
        background = ribbon(intArrayOf(Color.rgb(255, 49, 48), Color.rgb(189, 8, 27)), 11f)
        rotation = -3f
    }
    private val warning = ImageView(context).apply {
        setImageResource(R.drawable.phone_left_warning)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val subtitle = TextView(context).apply {
        setText(R.string.phone_left_subtitle)
        textSize = 11f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        setShadowLayer(dp(2).toFloat(), 0f, dp(1).toFloat(), Color.BLACK)
    }
    private val dismiss = Button(context).apply {
        setText(R.string.phone_left_dismiss)
        textSize = 14f
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
        isAllCaps = false
        gravity = Gravity.CENTER
        minHeight = dp(48)
        minimumHeight = dp(48)
        minWidth = 0
        minimumWidth = 0
        setPadding(dp(12), dp(2), dp(12), dp(2))
        setTextColor(Color.rgb(26, 22, 8))
        backgroundTintList = null
        val fill = ribbon(intArrayOf(Color.rgb(255, 226, 116), Color.rgb(255, 193, 45)), 28f)
            .apply { setStroke(dp(1), Color.rgb(255, 239, 167)) }
        background = RippleDrawable(ColorStateList.valueOf(Color.argb(65, 255, 255, 255)), fill, null)
        stateListAnimator = null
        elevation = dp(2).toFloat()
        setOnClickListener { onDismiss() }
    }

    init {
        isFillViewport = true
        isVerticalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        background = PhoneLossBackdrop(photograph, resources.configuration.isScreenRound, density)
        accessibilityPaneTitle = context.getString(R.string.phone_left_accessibility_title)
        panel.addView(heading)
        panel.addView(subject)
        panel.addView(warning)
        panel.addView(subtitle)
        panel.addView(dismiss)
        addView(panel, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        if (width <= 0 || height <= 0) return
        val side = min(width, height)
        val scale = (side / density / 240f).coerceIn(0.65f, 1.4f)
        fun gap(value: Int) = dp(value * scale)
        fun place(child: View, widthFraction: Float, childHeight: Int, topGap: Int) {
            child.layoutParams = LinearLayout.LayoutParams((side * widthFraction).roundToInt(), childHeight)
                .apply { topMargin = gap(topGap) }
        }
        val wrap = LinearLayout.LayoutParams.WRAP_CONTENT
        // Chord-safe widths and insets keep the title and action inside a round display.
        panel.setPadding(0, (side * 0.11f).roundToInt(), 0, (side * 0.11f).roundToInt())
        place(heading, 0.64f, gap(26), 0)
        place(subject, 0.82f, gap(38), 4)
        place(warning, 0.18f, gap(36), 6)
        place(subtitle, 0.76f, wrap, 4)
        place(dismiss, 0.60f, wrap, 8)
    }

    private fun dp(value: Int) = dp(value.toFloat())
    private fun dp(value: Float) = (value * density).roundToInt()
    private fun ribbon(colors: IntArray, radius: Float) = GradientDrawable(GradientDrawable.Orientation.TL_BR, colors)
        .apply { cornerRadius = dp(radius).toFloat() }
}

/** One cached bitmap shader, two scrims and a static rim; no animation or per-frame decoding. */
private class PhoneLossBackdrop(photograph: Bitmap?, private val round: Boolean, private val density: Float) : Drawable() {
    private val picture = photograph
    private val photo = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        if (photograph != null) shader = BitmapShader(photograph, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
    }
    private val scrim = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 40, 48)
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
    }
    private val crop = Matrix()
    private val shape = Path()
    private val area = RectF()
    private var shade: Shader? = null

    override fun onBoundsChange(bounds: Rect) {
        area.set(bounds)
        val inset = rim.strokeWidth / 2f
        area.inset(inset, inset)
        shape.reset()
        if (round) shape.addOval(area, Path.Direction.CW)
        else shape.addRoundRect(area, 24f * density, 24f * density, Path.Direction.CW)
        picture?.let { bitmap ->
            val scale = max(bounds.width().toFloat() / bitmap.width, bounds.height().toFloat() / bitmap.height)
            crop.setScale(scale, scale)
            crop.postTranslate(bounds.left + (bounds.width() - bitmap.width * scale) / 2f,
                bounds.top + (bounds.height() - bitmap.height * scale) / 2f)
            photo.shader?.setLocalMatrix(crop)
        }
        shade = LinearGradient(0f, bounds.top.toFloat(), 0f, bounds.bottom.toFloat(),
            intArrayOf(Color.argb(135, 4, 9, 18), Color.argb(20, 4, 9, 18), Color.argb(40, 4, 9, 18), Color.argb(220, 4, 9, 18)),
            floatArrayOf(0f, 0.43f, 0.64f, 1f), Shader.TileMode.CLAMP)
    }

    override fun draw(canvas: Canvas) {
        canvas.drawColor(Color.rgb(5, 9, 16))
        val saved = canvas.save()
        canvas.clipPath(shape)
        if (picture != null) canvas.drawRect(bounds, photo)
        scrim.shader = shade
        canvas.drawRect(bounds, scrim)
        canvas.restoreToCount(saved)
        canvas.drawPath(shape, rim)
    }
    override fun setAlpha(alpha: Int) { photo.alpha = alpha; scrim.alpha = alpha; rim.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(filter: ColorFilter?) { photo.colorFilter = filter; invalidateSelf() }
    @Deprecated("Required by Drawable") override fun getOpacity() = PixelFormat.OPAQUE
}
