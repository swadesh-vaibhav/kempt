package com.kempt.app.monitor

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import com.kempt.app.R

/**
 * The full-screen lock drawn over a blocked app via `TYPE_APPLICATION_OVERLAY`. It
 * collects a passcode and hands it to [onSubmit]; the caller verifies it (the check is
 * a suspend/DataStore read) and then calls [dismiss] on success or [showError] on
 * failure. All methods must be called on the main thread.
 */
class LockOverlay(private val context: Context) {

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var root: View? = null
    private var errorView: TextView? = null

    /**
     * Whether the lock is actually on screen right now.
     *
     * This checks real window attachment, not just `root != null`, as a safety net: if the
     * framework ever tears our window off without [dismiss] being called, `isAttachedToWindow`
     * flips to false and the monitor will re-add the overlay on its next tick (as long as a
     * blocked app is still on top). Must be read on the main thread. The `== true` collapses
     * the nullable result: `null?.isAttachedToWindow` is `null`, and `null == true` is `false`.
     */
    val isShowing: Boolean get() = root?.isAttachedToWindow == true

    /** Whether we currently hold the "draw over other apps" permission. */
    fun canDraw(): Boolean = Settings.canDrawOverlays(context)

    /**
     * Show the lock for [blockedLabel]. [onSubmit] is invoked on the main thread with
     * the entered code each time the user taps Unlock.
     */
    fun show(blockedLabel: String, onSubmit: (String) -> Unit) {
        if (isShowing || !canDraw()) return

        // If we still hold a reference to a previous view that the system detached (see
        // isShowing), drop it before adding a fresh one so we don't leak the orphaned window.
        root?.let { runCatching { windowManager.removeView(it) } }
        root = null
        errorView = null

        val view = LayoutInflater.from(context).inflate(R.layout.overlay_lock, null)
        val passcode = view.findViewById<EditText>(R.id.lock_passcode)
        val message = view.findViewById<TextView>(R.id.lock_message)
        errorView = view.findViewById(R.id.lock_error)

        message.text = context.getString(R.string.overlay_message)
        view.findViewById<TextView>(R.id.lock_title).text =
            context.getString(R.string.overlay_title)

        view.findViewById<Button>(R.id.lock_unlock).setOnClickListener {
            errorView?.visibility = View.INVISIBLE
            onSubmit(passcode.text?.toString().orEmpty())
        }

        val type =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE

        // Focusable (so the EditText accepts input) and covering the whole screen.
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.OPAQUE
        ).apply { gravity = Gravity.CENTER }

        windowManager.addView(view, params)
        root = view
    }

    fun showError() {
        errorView?.visibility = View.VISIBLE
    }

    fun dismiss() {
        root?.let { runCatching { windowManager.removeView(it) } }
        root = null
        errorView = null
    }
}
