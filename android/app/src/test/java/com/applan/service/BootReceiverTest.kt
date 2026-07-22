package com.applan.service

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.applan.MainActivity
import com.applan.util.AppConfig
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BootReceiverTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        AppConfig.init(context)
        AppConfig.setExitGranted(true)
    }

    @Test
    fun `device boot clears passive exit and launches main activity`() {
        BootReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))

        assertEquals(false, AppConfig.isExitGranted())
        assertEquals(MainActivity::class.java.name, shadowOf(context as android.app.Application).nextStartedActivity.component?.className)
    }
}
