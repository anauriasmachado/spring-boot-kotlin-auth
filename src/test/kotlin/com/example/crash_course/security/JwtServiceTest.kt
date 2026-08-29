package com.example.crash_course.security

import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.bson.types.ObjectId
import org.junit.jupiter.api.Test
import java.util.Base64
import java.util.Date
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JwtServiceTest {

    private val secret = base64Secret(seed = 1)
    private val jwtService = JwtService(secret)
    private val userId = ObjectId.get().toHexString()

    @Test
    fun `access token is accepted as an access token only`() {
        val token = jwtService.generateAccessToken(userId)

        assertTrue(jwtService.validateAccessToken(token))
        assertFalse(jwtService.validateRefreshToken(token))
    }

    @Test
    fun `refresh token is accepted as a refresh token only`() {
        val token = jwtService.generateRefreshToken(userId)

        assertTrue(jwtService.validateRefreshToken(token))
        assertFalse(jwtService.validateAccessToken(token))
    }

    @Test
    fun `user id is read back from a generated token`() {
        val token = jwtService.generateAccessToken(userId)

        assertEquals(userId, jwtService.getUserIdFromJWT(token))
    }

    @Test
    fun `user id is read back from a token carrying the Bearer prefix`() {
        val token = jwtService.generateAccessToken(userId)

        assertEquals(userId, jwtService.getUserIdFromJWT("Bearer $token"))
    }

    @Test
    fun `malformed token is rejected`() {
        assertFalse(jwtService.validateAccessToken("not-a-jwt"))
        assertFalse(jwtService.validateRefreshToken("not-a-jwt"))
        assertFailsWith<IllegalArgumentException> { jwtService.getUserIdFromJWT("not-a-jwt") }
    }

    @Test
    fun `token signed with another secret is rejected`() {
        val foreignToken = JwtService(base64Secret(seed = 2)).generateAccessToken(userId)

        assertFalse(jwtService.validateAccessToken(foreignToken))
        assertFailsWith<IllegalArgumentException> { jwtService.getUserIdFromJWT(foreignToken) }
    }

    @Test
    fun `expired token is rejected`() {
        val expired = expiredToken(type = "access")

        assertFalse(jwtService.validateAccessToken(expired))
        assertFalse(jwtService.validateRefreshToken(expiredToken(type = "refresh")))
    }

    @Test
    fun `token without a type claim is rejected`() {
        val typeless = Jwts.builder()
            .subject(userId)
            .issuedAt(Date())
            .expiration(Date(System.currentTimeMillis() + 60_000))
            .signWith(Keys.hmacShaKeyFor(Base64.getDecoder().decode(secret)), Jwts.SIG.HS256)
            .compact()

        assertFalse(jwtService.validateAccessToken(typeless))
        assertFalse(jwtService.validateRefreshToken(typeless))
    }

    /** HS256 needs at least 32 bytes of key material; [seed] varies the key so tests can cross-check secrets. */
    private fun base64Secret(seed: Int): String =
        Base64.getEncoder().encodeToString(ByteArray(32) { (it + seed).toByte() })

    private fun expiredToken(type: String): String {
        val issuedAt = Date(System.currentTimeMillis() - 120_000)
        return Jwts.builder()
            .subject(userId)
            .claim("type", type)
            .issuedAt(issuedAt)
            .expiration(Date(issuedAt.time + 60_000))
            .signWith(Keys.hmacShaKeyFor(Base64.getDecoder().decode(secret)), Jwts.SIG.HS256)
            .compact()
    }
}
