package com.applan.util

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log

object AppPackageResolver {

    private const val TAG = "PkgResolver"

    private val BUILTIN_MAP = mapOf(
        "微信" to "com.tencent.mm",
        "QQ" to "com.tencent.mobileqq",
        "QQ音乐" to "com.tencent.qqmusic",
        "腾讯视频" to "com.tencent.qqlive",
        "企业微信" to "com.tencent.wework",
        "TIM" to "com.tencent.tim",

        "抖音" to "com.ss.android.ugc.aweme",
        "抖音极速版" to "com.ss.android.ugc.aweme.lite",
        "今日头条" to "com.ss.android.article.news",
        "西瓜视频" to "com.ss.android.article.video",
        "飞书" to "com.ss.android.lark",
        "飞书会议" to "com.ss.android.lark.meeting",
        "剪映" to "com.lemon.lv",

        "支付宝" to "com.eg.android.AlipayGphone",
        "淘宝" to "com.taobao.taobao",
        "天猫" to "com.tmall.wireless",
        "钉钉" to "com.alibaba.android.rimet",
        "闲鱼" to "com.taobao.idlefish",
        "优酷" to "com.youku.phone",
        "高德地图" to "com.autonavi.minimap",
        "高德" to "com.autonavi.minimap",
        "饿了么" to "me.ele",
        "盒马" to "com.fresh.freshshop",

        "小红书" to "com.xingin.xhs",
        "哔哩哔哩" to "tv.danmaku.bili",
        "B站" to "tv.danmaku.bili",
        "bilibili" to "tv.danmaku.bili",
        "微博" to "com.sina.weibo",
        "百度" to "com.baidu.searchbox",
        "百度地图" to "com.baidu.BaiduMap",
        "百度网盘" to "com.baidu.netdisk",
        "知乎" to "com.zhihu.android",
        "豆瓣" to "com.douban.frodo",
        "网易云音乐" to "com.netease.cloudmusic",
        "网易新闻" to "com.netease.newsreader.activity",
        "有道词典" to "com.youdao.dict",

        "美团" to "com.sankuai.meituan",
        "美团外卖" to "com.sankuai.meituan.takeoutnew",
        "大众点评" to "com.dianping.android.v9",
        "拼多多" to "com.xunmeng.pinduoduo",
        "京东" to "com.jingdong.app.mall",
        "快手" to "com.smile.gifmaker",
        "快手极速版" to "com.kuaishou.nebula",

        "短信" to "com.android.mms",
        "信息" to "com.google.android.apps.messaging",
        "电话" to "com.android.dialer",
        "通讯录" to "com.android.contacts",
        "相机" to "com.android.camera",
        "相册" to "com.android.gallery3d",
        "设置" to "com.android.settings",
        "浏览器" to "com.android.browser",
        "Chrome" to "com.android.chrome",
        "Edge" to "com.microsoft.emmx",
        "火狐" to "org.mozilla.firefox",
        "Firefox" to "org.mozilla.firefox",
        "Safari" to "com.apple.mobilesafari",

        "王者荣耀" to "com.tencent.tmgp.sgame",
        "和平精英" to "com.tencent.tmgp.pubgmhd",
        "原神" to "com.miHoYo.GenshinImpact",
        "英雄联盟手游" to "com.riotgames.leagueoflegends",
        "开心消消乐" to "com.happyelements.AndroidAnimal",

        "招商银行" to "cmb.pb",
        "工商银行" to "com.icbc",
        "建设银行" to "com.ccb.android",
        "中国银行" to "com.chinamworld.bocmbci",
        "农业银行" to "com.android.bankabc",

        "携程" to "ctrip.android.view",
        "去哪儿" to "com.Qunar",
        "12306" to "com.MobileTicket",
        "滴滴" to "com.sdu.didi.psnger",
        "滴滴出行" to "com.sdu.didi.psnger",

        "WPS" to "cn.wps.moffice_eng",
        "WPS Office" to "cn.wps.moffice_eng",
        "Word" to "com.microsoft.office.word",
        "Excel" to "com.microsoft.office.excel",
        "PowerPoint" to "com.microsoft.office.powerpoint",
        "OneNote" to "com.microsoft.office.onenote",
        "Notion" to "notion.id",
        "印象笔记" to "com.yinxiang",
        "有道云笔记" to "com.youdao.note",
        "备忘录" to "com.android.memo",
        "便签" to "com.android.notes",

        "Zoom" to "us.zoom.videomeetings",
        "腾讯会议" to "com.tencent.wemeet.app",
        "WhatsApp" to "com.whatsapp",
        "Telegram" to "org.telegram.messenger",
        "Signal" to "org.thoughtcrime.securesms",
        "Skype" to "com.skype.raider",

        "Spotify" to "com.spotify.music",
        "Apple Music" to "com.apple.android.music",
        "QQ邮箱" to "com.tencent.androidqqmail",
        "Gmail" to "com.google.android.gm",
        "Outlook" to "com.microsoft.office.outlook",
        "邮箱" to "com.google.android.gm",

        "中国大学MOOC" to "com.netease.edu.ucmooc",
        "学习强国" to "cn.xuexi.android",
        "得到" to "com.luojilab.player",
        "喜马拉雅" to "com.ximalaya.ting.android",
        "番茄小说" to "com.dragon.read",
        "七猫小说" to "com.kmxs.reader",
        "起点读书" to "com.qidian.QDReader",

        "华为应用市场" to "com.huawei.appmarket",
        "小米应用商店" to "com.xiaomi.market",
        "OPPO软件商店" to "com.oppo.market",
        "vivo应用商店" to "com.bbk.appstore",
        "Google Play" to "com.android.vending",

        "Android Studio" to "com.google.android.studio",
        "VS Code" to "com.microsoft.VSCode",
        "Terminal" to "com.termux",
        "Termux" to "com.termux",
    )

    private val REVERSE_MAP: Map<String, String> by lazy {
        BUILTIN_MAP.entries.associate { (name, pkg) -> pkg to name }
    }

    @Volatile
    private var installedAppsCache: Map<String, String>? = null
    @Volatile
    private var cacheTime: Long = 0
    private const val CACHE_TTL = 5 * 60 * 1000L

    fun resolveAppNames(context: Context, appNames: List<String>): Pair<Set<String>, List<String>> {
        val packages = linkedSetOf<String>()
        val unresolved = mutableListOf<String>()

        for (name in appNames) {
            val trimmed = name.trim()
            if (trimmed.isEmpty()) continue
            val pkg = resolveSingleApp(context, trimmed)
            if (pkg != null) {
                packages.add(pkg)
            } else {
                unresolved.add(trimmed)
            }
        }
        return packages to unresolved
    }

    private fun resolveSingleApp(context: Context, name: String): String? {
        BUILTIN_MAP[name]?.let { return it }
        BUILTIN_MAP.entries.find { it.key.equals(name, ignoreCase = true) }?.value?.let { return it }

        if (looksLikePackageName(name)) {
            return name
        }

        ensureCache(context)
        installedAppsCache?.let { cache ->
            cache[name]?.let { return it }
            cache.entries.find {
                it.key.equals(name, ignoreCase = true)
            }?.value?.let { return it }
            cache.entries.find {
                it.key.contains(name, ignoreCase = true) || name.contains(it.key, ignoreCase = true)
            }?.value?.let { return it }
        }

        Log.w(TAG, "Failed to resolve app name: $name")
        return null
    }

    private fun looksLikePackageName(s: String): Boolean {
        if (!s.contains('.')) return false
        val segments = s.split('.')
        if (segments.size < 2) return false
        return segments.all { it.isNotEmpty() && it.all { c -> c.isLetterOrDigit() || c == '_' } }
    }

    fun getAppName(context: Context, packageName: String): String {
        REVERSE_MAP[packageName]?.let { return it }

        return try {
            val pm = context.packageManager
            val info = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(info).toString()
        } catch (_: Exception) {
            packageName
        }
    }

    @Synchronized
    private fun ensureCache(context: Context) {
        val now = System.currentTimeMillis()
        if (installedAppsCache != null && now - cacheTime < CACHE_TTL) return

        try {
            val pm = context.packageManager
            val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            val map = mutableMapOf<String, String>()
            for (app in apps) {
                try {
                    val label = pm.getApplicationLabel(app).toString()
                    if (label.isNotEmpty()) {
                        map[label] = app.packageName
                    }
                } catch (_: Exception) {}
            }
            installedAppsCache = map
            cacheTime = now
            Log.d(TAG, "Cached ${map.size} installed apps")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cache installed apps (permission likely denied), using BUILTIN_MAP only", e)
            installedAppsCache = emptyMap()
            cacheTime = now
        }
    }

    fun invalidateCache() {
        installedAppsCache = null
        cacheTime = 0
    }
}
