package com.example.crash_course.security

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.stereotype.Component

@Component
class HashEncoder {

    private val bCryptPasswordEncoder = BCryptPasswordEncoder()

    fun encode(input: String): String? {
        return bCryptPasswordEncoder.encode(input)
    }

    fun matches(input: String, encoded: String): Boolean {
        return bCryptPasswordEncoder.matches(input, encoded)
    }
}