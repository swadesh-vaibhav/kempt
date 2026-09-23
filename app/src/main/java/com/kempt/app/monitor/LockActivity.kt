/**
 * @file
 * @brief Full-screen passcode lock shown over apps the overlay can't cover (e.g. Settings).
 */
package com.kempt.app.monitor

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.lifecycle.lifecycleScope
import com.kempt.app.R
import com.kempt.app.data.BlockEvent
import com.kempt.app.data.BlockEventDao
import com.kempt.app.data.LockStateStore
import com.kempt.app.sync.AccountabilityService
import com.kempt.app.sync.HeartbeatWorker
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * @brief A real, full-screen Activity that presents the same passcode lock as @ref LockOverlay.
 *
 * @details The @ref LockOverlay draws a @c TYPE_APPLICATION_OVERLAY window, which modern Android
 * deliberately *hides* over the system Settings app (an anti-tapjacking protection). That makes
 * an overlay useless for the one screen we most need to block during a lock — Settings is where a
 * user would revoke Kempt's permissions or force-stop it. An Activity is drawn by the window
 * compositor as a normal foreground app, so it is *not* suppressed, and launching it pushes
 * Settings into the background. @ref AppMonitorService starts this instead of the overlay for the
 * force-blocked Settings package.
 *
 * @note @c @AndroidEntryPoint lets Hilt inject the @c lateinit fields below. @c lateinit is a
 * Kotlin promise that a non-null @c var is assigned before first use (here by Hilt), so the
 * fields don't have to be nullable.
 */
@AndroidEntryPoint
class LockActivity : ComponentActivity() {

    /** @brief Store used to verify the partner passcode and clear the armed flag. Injected by Hilt. */
    @Inject lateinit var lockState: LockStateStore

    /** @brief DAO for recording the unlock-success / wrong-passcode events. Injected by Hilt. */
    @Inject lateinit var blockEventDao: BlockEventDao

    /** @brief Outbound channel that reports those events to the partner. Injected by Hilt. */
    @Inject lateinit var accountability: AccountabilityService

    /**
     * @brief Inflates the shared lock layout and wires the Unlock button.
     * @param savedInstanceState Unused; the lock has no state worth restoring.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Reuse the very same layout the overlay uses, so the two locks look identical.
        setContentView(R.layout.overlay_lock)
        val passcode = findViewById<EditText>(R.id.lock_passcode)
        val errorView = findViewById<TextView>(R.id.lock_error)

        findViewById<Button>(R.id.lock_unlock).setOnClickListener {
            errorView.visibility = View.INVISIBLE
            submit(passcode.text?.toString().orEmpty(), errorView)
        }

        // Back must not drop the user straight back into Settings. Route it to the home screen
        // instead, then finish this Activity. addCallback registers a handler on the modern
        // OnBackPressedDispatcher (the replacement for the deprecated onBackPressed()).
        onBackPressedDispatcher.addCallback(this) {
            goHome()
            finish()
        }
    }

    /**
     * @brief Verifies the entered passcode and, on success, tears the whole lock down.
     *
     * @details Runs in @c lifecycleScope — a coroutine scope tied to this Activity that is
     * cancelled automatically if the Activity is destroyed — because the passcode check and the
     * DataStore writes are @c suspend functions. On success we record the unlock, clear the
     * armed flag, stop the monitor service and its heartbeat, and finish; on failure we log the
     * attempt (the partner is notified) and reveal the error text.
     *
     * @param code The passcode the user typed.
     * @param errorView The "wrong passcode" label to reveal on a failed attempt.
     */
    private fun submit(code: String, errorView: TextView) {
        lifecycleScope.launch {
            if (lockState.verifyPasscode(code)) {
                recordAndReport(BlockEvent.UNLOCK_SUCCESS)
                lockState.disarm()
                AppMonitorService.stop(this@LockActivity)
                HeartbeatWorker.cancel(this@LockActivity)
                finish()
            } else {
                recordAndReport(BlockEvent.UNLOCK_FAILED)
                errorView.visibility = View.VISIBLE
            }
        }
    }

    /**
     * @brief Records an event locally, reports it, and marks it synced only if the report lands.
     * @details Mirrors the identically named helper in @ref AppMonitorService so both lock UIs
     * log unlocks the same way.
     * @param type One of the @ref com.kempt.app.data.BlockEvent type constants.
     */
    private suspend fun recordAndReport(type: String) {
        val event = BlockEvent(type = type, packageName = SETTINGS_TAG)
        val id = blockEventDao.insert(event)
        if (accountability.report(event.copy(id = id))) {
            blockEventDao.markSynced(listOf(id))
        }
    }

    /** @brief Sends the user to the launcher, so leaving the lock never reveals the blocked app. */
    private fun goHome() {
        startActivity(
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /** @brief Start helper and constants. */
    companion object {
        /** @brief Recorded as the event's package, since this lock stands in for a blocked app. */
        private const val SETTINGS_TAG = "com.android.settings"

        /**
         * @brief Brings the full-screen lock to the foreground over the currently blocked app.
         * @details @c FLAG_ACTIVITY_NEW_TASK is required to start an Activity from a Service (a
         * non-Activity context). Kempt is exempt from Android's background-activity-start limits
         * because it holds the "Draw over other apps" permission, so this launch succeeds even
         * though it originates from the background monitor.
         * @param context The calling context (the service).
         */
        fun start(context: Context) {
            context.startActivity(
                Intent(context, LockActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
