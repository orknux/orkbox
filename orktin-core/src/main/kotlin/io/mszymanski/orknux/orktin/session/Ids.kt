package io.mszymanski.orknux.orktin.session

import java.security.SecureRandom

object Ids {
    private val random = SecureRandom()
    private const val ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyz"

    fun session() = "ses_" + token(16)
    fun execution() = "exe_" + token(12)

    private fun token(length: Int) = buildString(length) {
        repeat(length) { append(ALPHABET[random.nextInt(ALPHABET.length)]) }
    }
}
