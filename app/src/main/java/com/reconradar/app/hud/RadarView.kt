package com.reconradar.app.hud

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import com.reconradar.app.model.Anomaly
import com.reconradar.app.model.DetectedDevice
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Tactical radar display. Plots Wi-Fi APs and BLE devices on concentric range rings
 * with a sweeping scan line. Wi-Fi rendered in green, BLE in cyan, trackers in magenta,
 * anomalies in red, new devices in yellow.
 */
class RadarView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // -- Palette --
    private val cGreen      = 0xFF00FF41.toInt()
    private val cGreenDim   = 0x8000FF41.toInt()
    private val cGreenFaint = 0x2000FF41.toInt()
    private val cGrid       = 0x3000FF41.toInt()
    private val cCyan       = 0xFF00E5FF.toInt()
    private val cYellow     = 0xFFFFD700.toInt()
    private val cRed        = 0xFFFF3333.toInt()
    private val cMagenta    = 0xFFFF00FF.toInt()
    private val cGrey       = 0xFF444444.toInt()
    private val cBg         = 0xFF070707.toInt()

    // -- Paints --
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = cGrid; style = Paint.Style.STROKE; strokeWidth = 1f
    }
    private val outerRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = cGreenDim; style = Paint.Style.STROKE; strokeWidth = 1.5f
    }
    private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = cGrid; style = Paint.Style.STROKE; strokeWidth = 0.8f
    }
    private val diagPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x1500FF41.toInt(); style = Paint.Style.STROKE; strokeWidth = 0.6f
    }
    private val sweepFillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sweepLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = cGreen; strokeWidth = 2f; style = Paint.Style.STROKE
    }
    private val blipFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val blipRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.5f
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
    }
    private val ringLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x4500FF41.toInt(); typeface = Typeface.MONOSPACE
    }
    private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = cGreenDim; style = Paint.Style.STROKE; strokeWidth = 2f
    }
    private val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = cGreen; style = Paint.Style.FILL
    }
    private val centerCross = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = cGreen; style = Paint.Style.STROKE; strokeWidth = 1f
    }

    // -- State --
    private var sweepAngle = 0f
    private var devices = listOf<DetectedDevice>()
    private var flaggedIds = mapOf<String, Int>() // id -> base color for anomaly/new/tracker

    private var cx = 0f
    private var cy = 0f
    private var radius = 0f
    private val sweepPath = Path()
    private val sweepRect = RectF()

    private val animator = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 4000L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            sweepAngle = it.animatedValue as Float
            invalidate()
        }
    }

    fun startSweep() { if (!animator.isRunning) animator.start() }
    fun stopSweep() { animator.cancel(); invalidate() }

    fun updateDevices(detected: List<DetectedDevice>, anomalies: List<Anomaly>) {
        this.devices = detected

        // Build lookup: which IDs should be flagged and with what color
        val flags = mutableMapOf<String, Int>()
        for (a in anomalies) {
            val color = when (a.type) {
                Anomaly.Type.KNOWN_TRACKER -> cMagenta
                Anomaly.Type.RSSI_SPIKE, Anomaly.Type.OPEN_NETWORK, Anomaly.Type.ROGUE_AP -> cRed
                Anomaly.Type.NEW_DEVICE -> cYellow
                Anomaly.Type.DEVICE_GONE -> cGrey
                else -> flags[a.device.id] ?: cYellow
            }
            // Higher-priority color wins
            val existing = flags[a.device.id]
            if (existing == null || priorityOf(color) > priorityOf(existing)) {
                flags[a.device.id] = color
            }
        }
        this.flaggedIds = flags
    }

    private fun priorityOf(color: Int): Int = when (color) {
        cMagenta -> 4; cRed -> 3; cYellow -> 2; cGrey -> 1; else -> 0
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        cx = w / 2f; cy = h / 2f
        radius = min(w, h) / 2f * 0.88f
        labelPaint.textSize = radius * 0.05f
        ringLabel.textSize = radius * 0.042f
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(cBg)
        drawCorners(c)
        drawGrid(c)
        drawCrosshairs(c)
        drawRangeLabels(c)
        drawSweep(c)
        drawDeviceBlips(c)
        drawCenter(c)
    }

    // ── Grid ──────────────────────────────────────────────────
    private fun drawGrid(c: Canvas) {
        for (i in 1..4) c.drawCircle(cx, cy, radius * i / 4, gridPaint)
        c.drawCircle(cx, cy, radius, outerRingPaint)
    }

    private fun drawCrosshairs(c: Canvas) {
        c.drawLine(cx - radius, cy, cx + radius, cy, crossPaint)
        c.drawLine(cx, cy - radius, cx, cy + radius, crossPaint)
        val d = radius * 0.707f
        c.drawLine(cx - d, cy - d, cx + d, cy + d, diagPaint)
        c.drawLine(cx - d, cy + d, cx + d, cy - d, diagPaint)
    }

    private fun drawRangeLabels(c: Canvas) {
        val labels = arrayOf("-30", "-50", "-70", "-90")
        for (i in labels.indices) {
            val r = radius * (i + 1) / 4
            c.drawText(labels[i], cx + 4, cy - r + ringLabel.textSize, ringLabel)
        }
    }

    private fun drawCorners(c: Canvas) {
        val m = 10f; val len = 28f
        val l = m; val t = m; val r = width - m; val b = height - m
        c.drawLine(l, t, l + len, t, cornerPaint); c.drawLine(l, t, l, t + len, cornerPaint)
        c.drawLine(r, t, r - len, t, cornerPaint); c.drawLine(r, t, r, t + len, cornerPaint)
        c.drawLine(l, b, l + len, b, cornerPaint); c.drawLine(l, b, l, b - len, cornerPaint)
        c.drawLine(r, b, r - len, b, cornerPaint); c.drawLine(r, b, r, b - len, cornerPaint)
    }

    // ── Sweep ─────────────────────────────────────────────────
    private fun drawSweep(c: Canvas) {
        sweepRect.set(cx - radius, cy - radius, cx + radius, cy + radius)
        val rad = Math.toRadians((sweepAngle - 90).toDouble())
        val ex = cx + radius * cos(rad).toFloat()
        val ey = cy + radius * sin(rad).toFloat()

        sweepFillPaint.shader = LinearGradient(
            cx, cy, ex, ey,
            intArrayOf(cGreenFaint, Color.TRANSPARENT),
            floatArrayOf(0f, 1f), Shader.TileMode.CLAMP
        )
        sweepPath.reset()
        sweepPath.moveTo(cx, cy)
        sweepPath.arcTo(sweepRect, sweepAngle - 90 - 55, 55f)
        sweepPath.close()
        c.drawPath(sweepPath, sweepFillPaint)
        c.drawLine(cx, cy, ex, ey, sweepLinePaint)
    }

    // ── Blips ─────────────────────────────────────────────────
    private fun drawDeviceBlips(c: Canvas) {
        for (dev in devices) {
            val angRad = Math.toRadians((dev.radarAngle - 90).toDouble())
            val r = radius * dev.radarDistance
            val bx = cx + r * cos(angRad).toFloat()
            val by = cy + r * sin(angRad).toFloat()

            // Brightness from sweep proximity
            val diff = angleDiff(sweepAngle, dev.radarAngle)
            val brightness = if (diff < 30f) 1f - diff / 30f * 0.6f else 0.35f

            // Color by status
            val flagColor = flaggedIds[dev.id]
            val baseColor = flagColor
                ?: if (dev.type == DetectedDevice.DeviceType.BLE) cCyan else cGreen
            val blipSize = when {
                flagColor == cMagenta -> 8f  // tracker
                flagColor == cRed -> 7f      // anomaly
                flagColor == cYellow -> 6f   // new
                dev.type == DetectedDevice.DeviceType.BLE -> 4f
                else -> 5f
            }

            val alpha = (brightness * 255).toInt().coerceIn(50, 255)
            blipFill.color = withAlpha(baseColor, alpha)
            c.drawCircle(bx, by, blipSize, blipFill)

            // Pulse ring for flagged devices
            if (flagColor != null && flagColor != cGrey) {
                val phase = (System.currentTimeMillis() % 1200) / 1200f
                val pr = blipSize + phase * 14f
                blipRing.color = withAlpha(baseColor, ((1f - phase) * 160).toInt())
                c.drawCircle(bx, by, pr, blipRing)
            }

            // Label for nearby or flagged devices
            if (dev.rssi > -60 || flagColor != null) {
                val lbl = dev.name.let { if (it.length > 14) it.take(14) + ".." else it }
                labelPaint.color = withAlpha(baseColor, (brightness * 180).toInt().coerceIn(30, 180))
                c.drawText(lbl, bx + blipSize + 4, by - 3, labelPaint)
            }
        }
    }

    // ── Center ────────────────────────────────────────────────
    private fun drawCenter(c: Canvas) {
        c.drawCircle(cx, cy, 4f, centerPaint)
        val s = 10f
        c.drawLine(cx - s, cy, cx + s, cy, centerCross)
        c.drawLine(cx, cy - s, cx, cy + s, centerCross)
    }

    // ── Util ──────────────────────────────────────────────────
    private fun angleDiff(a: Float, b: Float): Float {
        val d = ((a - b) % 360 + 360) % 360
        return if (d > 180) 360 - d else d
    }

    private fun withAlpha(color: Int, alpha: Int) =
        (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)

    override fun onDetachedFromWindow() { super.onDetachedFromWindow(); animator.cancel() }
}
