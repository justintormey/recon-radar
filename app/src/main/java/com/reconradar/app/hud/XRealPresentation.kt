package com.reconradar.app.hud

import android.app.Presentation
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.view.Display
import android.view.WindowManager
import android.widget.FrameLayout
import com.reconradar.app.model.Anomaly
import com.reconradar.app.model.DetectedDevice

/**
 * Renders the full radar + HUD onto XReal One Pro glasses.
 *
 * XReal One Pro registers as an external display via USB-C.
 * This uses Android's standard Presentation API — no XREAL SDK,
 * Unity, or ControlGlasses app required. Works with any phone
 * that supports USB-C display output.
 */
class XRealPresentation(
    context: Context, display: Display
) : Presentation(context, display) {

    private lateinit var radarView: RadarView
    private lateinit var hudOverlay: HudOverlayView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = FrameLayout(context).apply {
            setBackgroundColor(0xFF070707.toInt())
        }

        radarView = RadarView(context)
        hudOverlay = HudOverlayView(context)

        val matchParent = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        root.addView(radarView, matchParent)
        root.addView(hudOverlay, matchParent)

        setContentView(root)
        radarView.startSweep()
    }

    fun updateDevices(
        devices: List<DetectedDevice>,
        anomalies: List<Anomaly>,
        baselineSize: Int
    ) {
        radarView.updateDevices(devices, anomalies)

        hudOverlay.wifiCount = devices.count { it.type == DetectedDevice.DeviceType.WIFI }
        hudOverlay.bleCount = devices.count { it.type == DetectedDevice.DeviceType.BLE }
        hudOverlay.anomalyCount = anomalies.count { it.type != Anomaly.Type.KNOWN_TRACKER }
        hudOverlay.trackerCount = anomalies.count { it.type == Anomaly.Type.KNOWN_TRACKER }
        hudOverlay.band24 = devices.count { it.wifiBand == "2.4G" }
        hudOverlay.band5 = devices.count { it.wifiBand == "5G" }
        hudOverlay.baselineSize = baselineSize
        hudOverlay.recentAnomalies = anomalies
        hudOverlay.xrealConnected = true
        hudOverlay.refresh()
    }

    fun setScanning(scanning: Boolean) {
        hudOverlay.isScanning = scanning
        if (scanning) radarView.startSweep() else radarView.stopSweep()
        hudOverlay.refresh()
    }

    override fun onStop() { super.onStop(); radarView.stopSweep() }

    companion object {
        /** Find an external presentation display (XReal glasses, etc). */
        fun findExternalDisplay(context: Context): Display? {
            val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            return dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).firstOrNull()
        }

        fun registerDisplayListener(
            context: Context,
            onConnected: (Display) -> Unit,
            onDisconnected: () -> Unit
        ): DisplayManager.DisplayListener {
            val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            val listener = object : DisplayManager.DisplayListener {
                override fun onDisplayAdded(displayId: Int) {
                    val d = dm.getDisplay(displayId)
                    if (d != null && d.displayId != Display.DEFAULT_DISPLAY) onConnected(d)
                }
                override fun onDisplayRemoved(displayId: Int) { onDisconnected() }
                override fun onDisplayChanged(displayId: Int) {}
            }
            dm.registerDisplayListener(listener, null)
            return listener
        }

        fun unregisterDisplayListener(context: Context, listener: DisplayManager.DisplayListener) {
            (context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
                .unregisterDisplayListener(listener)
        }
    }
}
