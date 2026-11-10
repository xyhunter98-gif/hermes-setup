package com.hermes.assistant

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.TextView

/**
 * A small floating popup (like the Google Assistant "Listening…" bubble)
 * that shows over whatever app you're in while Hermes is listening or
 * reporting a result. Requires the SYSTEM_ALERT_WINDOW permission, which
 * the user grants from Settings (MainActivity walks them there).
 */
class OverlayBubble(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var bubbleView: View? = null

    @SuppressLint("InflateParams")
    fun show(initialText: String) {
        if (bubbleView != null) {
            update(initialText)
            return
        }

        val view = LayoutInflater.from(context).inflate(R.layout.overlay_popup, null)
        view.findViewById<TextView>(R.id.popupText).text = initialText

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 120
        }

        windowManager.addView(view, params)
        bubbleView = view
    }

    fun update(text: String) {
        bubbleView?.findViewById<TextView>(R.id.popupText)?.text = text
    }

    fun hide() {
        bubbleView?.let {
            windowManager.removeView(it)
            bubbleView = null
        }
    }
}
