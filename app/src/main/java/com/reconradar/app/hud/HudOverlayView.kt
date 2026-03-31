package com.reconradar.app.hud

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import com.reconradar.app.model.Anomaly

/**
 * Full-screen overlay that draws HUD chrome on top of the radar:
 * CRT scan-lines, status bars, anomaly ticker, and band counters.
 */
class HudOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val cGreen    = 0xFF00FF41.toInt()
    private val cGreenDim = 0x7000FF41.toInt()
    private val cCyan     = 0xFF00E5FF.toInt()
    private val cYellow   = 0xFFFFD700.toInt()
    private val cRed      = 0xFFFF3333.toInt()
    private val cMagenta  = 0xFFFF00FF.toInt()

    private val mono = Typeface.MONOSPACE
    private val textMain = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = cGreen; textSize = 28f; typeface = mono
    }
    private val textDim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = cGreenDim; textSize = 22f; typeface = mono
    }
    private val textAlert = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = cRed; textSize = 24f; typeface = mono
    }
    private val scanLine = Paint().apply {
        color = 0x0700FF41.toInt(); strokeWidth = 1f
    }

    var wifiCount = 0
    var bleCount = 0
    var anomalyCount = 0
    var trackerCount = 0
    var band24 = 0
    var band5 = 0
    var baselineSize = 0
    var isScanning = false
    var xrealConnected = false
    var recentAnomalies = listOf<Anomaly>()

    fun refresh() = invalidate()

    override fun onDraw(c: Canvas) {
        drawScanLines(c)
        drawTopBar(c)
        drawBottomBar(c)
        drawAnomalyTicker(c)
    }

    private fun drawScanLines(c: Canvas) {
        var y = 0f
        while (y < height) { c.drawLine(0f, y, width.toFloat(), y, scanLine); y += 4f }
    }

    private fun drawTopBar(c: Canvas) {
        val y1 = textMain.textSize + 14f

        // Status label
        val statusColor = if (isScanning) cGreen else cYellow
        textMain.color = statusColor
        c.drawText(if (isScanning) "[ SCANNING ]" else "[ PAUSED ]", 18f, y1, textMain)

        // Signal totals (right-aligned)
        textMain.color = cGreen
        val wifiStr = "W:$wifiCount"
        textMain.color = cGreen
        val bleStr = "B:$bleCount"
        val totalStr = "$wifiStr $bleStr"
        c.drawText(totalStr, width - textMain.measureText(totalStr) - 18f, y1, textMain)

        // Second row
        val y2 = y1 + textDim.textSize + 6f

        // XReal status
        textDim.color = if (xrealConnected) cGreen else cGreenDim
        c.drawText(if (xrealConnected) "XREAL:LINKED" else "XREAL:---", 18f, y2, textDim)

        // Anomaly + tracker counts
        if (anomalyCount > 0 || trackerCount > 0) {
            val parts = mutableListOf<String>()
            if (trackerCount > 0) parts.add("TRK:$trackerCount")
            if (anomalyCount > 0) parts.add("ANOM:$anomalyCount")
            val anomStr = parts.joinToString(" ")
            textAlert.color = if (trackerCount > 0) cMagenta else cRed
            c.drawText(anomStr, width - textAlert.measureText(anomStr) - 18f, y2, textAlert)
        }
    }

    private fun drawBottomBar(c: Canvas) {
        val y = height - 18f
        textDim.color = cGreenDim
        c.drawText("2.4G:$band24  5G:$band5", 18f, y, textDim)

        val baseStr = "BASE:$baselineSize"
        c.drawText(baseStr, width - textDim.measureText(baseStr) - 18f, y, textDim)
    }

    private fun drawAnomalyTicker(c: Canvas) {
        if (recentAnomalies.isEmpty()) return

        val startY = height * 0.14f
        val x = 18f
        val toShow = recentAnomalies.takeLast(6)

        for ((i, a) in toShow.withIndex()) {
            val paint = when {
                a.type == Anomaly.Type.KNOWN_TRACKER -> textAlert.apply { color = cMagenta }
                a.isHighPriority -> textAlert.apply { color = cRed }
                a.type == Anomaly.Type.NEW_DEVICE -> textAlert.apply { color = cYellow }
                else -> textDim.apply { color = cGreenDim }
            }
            val line = "[${a.type.label}] ${a.detail.take(30)}"
            c.drawText(line, x, startY + i * (paint.textSize + 5f), paint)
        }
    }
}
