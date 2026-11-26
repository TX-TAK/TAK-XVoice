package com.atakmap.android.xv.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.atakmap.android.maps.MapView
import com.atakmap.android.widgets.LinearLayoutWidget
import com.atakmap.android.widgets.MapWidget
import com.atakmap.android.widgets.MarkerIconWidget
import com.atakmap.android.widgets.RootLayoutWidget
import com.atakmap.android.widgets.TextWidget
import com.atakmap.android.xv.R
import com.atakmap.coremap.maps.assets.Icon
import java.io.File
import java.io.FileOutputStream

/**
 * On-map PTT overlay (the approved map-HUD mockup): two hex PTT buttons
 * docked to the map edge with ATAK MapWidgets, so ATAK's own layout keeps
 * them clear of the compass, scale bar and open panes.
 *
 * Shown while the XV panel is collapsed, hidden while it's open (the
 * panel has its own PTT). Momentary: press keys, unpress releases.
 *
 * Stuck-key guards (a stuck key is the worst failure this plugin can
 * have): a held button is released when it is hidden, removed (channel 2
 * turned off), the side changes, or the HUD detaches. The service's PTT
 * timeout is the last line of defence.
 */
class XvMapHud(
    private val mapView: MapView,
    private val pluginContext: Context,
    private val pttDown: (slot: Int) -> Unit,
    private val pttUp: (slot: Int) -> Unit,
    private val state: () -> Snapshot,
) {
    /** Everything the HUD renders, read from the controller each refresh. */
    data class Snapshot(
        val enabled: Boolean,
        val leftHanded: Boolean,
        val slot1: MapHudPolicy.SlotState,
        val slot2: MapHudPolicy.SlotState?,
    )

    private val main = Handler(Looper.getMainLooper())
    private val density = pluginContext.resources.displayMetrics.density
    private var root: RootLayoutWidget? = null
    private var dock: LinearLayoutWidget? = null
    private var dockedSide: MapHudPolicy.Side? = null
    private val holder = LinearLayoutWidget(LinearLayoutWidget.WRAP_CONTENT, LinearLayoutWidget.WRAP_CONTENT, LinearLayoutWidget.VERTICAL)
    private val talkerPill = TextWidget("")
    private val buttons = mapOf(0 to MarkerIconWidget(), 1 to MarkerIconWidget())
    private val renderedKey = mutableMapOf<Int, String>()

    @Volatile private var panelOpen = false

    @Volatile private var heldSlot: Int? = null

    @Volatile private var attached = false

    private val ticker =
        object : Runnable {
            override fun run() {
                refresh()
                if (attached) main.postDelayed(this, TICK_MS)
            }
        }

    fun attach() {
        main.post {
            if (attached) return@post
            root = mapView.getComponentExtra("rootLayoutWidget") as? RootLayoutWidget
            if (root == null) {
                Log.w(TAG, "no rootLayoutWidget — map PTT overlay disabled")
                return@post
            }
            holder.setName("XV map PTT")
            talkerPill.setName("XV talker")
            talkerPill.setTextFormat(com.atakmap.android.maps.MapTextFormat(android.graphics.Typeface.DEFAULT_BOLD, TALKER_TEXT_SIZE))
            talkerPill.setColor(pluginContext.resources.getColor(R.color.xv_voice, null))
            talkerPill.setBackground(TextWidget.TRANSLUCENT_BLACK)
            holder.addWidget(talkerPill)
            for ((slot, w) in buttons) {
                w.setName("XV PTT ${slot + 1}")
                w.addOnPressListener(MapWidget.OnPressListener { _, _ -> press(slot) })
                w.addOnUnpressListener(MapWidget.OnUnpressListener { _, _ -> release(slot) })
                holder.addWidget(w)
            }
            attached = true
            refresh()
            main.postDelayed(ticker, TICK_MS)
        }
    }

    fun detach() {
        main.post {
            attached = false
            main.removeCallbacks(ticker)
            releaseHeld("detach")
            dock?.removeWidget(holder)
            dock = null
            dockedSide = null
        }
    }

    /** The XV panel opened (true) or collapsed (false). */
    fun setPanelOpen(open: Boolean) {
        panelOpen = open
        main.post { refresh() }
    }

    fun refreshNow() {
        main.post { refresh() }
    }

    private fun press(slot: Int) {
        if (heldSlot != null) return
        heldSlot = slot
        Log.i("XV_PTT", ">>> MAP HUD PTT${slot + 1} (slot=$slot) DOWN <<<")
        pttDown(slot)
        refreshNow()
    }

    private fun release(slot: Int) {
        if (heldSlot != slot) return
        heldSlot = null
        Log.i("XV_PTT", ">>> MAP HUD PTT${slot + 1} (slot=$slot) UP <<<")
        pttUp(slot)
        refreshNow()
    }

    private fun releaseHeld(why: String) {
        val slot = heldSlot ?: return
        Log.w(TAG, "releasing held map PTT${slot + 1}: $why")
        release(slot)
    }

    // Main thread only.
    private fun refresh() {
        if (!attached) return
        val snap = state()
        val visible = MapHudPolicy.visible(snap.enabled, panelOpen)
        val drawn = MapHudPolicy.buttons(snap.slot1, snap.slot2)
        MapHudPolicy.releaseOnChange(heldSlot, visible, drawn.map { it.slot }.toSet())?.let {
            releaseHeld(if (visible) "button removed" else "overlay hidden")
        }
        redock(MapHudPolicy.side(snap.leftHanded))
        holder.setVisible(visible)
        if (!visible) return
        val pill = MapHudPolicy.talkerPill(snap.slot1, snap.slot2)
        talkerPill.setText(pill.orEmpty())
        talkerPill.setVisible(pill != null)
        for ((slot, w) in buttons) {
            val b = drawn.firstOrNull { it.slot == slot }
            w.setVisible(b != null)
            if (b != null) render(w, b)
        }
    }

    private fun redock(side: MapHudPolicy.Side) {
        if (side == dockedSide) return
        releaseHeld("side changed")
        val r = root ?: return
        dock?.removeWidget(holder)
        val edge = if (side == MapHudPolicy.Side.LEFT) RootLayoutWidget.LEFT_EDGE else RootLayoutWidget.RIGHT_EDGE
        dock = r.getLayout(edge).also { it.addWidget(holder) }
        dockedSide = side
    }

    private fun render(
        w: MarkerIconWidget,
        b: MapHudPolicy.Button,
    ) {
        val key = "${b.look}|${b.label}"
        if (renderedKey[b.slot] == key) return
        renderedKey[b.slot] = key
        val bmp = drawHex(b)
        // ATAK's GL image loader didn't render a base64:// URI on device
        // (widget took touches but drew nothing). Write the PNG into ATAK's
        // own cache dir — same process/UID as the map renderer, readable
        // by it — and hand it a file:// URI. One file per slot+look+label,
        // so a changed state is a new URI (no stale cached texture).
        val uri = writeIconFile(b, bmp) ?: return
        val icon =
            Icon
                .Builder()
                .setImageUri(0, uri)
                .setSize(bmp.width, bmp.height)
                .setAnchor(0, 0)
                .build()
        w.setIcon(icon)
        // Whole hex is the touch target (72x82dp: works with gloves).
        w.setMarkerHitBounds(0, 0, bmp.width, bmp.height)
    }

    private fun writeIconFile(
        b: MapHudPolicy.Button,
        bmp: Bitmap,
    ): String? =
        try {
            val dir = File(mapView.context.cacheDir, "xv_map_ptt").apply { mkdirs() }
            val safe = "${b.slot}_${b.look}_${b.label}".replace(Regex("[^A-Za-z0-9_]"), "_")
            val f = File(dir, "ptt_$safe.png")
            if (!f.exists()) {
                FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            "file://${f.absolutePath}"
        } catch (t: Throwable) {
            Log.w(TAG, "map PTT icon write failed", t)
            null
        }

    // Pointy-top hex in the brand kit badge shape, with the slot number on
    // top and the channel (or TX) below. Transparent margin keeps the two
    // buttons apart and off the screen edge.
    private fun drawHex(b: MapHudPolicy.Button): Bitmap {
        val w = (HEX_W_DP * density).toInt()
        val h = (HEX_H_DP * density).toInt()
        val m = (MARGIN_DP * density)
        val bmp = Bitmap.createBitmap(w + (2 * m).toInt(), h + (2 * m).toInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val res = pluginContext.resources
        val honey = res.getColor(R.color.xv_voice, null)
        val red = res.getColor(R.color.xv_ptt_active, null)
        val black = res.getColor(R.color.xv_bg, null)
        val dim = res.getColor(R.color.xv_text_dim, null)
        val text = res.getColor(R.color.xv_text, null)
        val path =
            Path().apply {
                moveTo(m + w * 0.5f, m)
                lineTo(m + w, m + h * 0.25f)
                lineTo(m + w, m + h * 0.75f)
                lineTo(m + w * 0.5f, m + h.toFloat())
                lineTo(m, m + h * 0.75f)
                lineTo(m, m + h * 0.25f)
                close()
            }
        val (fill, stroke, fg) =
            when (b.look) {
                MapHudPolicy.Look.TRANSMITTING -> Triple(red, red, text)
                MapHudPolicy.Look.RECEIVING -> Triple(honey, honey, black)
                MapHudPolicy.Look.UNAVAILABLE -> Triple(black, dim, dim)
                MapHudPolicy.Look.IDLE -> Triple(black, honey, text)
            }
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.style = Paint.Style.FILL
        p.color = fill
        // Solid fill: map labels must not show through the button text.
        p.alpha = 0xFF
        c.drawPath(path, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 2f * density
        p.color = stroke
        p.alpha = 0xFF
        c.drawPath(path, p)
        p.style = Paint.Style.FILL
        p.color = fg
        p.textAlign = Paint.Align.CENTER
        p.isFakeBoldText = true
        p.textSize = 22f * density
        c.drawText("${b.slot + 1}", m + w / 2f, m + h * 0.48f, p)
        p.textSize = 11f * density
        c.drawText(b.label, m + w / 2f, m + h * 0.72f, p)
        return bmp
    }

    companion object {
        private const val TAG = "XvMapHud"
        private const val HEX_W_DP = 72f
        private const val HEX_H_DP = 82f
        private const val MARGIN_DP = 4f
        private const val TALKER_TEXT_SIZE = 14

        // Talker / TX state while the panel is collapsed (the panel's own
        // refresh loop stops then).
        private const val TICK_MS = 500L
    }
}
