package com.applan.util

import java.security.SecureRandom

object EmergencyKeyGenerator {

    // 16位密钥（有挑战性但不会太痛苦，正式版可加长）
    private const val KEY_LENGTH = 16

    // 丰富字符集：大小写+数字+特殊符号
    private val CHARS = (
        ('A'..'Z') + ('a'..'z') + ('0'..'9') +
        "!@#$%^&*()_+-=[]{}|;':\",./<>?`~".toList()
    ).toCharArray()

    private val random = SecureRandom()

    fun keyLength(): Int = KEY_LENGTH

    fun generate(): String {
        val sb = StringBuilder(KEY_LENGTH)
        for (i in 0 until KEY_LENGTH) {
            sb.append(CHARS[random.nextInt(CHARS.size)])
        }
        return sb.toString()
    }

    fun verify(input: String, target: String): Boolean {
        if (input.length != KEY_LENGTH) return false
        return input == target
    }
}
