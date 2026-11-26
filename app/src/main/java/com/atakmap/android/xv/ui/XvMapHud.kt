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
        val size: MapHudPolicy.Size,
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
    private val iconUris = mutableMapOf<String, String>()

    // Last drawn icon size (px), for slide-off detection.
    @Volatile private var iconSizePx: Pair<Int, Int> = 0 to 0

    @Volatile private var panelOpen = false

    // Current hex width in px (from the size setting and screen); main thread.
    private var hexWidthPx = MapHudPolicy.MIN_HEX_WIDTH_DP * density

    @Volatile private var heldSlot: Int? = null

    @Volatile private var attached = false

    // Advances once per TICK_MS; drives long-name scrolling.
    private var scrollTick = 0L

    private val ticker =
        object : Runnable {
            override fun run() {
                scrollTick++
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
                // Slide-off: release once the finger leaves the hex by more
                // than the touch slop (don't rely on ATAK sending unpress).
                w.addOnMoveListener(
                    MapWidget.OnMoveListener { widget, ev ->
                        if (heldSlot == slot && slidOff(widget, ev.x, ev.y)) release(slot)
                        false
                    },
                )
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
        // Red first (jump the queue), then key: the visual must not wait
        // on mic warm-up / the talk-permit tone.
        main.postAtFrontOfQueue { refresh() }
        pttDown(slot)
    }

    private fun release(slot: Int) {
        if (heldSlot != slot) return
        heldSlot = null
        Log.i("XV_PTT", ">>> MAP HUD PTT${slot + 1} (slot=$slot) UP <<<")
        pttUp(slot)
        refreshNow()
    }

    private fun slidOff(
        widget: MapWidget,
        x: Float,
        y: Float,
    ): Boolean {
        val pos = widget.absolutePosition
        val slop = android.view.ViewConfiguration.get(pluginContext).scaledTouchSlop.toFloat()
        val (bw, bh) = iconSizePx
        return OnScreenPttGate.slidOff(x - pos.x, y - pos.y, bw, bh, slop)
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
        val drawn = MapHudPolicy.buttons(snap.slot1, snap.slot2, heldSlot)
        hexWidthPx = MapHudPolicy.hexWidthDp(snap.size, shortSideDp()) * density
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
        val frame = labelFrame(b.label)
        val key = "${b.look}|$frame|${hexWidthPx.toInt()}"
        if (renderedKey[b.slot] == key) return
        renderedKey[b.slot] = key
        // Make sure this slot's red (TX) image exists too, so the press
        // feedback is a cached file, not a draw + PNG encode on the press.
        if (b.look != MapHudPolicy.Look.TRANSMITTING) {
            iconUri(b.copy(look = MapHudPolicy.Look.TRANSMITTING, label = "TX"))
        }
        val bmpW = (hexWidthPx + 2 * MARGIN_DP * density).toInt()
        val bmpH = (hexWidthPx * MapHudPolicy.HEX_ASPECT + 2 * MARGIN_DP * density).toInt()
        iconSizePx = bmpW to bmpH
        val uri = iconUri(b.copy(label = frame)) ?: return
        val icon =
            Icon
                .Builder()
                .setImageUri(0, uri)
                .setSize(bmpW, bmpH)
                .setAnchor(0, 0)
                .build()
        w.setIcon(icon)
        // Whole hex is the touch target (never below 48dp: gloves).
        w.setMarkerHitBounds(0, 0, bmpW, bmpH)
    }

    // Shorter side of the map view in dp (display metrics until laid out).
    private fun shortSideDp(): Float {
        val dm = pluginContext.resources.displayMetrics
        val shortPx =
            if (mapView.width > 0 && mapView.height > 0) {
                minOf(mapView.width, mapView.height)
            } else {
                minOf(dm.widthPixels, dm.heightPixels)
            }
        return shortPx / density
    }

    // Drawn + written once per slot/look/label/size; later uses are a map hit.
    private fun iconUri(b: MapHudPolicy.Button): String? {
        val key = "${b.slot}|${b.look}|${b.label}|${hexWidthPx.toInt()}"
        // ATAK's GL image loader didn't render a base64:// URI on device
        // (widget took touches but drew nothing). Write the PNG into ATAK's
        // own cache dir — same process/UID as the map renderer, readable
        // by it — and hand it a file:// URI. One file per slot+look+label,
        // so a changed state is a new URI (no stale cached texture).
        return iconUris[key] ?: writeIconFile(b, drawHex(b))?.also { iconUris[key] = it }
    }

    private fun writeIconFile(
        b: MapHudPolicy.Button,
        bmp: Bitmap,
    ): String? =
        try {
            val dir = File(mapView.context.cacheDir, "xv_map_ptt").apply { mkdirs() }
            val safe = "${b.slot}_${b.look}_${b.label}_${hexWidthPx.toInt()}".replace(Regex("[^A-Za-z0-9_]"), "_")
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
        val w = hexWidthPx.toInt()
        val h = (hexWidthPx * MapHudPolicy.HEX_ASPECT).toInt()
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
        // Slot number: a small badge at the top; the channel name gets the body.
        p.textSize = 13f * density * scale()
        c.drawText("${b.slot + 1}", m + w / 2f, m + h * 0.27f, p)
        drawLabel(c, p, b.label, m + w / 2f, m + h * 0.58f)
        return bmp
    }

    // Labels are sized for the 72dp reference hex and scale with the size setting.
    private fun scale(): Float = hexWidthPx / (REFERENCE_HEX_DP * density)

    // Usable label width inside the hex body (its side walls slope in).
    private fun labelMaxWidth(): Float = hexWidthPx * LABEL_WIDTH_FRACTION

    private val measurePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFakeBoldText = true }

    private fun measure(
        text: String,
        sizeSp: Float,
    ): Float {
        measurePaint.textSize = sizeSp * density * scale()
        return measurePaint.measureText(text)
    }

    // The label text drawn this tick: the whole name when it fits on up to
    // two lines, else the current slice of a slow scroll ("\u0001" marks
    // a scroll frame so it's drawn on one line at the scroll size).
    private fun labelFrame(label: String): String {
        if (MapHudPolicy.layoutLabel(label, labelMaxWidth(), ::measure) != null) return label
        val window = MapHudPolicy.fitChars(label, labelMaxWidth()) { measure(it, MapHudPolicy.SCROLL_SIZE_SP) }
        return SCROLL_MARK + MapHudPolicy.scrollWindow(label, window, scrollTick)
    }

    private fun drawLabel(
        c: Canvas,
        p: Paint,
        frame: String,
        cx: Float,
        cy: Float,
    ) {
        if (frame.startsWith(SCROLL_MARK)) {
            p.textSize = MapHudPolicy.SCROLL_SIZE_SP * density * scale()
            c.drawText(frame.removePrefix(SCROLL_MARK), cx, cy + p.textSize / 3f, p)
            return
        }
        val layout = MapHudPolicy.layoutLabel(frame, labelMaxWidth(), ::measure) ?: return
        p.textSize = layout.sizeSp * density * scale()
        val lineH = p.textSize * 1.15f
        var y = cy - (layout.lines.size - 1) * lineH / 2f + p.textSize / 3f
        for (line in layout.lines) {
            c.drawText(line, cx, y, p)
            y += lineH
        }
    }

    companion object {
        private const val TAG = "XvMapHud"
        private const val MARGIN_DP = 4f
        private const val REFERENCE_HEX_DP = 72f
        private const val LABEL_WIDTH_FRACTION = 0.84f
        private const val SCROLL_MARK = "\u0001"
        private const val TALKER_TEXT_SIZE = 14

        // Talker / TX state while the panel is collapsed (the panel's own
        // refresh loop stops then).
        private const val TICK_MS = 500L
    }
}
