package com.lockai.util

import java.security.SecureRandom

object EmergencyKeyGenerator {
    private const val KEY_LENGTH = 64
    // 包含所有键盘字符，去掉空格。故意包含易混淆字符让输入更痛苦。
    // 大写+小写+数字+特殊符号，总计约86个字符，暴力破解概率 86^-64 ≈ 不可能。
    private const val CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#$%^&*()_+-=[]{}|;':\",./<>?`~"

    private val random = SecureRandom()

    fun generate(): String {
        val sb = StringBuilder(KEY_LENGTH)
        for (i in 0 until KEY_LENGTH) {
            sb.append(CHARS[random.nextInt(CHARS.length)])
        }
        return sb.toString()
    }

    fun verify(input: String, target: String): Boolean {
        return input.length == KEY_LENGTH && input == target
    }

    fun keyLength() = KEY_LENGTH
}
