package com.applan.util

import android.app.Application
import android.app.admin.DevicePolicyManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class PermissionHelperTest {
    @Test
    fun `request device admin opens the system admin consent screen`() {
        val context = ApplicationProvider.getApplicationContext<Application>()

        PermissionHelper.requestDeviceAdmin(context)

        assertEquals(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN, shadowOf(context).nextStartedActivity.action)
    }
}
