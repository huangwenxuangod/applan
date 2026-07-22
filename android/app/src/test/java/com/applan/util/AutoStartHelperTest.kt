package com.applan.util

import android.app.Application
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class AutoStartHelperTest {

    @Test
    fun `autostart entry opens the Settings home page`() {
        val context = ApplicationProvider.getApplicationContext<Application>()

        AutoStartHelper.jumpToAutoStartSetting(context)

        assertEquals(Settings.ACTION_SETTINGS, shadowOf(context).nextStartedActivity.action)
    }
}
