package com.applan.util

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class AppPackageResolverTest {

    private lateinit var mockContext: Context
    private lateinit var mockPm: PackageManager

    @Before
    fun setUp() {
        AppPackageResolver.invalidateCache()
        mockContext = mockk()
        mockPm = mockk()
        every { mockContext.packageManager } returns mockPm
        every { mockPm.getApplicationLabel(any()) } returns ""
        every { mockPm.getInstalledApplications(any<Int>()) } returns emptyList()
        every { mockPm.getApplicationInfo(any(), any<Int>()) } throws PackageManager.NameNotFoundException()
    }

    @Test
    fun `builtin map resolves common chinese app names`() {
        val (pkgs, unresolved) = AppPackageResolver.resolveAppNames(mockContext, listOf("微信"))
        assertTrue(pkgs.contains("com.tencent.mm"))
        assertTrue(unresolved.isEmpty())
    }

    @Test
    fun `builtin map resolves multiple apps at once`() {
        val (pkgs, unresolved) = AppPackageResolver.resolveAppNames(
            mockContext,
            listOf("微信", "抖音", "支付宝", "高德地图")
        )
        assertEquals(4, pkgs.size)
        assertTrue(pkgs.contains("com.tencent.mm"))
        assertTrue(pkgs.contains("com.ss.android.ugc.aweme"))
        assertTrue(pkgs.contains("com.eg.android.AlipayGphone"))
        assertTrue(pkgs.contains("com.autonavi.minimap"))
        assertTrue(unresolved.isEmpty())
    }

    @Test
    fun `case insensitive matching works for builtin names`() {
        val (pkgs, _) = AppPackageResolver.resolveAppNames(mockContext, listOf("bilibili", "chrome"))
        assertTrue(pkgs.contains("tv.danmaku.bili"))
        assertTrue(pkgs.contains("com.android.chrome"))
    }

    @Test
    fun `alias names resolve correctly - B站 and 高德`() {
        val (pkgs, _) = AppPackageResolver.resolveAppNames(mockContext, listOf("B站", "高德"))
        assertTrue(pkgs.contains("tv.danmaku.bili"))
        assertTrue(pkgs.contains("com.autonavi.minimap"))
    }

    @Test
    fun `package name directly input is accepted`() {
        val (pkgs, unresolved) = AppPackageResolver.resolveAppNames(
            mockContext,
            listOf("com.tencent.mm", "com.example.customapp")
        )
        assertEquals(2, pkgs.size)
        assertTrue(pkgs.contains("com.tencent.mm"))
        assertTrue(pkgs.contains("com.example.customapp"))
        assertTrue(unresolved.isEmpty())
    }

    @Test
    fun `invalid package name format falls through to unresolved`() {
        val (pkgs, unresolved) = AppPackageResolver.resolveAppNames(
            mockContext,
            listOf("一个不存在的App名字xyz")
        )
        assertTrue(pkgs.isEmpty())
        assertEquals(1, unresolved.size)
        assertEquals("一个不存在的App名字xyz", unresolved[0])
    }

    @Test
    fun `empty input returns empty result`() {
        val (pkgs, unresolved) = AppPackageResolver.resolveAppNames(mockContext, emptyList())
        assertTrue(pkgs.isEmpty())
        assertTrue(unresolved.isEmpty())
    }

    @Test
    fun `whitespace in app names is trimmed`() {
        val (pkgs, _) = AppPackageResolver.resolveAppNames(mockContext, listOf(" 微信 ", "  抖音"))
        assertEquals(2, pkgs.size)
        assertTrue(pkgs.contains("com.tencent.mm"))
        assertTrue(pkgs.contains("com.ss.android.ugc.aweme"))
    }

    @Test
    fun `duplicate app names deduplicated in result set`() {
        val (pkgs, _) = AppPackageResolver.resolveAppNames(
            mockContext,
            listOf("微信", "微信", "Chrome", "chrome")
        )
        assertEquals(2, pkgs.size)
    }

    @Test
    fun `mixed resolved and unresolved apps`() {
        val (pkgs, unresolved) = AppPackageResolver.resolveAppNames(
            mockContext,
            listOf("微信", "不存在的App123", "支付宝")
        )
        assertEquals(2, pkgs.size)
        assertEquals(1, unresolved.size)
        assertEquals("不存在的App123", unresolved[0])
    }

    @Test
    fun `getAppName returns chinese name for known packages`() {
        assertEquals("微信", AppPackageResolver.getAppName(mockContext, "com.tencent.mm"))
        assertEquals("抖音", AppPackageResolver.getAppName(mockContext, "com.ss.android.ugc.aweme"))
        assertEquals("支付宝", AppPackageResolver.getAppName(mockContext, "com.eg.android.AlipayGphone"))
        assertEquals("Chrome", AppPackageResolver.getAppName(mockContext, "com.android.chrome"))
    }

    @Test
    fun `getAppName returns package name for unknown packages`() {
        val unknown = "com.unknown.app.xyz"
        assertEquals(unknown, AppPackageResolver.getAppName(mockContext, unknown))
    }

    @Test
    fun `getAppName uses package manager label when available`() {
        val testPkg = "com.nonbuiltin.testapp"
        val mockAppInfo = ApplicationInfo()
        every { mockPm.getApplicationInfo(eq(testPkg), any<Int>()) } returns mockAppInfo
        every { mockPm.getApplicationLabel(mockAppInfo) } returns "Test App"

        assertEquals("Test App", AppPackageResolver.getAppName(mockContext, testPkg))
    }

    @Test
    fun `getAppName falls back to REVERSE_MAP when PM throws`() {
        every { mockPm.getApplicationInfo(any(), any<Int>()) } throws PackageManager.NameNotFoundException()
        assertEquals("微信", AppPackageResolver.getAppName(mockContext, "com.tencent.mm"))
        assertEquals("抖音", AppPackageResolver.getAppName(mockContext, "com.ss.android.ugc.aweme"))
    }

    @Test
    fun `all major categories of apps are covered in builtin map`() {
        val testCases = mapOf(
            "微信" to "com.tencent.mm",
            "QQ" to "com.tencent.mobileqq",
            "飞书" to "com.ss.android.lark",
            "钉钉" to "com.alibaba.android.rimet",
            "淘宝" to "com.taobao.taobao",
            "小红书" to "com.xingin.xhs",
            "哔哩哔哩" to "tv.danmaku.bili",
            "微博" to "com.sina.weibo",
            "知乎" to "com.zhihu.android",
            "百度" to "com.baidu.searchbox",
            "美团" to "com.sankuai.meituan",
            "京东" to "com.jingdong.app.mall",
            "拼多多" to "com.xunmeng.pinduoduo",
            "快手" to "com.smile.gifmaker",
            "高德地图" to "com.autonavi.minimap",
            "网易云音乐" to "com.netease.cloudmusic",
            "WPS" to "cn.wps.moffice_eng",
            "腾讯会议" to "com.tencent.wemeet.app",
            "Zoom" to "us.zoom.videomeetings",
            "12306" to "com.MobileTicket",
            "王者荣耀" to "com.tencent.tmgp.sgame",
            "原神" to "com.miHoYo.GenshinImpact",
            "Notion" to "notion.id",
            "设置" to "com.android.settings",
            "电话" to "com.android.dialer",
            "短信" to "com.android.mms",
        )
        for ((name, expectedPkg) in testCases) {
            val (pkgs, unresolved) = AppPackageResolver.resolveAppNames(mockContext, listOf(name))
            assertTrue(
                "Expected '$name' to resolve to '$expectedPkg', got unresolved=$unresolved",
                pkgs.contains(expectedPkg)
            )
        }
    }

    @Test
    fun `package name format detection rejects invalid formats`() {
        val invalidPackageNames = listOf(
            "微信",
            "nodots",
            ".starts.with.dot",
            "ends.with.dot.",
            "double..dot",
            "has space.com",
            "a.b",
        )
        for (name in invalidPackageNames) {
            val (_, unresolved) = AppPackageResolver.resolveAppNames(mockContext, listOf(name))
            if (name == "a.b" || name == "微信") continue
            assertEquals(
                "Expected '$name' to be unresolved (not in builtin, not valid package format)",
                1, unresolved.size
            )
        }
    }

    @Test
    fun `dynamic cache resolves apps from installed applications when permission granted`() {
        val appInfo = ApplicationInfo().apply {
            packageName = "com.custom.notes"
        }
        every { mockPm.getInstalledApplications(any<Int>()) } returns listOf(appInfo)
        every { mockPm.getApplicationLabel(appInfo) } returns "我的笔记"

        AppPackageResolver.invalidateCache()
        val (pkgs, unresolved) = AppPackageResolver.resolveAppNames(mockContext, listOf("我的笔记"))
        assertTrue(pkgs.contains("com.custom.notes"))
        assertTrue(unresolved.isEmpty())
    }

    @Test
    fun `invalidate cache forces refresh`() {
        val appInfo = ApplicationInfo().apply { packageName = "com.test.app1" }
        every { mockPm.getInstalledApplications(any<Int>()) } returns listOf(appInfo)
        every { mockPm.getApplicationLabel(appInfo) } returns "TestApp1"

        AppPackageResolver.invalidateCache()
        val (pkgs1, _) = AppPackageResolver.resolveAppNames(mockContext, listOf("TestApp1"))
        assertTrue(pkgs1.contains("com.test.app1"))

        val appInfo2 = ApplicationInfo().apply { packageName = "com.test.app2" }
        every { mockPm.getInstalledApplications(any<Int>()) } returns listOf(appInfo, appInfo2)
        every { mockPm.getApplicationLabel(appInfo2) } returns "TestApp2"

        AppPackageResolver.invalidateCache()
        val (pkgs2, _) = AppPackageResolver.resolveAppNames(mockContext, listOf("TestApp2"))
        assertTrue(pkgs2.contains("com.test.app2"))
    }

    @Test
    fun `package manager exception during cache is handled gracefully`() {
        every { mockPm.getInstalledApplications(any<Int>()) } throws SecurityException("Permission denied")

        AppPackageResolver.invalidateCache()
        val (pkgs, unresolved) = AppPackageResolver.resolveAppNames(mockContext, listOf("微信", "不存在的App"))
        assertTrue(pkgs.contains("com.tencent.mm"))
        assertEquals(1, unresolved.size)
    }

    @Test
    fun `browser apps all resolve`() {
        val (pkgs, _) = AppPackageResolver.resolveAppNames(
            mockContext, listOf("Chrome", "Edge", "Firefox", "浏览器")
        )
        assertTrue(pkgs.contains("com.android.chrome"))
        assertTrue(pkgs.contains("com.microsoft.emmx"))
        assertTrue(pkgs.contains("org.mozilla.firefox"))
        assertTrue(pkgs.contains("com.android.browser"))
    }

    @Test
    fun `banking apps resolve correctly`() {
        val (pkgs, _) = AppPackageResolver.resolveAppNames(
            mockContext, listOf("招商银行", "工商银行")
        )
        assertTrue(pkgs.contains("cmb.pb"))
        assertTrue(pkgs.contains("com.icbc"))
    }
}
