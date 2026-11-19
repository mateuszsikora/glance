package com.glance.settings

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.provider.Settings
import android.view.ViewGroup
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.test.core.app.ApplicationProvider
import com.glance.R
import com.glance.config.AppConfig
import com.google.android.material.button.MaterialButton
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAppTask
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class ExitKioskModeTest {

    private lateinit var context: Application
    private lateinit var config: AppConfig

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        config = AppConfig(context).apply { setSettingsPin(PIN) }
    }

    /**
     * The settings screen sits on top of the dashboard in the same task. Finishing only itself
     * left the dashboard alive, and returning to it skipped the launch that resumes kiosk mode.
     */
    @Test
    fun exitRemovesTheWholeTaskIncludingTheDashboardBelowSettings() {
        // More than one, so that removing only the first task would fail too.
        val tasks = listOf(ShadowAppTask.newInstance(), ShadowAppTask.newInstance())
        shadowOf(context.getSystemService(ActivityManager::class.java)).setAppTasks(tasks)
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        try {
            val activity = controller.get()
            confirm { (it.getChildAt(0) as EditText).setText(PIN) }

            activity.findViewById<MaterialButton>(R.id.btnExitKiosk).performClick()
            confirm()

            assertTrue("Kiosk mode was not suspended", config.isKioskSuspended)
            tasks.forEachIndexed { index, task ->
                assertTrue(
                    "Glance's task $index survived the exit; the dashboard would stay " +
                        "suspended (#85)",
                    shadowOf(task).isFinishedAndRemoved
                )
            }
            val started = generateSequence { shadowOf(activity).nextStartedActivity }
                .firstOrNull { it.action == Settings.ACTION_SETTINGS }
            assertNotNull("Android Settings was not opened", started)
            // Otherwise it would join Glance's task and be removed with it.
            assertEquals(
                "Android Settings must open in a task of its own",
                Intent.FLAG_ACTIVITY_NEW_TASK,
                started!!.flags and Intent.FLAG_ACTIVITY_NEW_TASK
            )
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun cancellingTheExitLeavesKioskModeAndTheTaskAlone() {
        val task = ShadowAppTask.newInstance()
        shadowOf(context.getSystemService(ActivityManager::class.java))
            .setAppTasks(listOf(task))
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        try {
            confirm { (it.getChildAt(0) as EditText).setText(PIN) }

            controller.get().findViewById<MaterialButton>(R.id.btnExitKiosk).performClick()
            (ShadowDialog.getLatestDialog() as AlertDialog)
                .getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()

            assertFalse("Cancel suspended kiosk mode", config.isKioskSuspended)
            assertFalse("Cancel removed Glance's task", shadowOf(task).isFinishedAndRemoved)
            assertFalse("Cancel closed the settings screen", controller.get().isFinishing)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    private fun confirm(fill: (ViewGroup) -> Unit = {}) {
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        dialog.findViewById<ViewGroup>(androidx.appcompat.R.id.custom)?.let(fill)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private companion object {
        const val PREFS_NAME = "glance_config"
        const val PIN = "583902"
    }
}
