package com.atakmap.android.xv.ui

import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
import android.graphics.drawable.ColorDrawable
import android.widget.TextView
import com.atakmap.android.xv.R

/**
 * One look for every dialog XV shows (pickers, confirms, share/import,
 * channel config), matching the panel and Settings.
 *
 * Dialogs are built on ATAK's activity context (they need its window
 * token), which can't resolve plugin theme resources, so the style is
 * applied after the dialog is shown, using drawables and colours read
 * from the plugin's own resources:
 *  - Comb surface with a rounded hairline border;
 *  - Wax White title and message;
 *  - Honey positive button (error red when the action is destructive),
 *    Signal Grey negative / neutral; all buttons 48dp, no all-caps.
 */
object XvDialogStyle {
    fun apply(
        dialog: AlertDialog,
        plugin: Context,
        destructive: Boolean,
    ) {
        // Styling is cosmetic: a resource mismatch must never break the
        // dialog itself (it stays usable in ATAK's default look).
        try {
            applyStyle(dialog, plugin, destructive)
        } catch (t: Throwable) {
            android.util.Log.w("XvDialogStyle", "dialog styling skipped: ${t.message}")
        }
    }

    private fun applyStyle(
        dialog: AlertDialog,
        plugin: Context,
        destructive: Boolean,
    ) {
        val res = plugin.resources
        val density = res.displayMetrics.density
        dialog.window?.setBackgroundDrawable(res.getDrawable(R.drawable.xv_dialog_bg, null))
        val text = res.getColor(R.color.xv_text, null)
        val dim = res.getColor(R.color.xv_text_dim, null)
        dialog.findViewById<TextView>(android.R.id.message)?.setTextColor(text)
        titleView(dialog)?.setTextColor(text)
        dialog.listView?.apply {
            divider = ColorDrawable(res.getColor(R.color.xv_line, null))
            dividerHeight = 1
        }
        val positive = res.getColor(if (destructive) R.color.xv_err else R.color.xv_voice, null)
        for ((which, color) in listOf(
            DialogInterface.BUTTON_POSITIVE to positive,
            DialogInterface.BUTTON_NEGATIVE to dim,
            DialogInterface.BUTTON_NEUTRAL to dim,
        )) {
            dialog.getButton(which)?.apply {
                setTextColor(color)
                isAllCaps = false
                minHeight = (48 * density).toInt()
                minimumHeight = (48 * density).toInt()
            }
        }
    }

    // AlertDialog's title TextView ("alertTitle") isn't public API.
    private fun titleView(dialog: AlertDialog): TextView? {
        val id = dialog.context.resources.getIdentifier("alertTitle", "id", "android")
        return if (id != 0) dialog.findViewById(id) else null
    }
}

/**
 * Create, style and show. Use in place of [AlertDialog.Builder.show] for
 * every XV dialog. [destructive] colours the positive action red (wipe,
 * forget, remove).
 */
fun AlertDialog.Builder.showXv(
    plugin: Context,
    destructive: Boolean = false,
): AlertDialog {
    val dialog = create()
    dialog.setOnShowListener { XvDialogStyle.apply(dialog, plugin, destructive) }
    dialog.show()
    return dialog
}
