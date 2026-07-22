package com.applan.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PolicyRepositoryTest {
    private lateinit var repository: PolicyRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("app_config", Context.MODE_PRIVATE).edit().clear().commit()
        repository = PolicyRepository(context)
    }

    @Test
    fun `profiles and plan survive repository recreation`() {
        val profile = TimeProfile("work", setOf(1, 2), 540, 720, setOf("com.tencent.wework"))
        val plan = AiPlan(setOf("com.tencent.wework"), "review", 1_800_000_000_000)

        repository.saveProfiles(listOf(profile))
        repository.savePlan(plan)

        val restored = PolicyRepository(ApplicationProvider.getApplicationContext())
        assertEquals(listOf(profile), restored.getProfiles())
        assertEquals(plan, restored.getPlan())
    }

    @Test
    fun `clearing plan removes it from storage`() {
        repository.savePlan(AiPlan(setOf("com.tencent.mm"), "reply", 1_800_000_000_000))

        repository.clearPlan()

        assertEquals(null, repository.getPlan())
    }
}
