package com.atakmap.android.xv.ui

import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
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
 *    Signal Grey negative / neutral; all buttons 48dp, no all-caps;
 *  - text fields inside custom dialog views get the Settings field look
 *    (Wax White text, Signal Grey hint, field background);
 *  - list / spinner rows built with [textAdapter] read as body text.
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
        dialog.window?.decorView?.let { styleFields(it, plugin) }
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

    // EditTexts anywhere in a dialog's custom view: field look.
    private fun styleFields(
        v: View,
        plugin: Context,
    ) {
        val res = plugin.resources
        when (v) {
            is EditText -> {
                v.setTextColor(res.getColor(R.color.xv_text, null))
                v.setHintTextColor(res.getColor(R.color.xv_text_dim, null))
                val pad = (12 * res.displayMetrics.density).toInt()
                v.background = res.getDrawable(R.drawable.xv_field_bg, null)
                v.setPadding(pad, pad, pad, pad)
            }
            is ViewGroup -> for (i in 0 until v.childCount) styleFields(v.getChildAt(i), plugin)
        }
    }

    /**
     * An adapter for dialog lists and spinners whose rows read as XV body
     * text on the dark dialog. Built on ATAK's [ctx] (plugin layouts can't
     * be inflated with it), recolouring the stock rows.
     */
    fun <T> textAdapter(
        ctx: Context,
        plugin: Context,
        items: List<T>,
    ): ArrayAdapter<T> {
        val res = plugin.resources
        val text = res.getColor(R.color.xv_text, null)
        val surface = res.getColor(R.color.xv_surface, null)
        val minH = (48 * res.displayMetrics.density).toInt()
        return object : ArrayAdapter<T>(ctx, android.R.layout.simple_list_item_1, items) {
            override fun getView(
                position: Int,
                convertView: View?,
                parent: ViewGroup,
            ): View = style(super.getView(position, convertView, parent), dropDown = false)

            override fun getDropDownView(
                position: Int,
                convertView: View?,
                parent: ViewGroup,
            ): View = style(super.getDropDownView(position, convertView, parent), dropDown = true)

            private fun style(
                row: View,
                dropDown: Boolean,
            ): View {
                (row as? TextView)?.apply {
                    setTextColor(text)
                    minHeight = minH
                    if (dropDown) setBackgroundColor(surface)
                }
                return row
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
