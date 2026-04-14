package hr.workspace.boat4you.security.services

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service

@Service
class PasswordService(
    private val passwordEncoder: PasswordEncoder = BCryptPasswordEncoder(),
) {
    fun encodePassword(rawPassword: CharSequence): String = passwordEncoder.encode(rawPassword)

    fun doesMatch(
        rawPassword: CharSequence,
        encodedPassword: String,
    ): Boolean = passwordEncoder.matches(rawPassword, encodedPassword)
}
