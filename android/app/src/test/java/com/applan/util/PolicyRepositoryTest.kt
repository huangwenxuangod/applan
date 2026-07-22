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

    @Test
    fun `temporary pass survives repository recreation and expires`() {
        val pass = TemporaryPass("com.sankuai.meituan", System.currentTimeMillis() + 300_000)

        repository.saveTemporaryPass(pass)

        assertEquals(pass, PolicyRepository(ApplicationProvider.getApplicationContext()).getTemporaryPass())

        repository.saveTemporaryPass(TemporaryPass("com.sankuai.meituan", System.currentTimeMillis() - 1))

        assertEquals(null, repository.getTemporaryPass())
    }

    @Test
    fun `remote backup replaces only long lived policy and marks it synced`() {
        repository.saveProfiles(listOf(TimeProfile("local", setOf(1), 540, 600, emptySet())))
        repository.savePlan(AiPlan(setOf("com.tencent.mm"), "reply", System.currentTimeMillis() + 60_000))

        repository.applyRemotePolicy(
            BackupPolicy(4, listOf(TimeProfile("remote", setOf(2), 600, 660, setOf("com.tencent.wework"))), true)
        )

        assertEquals(4, repository.backupPolicy().version)
        assertEquals("remote", repository.getProfiles().single().id)
        assertEquals(true, repository.backupPolicy().planModeEnabled)
        assertEquals(false, repository.isPolicyDirty())
        assertNotNull(repository.getPlan())
    }
}
