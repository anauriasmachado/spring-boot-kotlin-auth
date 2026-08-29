package com.example.crash_course.security

import com.example.crash_course.database.model.RefreshToken
import com.example.crash_course.database.model.RefreshTokenRepository
import com.example.crash_course.database.model.User
import com.example.crash_course.database.model.repository.UserRepository
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.Runs
import io.mockk.slot
import io.mockk.verify
import org.bson.types.ObjectId
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.Base64
import java.util.Optional
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AuthServiceTest {

    private val jwtService = JwtService(Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() }))
    private val hashEncoder = HashEncoder()
    private val userRepository = mockk<UserRepository>()
    private val refreshTokenRepository = mockk<RefreshTokenRepository>()

    private val authService = AuthService(jwtService, userRepository, hashEncoder, refreshTokenRepository)

    private val email = "user@example.com"
    private val password = "correct horse battery staple"

    // ---- register ----

    @Test
    fun `register stores the password hashed, never in the clear`() {
        val saved = slot<User>()
        every { userRepository.save(capture(saved)) } answers { saved.captured }

        authService.register(email, password)

        assertEquals(email, saved.captured.email)
        assertNotEquals(password, saved.captured.hashedPassword)
        assertTrue(hashEncoder.matches(password, saved.captured.hashedPassword))
    }

    @Test
    fun `register returns the persisted user`() {
        val persisted = user()
        every { userRepository.save(any()) } returns persisted

        assertEquals(persisted, authService.register(email, password))
    }

    // ---- login ----

    @Test
    fun `login rejects an unknown email`() {
        every { userRepository.findByEmail(email) } returns null

        val error = assertFailsWith<IllegalArgumentException> { authService.login(email, password) }

        assertEquals("Invalid credentials", error.message)
        verify(exactly = 0) { refreshTokenRepository.save(any()) }
    }

    @Test
    fun `login rejects a wrong password`() {
        every { userRepository.findByEmail(email) } returns user()

        val error = assertFailsWith<IllegalArgumentException> { authService.login(email, "wrong password") }

        assertEquals("Invalid credentials", error.message)
        verify(exactly = 0) { refreshTokenRepository.save(any()) }
    }

    @Test
    fun `login issues a token pair bound to the user`() {
        val user = user()
        every { userRepository.findByEmail(email) } returns user
        every { refreshTokenRepository.save(any()) } answers { firstArg() }

        val pair = authService.login(email, password)

        assertTrue(jwtService.validateAccessToken(pair.accessToken))
        assertTrue(jwtService.validateRefreshToken(pair.refreshToken))
        assertEquals(user.id.toHexString(), jwtService.getUserIdFromJWT(pair.accessToken))
        assertEquals(user.id.toHexString(), jwtService.getUserIdFromJWT(pair.refreshToken))
    }

    @Test
    fun `login stores the refresh token hashed and with an expiry`() {
        val user = user()
        val stored = slot<RefreshToken>()
        every { userRepository.findByEmail(email) } returns user
        every { refreshTokenRepository.save(capture(stored)) } answers { stored.captured }

        val pair = authService.login(email, password)

        assertEquals(user.id, stored.captured.userId)
        assertNotEquals(pair.refreshToken, stored.captured.hashedToken)
        val expected = Instant.now().plusMillis(jwtService.refreshTokenValidityMs)
        assertTrue(
            stored.captured.expiresAt.isAfter(expected.minusSeconds(30)) &&
                stored.captured.expiresAt.isBefore(expected.plusSeconds(30)),
            "expiry ${stored.captured.expiresAt} should sit around $expected"
        )
    }

    // ---- refresh ----

    @Test
    fun `refresh rejects a malformed token`() {
        val error = assertFailsWith<IllegalArgumentException> { authService.refresh("not-a-jwt") }

        assertEquals("Invalid refresh token", error.message)
    }

    @Test
    fun `refresh rejects an access token`() {
        val accessToken = jwtService.generateAccessToken(ObjectId.get().toHexString())

        val error = assertFailsWith<IllegalArgumentException> { authService.refresh(accessToken) }

        assertEquals("Invalid refresh token", error.message)
    }

    @Test
    fun `refresh rejects a token whose user is gone`() {
        val user = user()
        every { userRepository.findById(user.id) } returns Optional.empty()

        val error = assertFailsWith<IllegalArgumentException> {
            authService.refresh(jwtService.generateRefreshToken(user.id.toHexString()))
        }

        assertEquals("User not found", error.message)
    }

    @Test
    fun `refresh rejects a token that was never stored`() {
        val user = user()
        every { userRepository.findById(user.id) } returns Optional.of(user)
        every { refreshTokenRepository.findByUserIdAndHashedToken(user.id, any()) } returns null

        val error = assertFailsWith<IllegalArgumentException> {
            authService.refresh(jwtService.generateRefreshToken(user.id.toHexString()))
        }

        assertEquals("Refresh token not found", error.message)
    }

    @Test
    fun `refresh rotates the stored token and returns a fresh pair`() {
        val user = user()
        val refreshToken = jwtService.generateRefreshToken(user.id.toHexString())
        val lookedUpHash = slot<String>()
        val deletedHash = slot<String>()
        val stored = slot<RefreshToken>()
        every { userRepository.findById(user.id) } returns Optional.of(user)
        every {
            refreshTokenRepository.findByUserIdAndHashedToken(user.id, capture(lookedUpHash))
        } answers { RefreshToken(user.id, Instant.now().plusSeconds(60), hashedToken = lookedUpHash.captured) }
        every { refreshTokenRepository.deleteByUserId(user.id, capture(deletedHash)) } just Runs
        every { refreshTokenRepository.save(capture(stored)) } answers { stored.captured }

        val pair = authService.refresh(refreshToken)

        assertEquals(lookedUpHash.captured, deletedHash.captured, "the presented token should be the one revoked")
        assertTrue(jwtService.validateAccessToken(pair.accessToken))
        assertTrue(jwtService.validateRefreshToken(pair.refreshToken))
        assertEquals(user.id.toHexString(), jwtService.getUserIdFromJWT(pair.accessToken))
        verify(exactly = 1) { refreshTokenRepository.deleteByUserId(user.id, any()) }
        verify(exactly = 1) { refreshTokenRepository.save(any()) }
    }

    @Test
    fun `the hash stored at login is the one looked up at refresh`() {
        val user = user()
        val storedAtLogin = slot<RefreshToken>()
        every { userRepository.findByEmail(email) } returns user
        every { userRepository.findById(user.id) } returns Optional.of(user)
        every { refreshTokenRepository.save(capture(storedAtLogin)) } answers { storedAtLogin.captured }
        val lookedUpHash = slot<String>()
        every { refreshTokenRepository.findByUserIdAndHashedToken(user.id, capture(lookedUpHash)) } returns null

        val loginPair = authService.login(email, password)
        val hashFromLogin = storedAtLogin.captured.hashedToken

        assertFailsWith<IllegalArgumentException> { authService.refresh(loginPair.refreshToken) }
        assertEquals(hashFromLogin, lookedUpHash.captured)
    }

    private fun user() = User(
        email = email,
        hashedPassword = hashEncoder.encode(password)!!,
        id = ObjectId.get()
    )
}
