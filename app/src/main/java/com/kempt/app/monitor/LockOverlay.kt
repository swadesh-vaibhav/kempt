/**
 * @file
 * @brief The full-screen passcode overlay drawn over blocked apps.
 */
package com.kempt.app.monitor

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import com.kempt.app.R

/**
 * @brief The full-screen lock drawn over a blocked app via @c TYPE_APPLICATION_OVERLAY.
 *
 * @details Collects a passcode and hands it to the @c onSubmit callback; the caller verifies
 * it (the check is a suspend/DataStore read) and then calls #dismiss on success or #showError
 * on failure.
 *
 * @warning All methods must be called on the main thread.
 * @param context Context used to inflate the layout and reach the @c WindowManager.
 */
class LockOverlay(private val context: Context) {

    /** @brief System @c WindowManager used to add and remove the overlay window. */
    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    /** @brief The overlay's root view while shown, or @c null when not added. */
    private var root: View? = null

    /** @brief Cached reference to the "wrong passcode" text, toggled by #showError. */
    private var errorView: TextView? = null

    /**
     * @brief Whether the lock is actually on screen right now.
     *
     * @details Checks real window attachment, not just @c root != null, as a safety net: if
     * the framework tears our window off without #dismiss being called, @c isAttachedToWindow
     * flips to @c false and the monitor re-adds the overlay on its next tick (as long as a
     * blocked app is still on top).
     *
     * @note @c root?.isAttachedToWindow == true uses Kotlin's safe-call operator @c ?.: on a
     * @c null @c root the expression is @c null, and @c null == true evaluates to @c false, so
     * the whole thing collapses to @c false. Must be read on the main thread.
     */
    val isShowing: Boolean get() = root?.isAttachedToWindow == true

    /** @brief @return @c true if the "draw over other apps" permission is currently held. */
    fun canDraw(): Boolean = Settings.canDrawOverlays(context)

    /**
     * @brief Shows the lock for a blocked app and wires up the Unlock button.
     *
     * @details No-ops if already showing or the overlay permission is missing. Any orphaned
     * previous view (one the system detached without a #dismiss) is removed first so a stale
     * window isn't leaked.
     *
     * @param blockedLabel The app name to display in the lock message.
     * @param onSubmit Invoked on the main thread with the entered code each time Unlock is tapped.
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

        // runCatching wraps the call and captures any exception instead of crashing, so a
        // refused overlay (e.g. the system blocking overlays over Settings) is logged, not fatal.
        runCatching { windowManager.addView(view, params) }
            .onSuccess { root = view }
            .onFailure { Log.w("AppMonitor", "overlay addView failed for $blockedLabel", it) }
    }

    /** @brief Reveals the "wrong passcode" message. Call after a failed verification. */
    fun showError() {
        errorView?.visibility = View.VISIBLE
    }

    /** @brief Removes the overlay window (if present) and drops all view references. */
    fun dismiss() {
        root?.let { runCatching { windowManager.removeView(it) } }
        root = null
        errorView = null
    }
}
