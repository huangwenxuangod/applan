package com.lockai.util

import java.security.SecureRandom

object EmergencyKeyGenerator {

    // 8位密钥（测试方便，正式版可改回64位）
    private const val KEY_LENGTH = 8

    // 丰富字符集：大小写+数字+特殊符号
    private val CHARS = (
        ('A'..'Z') + ('a'..'z') + ('0'..'9') +
        "!@#$%^&*()_+-=[]{}|;':\",./<>?`~".toList()
    ).toCharArray()

    private val random = SecureRandom()

    fun keyLength(): Int = KEY_LENGTH

    /**
     * 生成随机密钥
     */
    fun generate(): String {
        val sb = StringBuilder(KEY_LENGTH)
        for (i in 0 until KEY_LENGTH) {
            sb.append(CHARS[random.nextInt(CHARS.size)])
        }
        return sb.toString()
    }

    /**
     * 验证密钥
     */
    fun verify(input: String, target: String): Boolean {
        if (input.length != KEY_LENGTH) return false
        return input == target
    }
}
