package dji.sampleV5.aircraft.virtualstick

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.min
import kotlin.math.sqrt

class OnScreenJoystick @JvmOverloads constructor(
    context: Context?,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    // Listener
    private var joystickListener: OnScreenJoystickListener? = null

    // Labels
    private var labelUp: String? = null
    private var labelDown: String? = null
    private var labelLeft: String? = null
    private var labelRight: String? = null

    // Center & radius
    private var cx = 0f
    private var cy = 0f
    private var radius = 0f
    private var knobRadius = 0f

    // Normalized position [-1, 1]
    private var normX = 0f   // +right
    private var normY = 0f   // +up

    // Knob position in pixels
    private var knobX = 0f
    private var knobY = 0f

    // Which quadrant is highlighted
    private var highlightUp = false
    private var highlightDown = false
    private var highlightLeft = false
    private var highlightRight = false

    // Paints
    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#20242C")
        style = Paint.Style.FILL
    }

    private val quadrantPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3A4250")
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4DA3FF")
        style = Paint.Style.FILL
    }

    private val knobStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#55FFFFFF")
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }


    private val arcRect = RectF()

    // ---- PUBLIC API ----

    fun setJoystickListener(listener: OnScreenJoystickListener?) {
        joystickListener = listener
    }

    fun setLabels(
        up: String? = null,
        down: String? = null,
        left: String? = null,
        right: String? = null
    ) {
        labelUp = up
        labelDown = down
        labelLeft = left
        labelRight = right
        invalidate()
    }

    /**
     * Called from TouchFlyActivity when remote input moves the stick.
     * normX, normY in [-1, 1].
     */
    fun setPositionFromRemote(normX: Float, normY: Float) {
        this.normX = normX.coerceIn(-1f, 1f)
        this.normY = normY.coerceIn(-1f, 1f)
        updateKnobFromNormalized()
        invalidate()
    }

    /**
     * Called from TouchFlyActivity to show which wedge is active when keys are pressed.
     */
    fun setHighlight(
        up: Boolean = highlightUp,
        down: Boolean = highlightDown,
        left: Boolean = highlightLeft,
        right: Boolean = highlightRight
    ) {
        highlightUp = up
        highlightDown = down
        highlightLeft = left
        highlightRight = right
        invalidate()
    }

    // ---- GEOMETRY / DRAWING ----

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)

        val size = min(w, h).toFloat()
        cx = w / 2f
        cy = h / 2f
        radius = size * 0.45f
        knobRadius = radius * 0.25f

        // Scale labels with the joystick size
        textPaint.textSize = radius * 0.18f

        arcRect.set(cx - radius, cy - radius, cx + radius, cy + radius)
        updateKnobFromNormalized()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Background circle + ring
        canvas.drawCircle(cx, cy, radius, basePaint)
        canvas.drawCircle(cx, cy, radius, ringPaint)

        fun colorFor(highlight: Boolean) =
            if (highlight) Color.parseColor("#4DA3FF") else Color.parseColor("#303845")

        // wedges of joystick
        quadrantPaint.color = colorFor(highlightUp)
        canvas.drawArc(arcRect, -135f, 90f, true, quadrantPaint)

        quadrantPaint.color = colorFor(highlightRight)
        canvas.drawArc(arcRect, -45f, 90f, true, quadrantPaint)

        quadrantPaint.color = colorFor(highlightDown)
        canvas.drawArc(arcRect, 45f, 90f, true, quadrantPaint)

        quadrantPaint.color = colorFor(highlightLeft)
        canvas.drawArc(arcRect, 135f, 90f, true, quadrantPaint)


        // Diagonal wedge edges
        fun drawDivider(angleDeg: Float) {
            val rad = Math.toRadians(angleDeg.toDouble())
            val ex = cx + (radius * Math.cos(rad)).toFloat()
            val ey = cy + (radius * Math.sin(rad)).toFloat()
            canvas.drawLine(cx, cy, ex, ey, dividerPaint)
        }
        drawDivider(-45f)
        drawDivider(45f)
        drawDivider(135f)
        drawDivider(-135f)

        // Labels / arrows
        val labelOffset = radius * 0.64f

        if (isArrowMode()) {
            drawArrow(canvas, cx, cy - labelOffset, 0f)  // up
            drawArrow(canvas, cx + labelOffset, cy, 90f)  // right
            drawArrow(canvas, cx, cy + labelOffset, 180f)  // down
            drawArrow(canvas, cx - labelOffset, cy, 270f)  // left
        } else {
            // Normal text labels (SPACE, SHIFT, A, D, etc.)
            labelUp?.let {
                canvas.drawText(it, cx, cy - labelOffset, textPaint)
            }
            labelDown?.let {
                canvas.drawText(it, cx, cy + labelOffset + textPaint.textSize / 2f, textPaint)
            }
            labelLeft?.let {
                canvas.drawText(it, cx - labelOffset, cy + textPaint.textSize / 2f, textPaint)
            }
            labelRight?.let {
                canvas.drawText(it, cx + labelOffset, cy + textPaint.textSize / 2f, textPaint)
            }
        }


        // Knob
        canvas.drawCircle(knobX, knobY, knobRadius, knobPaint)
        canvas.drawCircle(knobX, knobY, knobRadius, knobStrokePaint)
    }

    // ---- TOUCH HANDLING ----

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_MOVE -> {
                val x = event.x - cx
                val y = event.y - cy

                val dist = sqrt(x * x + y * y)
                val max = radius * 0.8f

                val (clampedX, clampedY) = if (dist > max && dist > 0f) {
                    val ratio = max / dist
                    x * ratio to y * ratio
                } else {
                    x to y
                }

                // Convert to normalized [-1, 1]
                normX = (clampedX / max).coerceIn(-1f, 1f)
                normY = (-clampedY / max).coerceIn(-1f, 1f)

                knobX = cx + clampedX
                knobY = cy + clampedY

                joystickListener?.onTouch(this, normX, normY)
                invalidate()
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                // Auto-center
                normX = 0f
                normY = 0f
                updateKnobFromNormalized()
                joystickListener?.onTouch(this, 0f, 0f)
                invalidate()
            }
        }
        return true
    }

    private fun updateKnobFromNormalized() {
        val max = radius * 0.8f
        val px = normX * max
        val py = -normY * max
        knobX = cx + px
        knobY = cy + py
    }

    private fun isArrowMode(): Boolean {
        // Right-stick case where you set arrow labels
        return labelUp == "↑" && labelDown == "↓" && labelLeft == "←" && labelRight == "→"
    }
    private fun drawArrow(canvas: Canvas, cx: Float, cy: Float, angleDeg: Float) {
        val size = radius * 0.16f  //arrow size
        val path = Path().apply {
            moveTo(0f, -size)  // tip
            lineTo(-size * 0.7f, size)  // left base
            lineTo(size * 0.7f, size)  // right base
            close()
        }

        canvas.save()
        canvas.translate(cx, cy)
        canvas.rotate(angleDeg)
        canvas.drawPath(path, arrowPaint)
        canvas.restore()
    }
}
