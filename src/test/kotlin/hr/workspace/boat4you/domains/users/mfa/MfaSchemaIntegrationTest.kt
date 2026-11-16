package hr.workspace.boat4you.domains.users.mfa

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.images.builder.Transferable
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.MountableFile
import java.time.Instant

/**
 * V9_128 (two-step admin sign-in) against a real PostgreSQL 18 (prod: cusma4): the migration, Hibernate
 * `ddl-auto=validate` of the four entities against it (a mismatch would stop the API at start, 22.10.2026), the bytea
 * round trip of a passkey and the cascade when a user row goes. `users` is a one-column stand-in.
 */
@DataJpaTest(properties = ["spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=validate"])
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class MfaSchemaIntegrationTest {
    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres: PostgreSQLContainer<Nothing> =
            PostgreSQLContainer<Nothing>("postgres:18-alpine").apply {
                withDatabaseName("boat4you_db")
                withCopyToContainer(
                    Transferable.of("CREATE TABLE users (id BIGINT PRIMARY KEY); INSERT INTO users VALUES (7), (8);"),
                    "/docker-entrypoint-initdb.d/01_users.sql",
                )
                withCopyFileToContainer(
                    MountableFile.forClasspathResource("db/migration/V9_128__admin_mfa.sql"),
                    "/docker-entrypoint-initdb.d/02_admin_mfa.sql",
                )
            }
    }

    @SpringBootConfiguration
    @EntityScan(basePackageClasses = [UserPasskey::class])
    @EnableJpaRepositories(basePackageClasses = [UserPasskeyRepository::class])
    class OnlyMfa

    @Autowired lateinit var passkeys: UserPasskeyRepository

    @Autowired lateinit var totps: UserTotpRepository

    @Autowired lateinit var recoveryCodes: UserRecoveryCodeRepository

    @Autowired lateinit var devices: UserTrustedDeviceRepository

    @Autowired lateinit var jdbc: JdbcTemplate

    @Test
    fun `the entities match the migration and every table takes and gives back its rows`() {
        val key = ByteArray(77) { (it * 3).toByte() }
        val saved =
            passkeys.save(
                UserPasskey().apply {
                    userId = 7
                    credentialId = "cred-1"
                    attestedCredentialData = key
                    signCount = 3
                    label = "iPhone"
                },
            )
        passkeys.findByCredentialId("cred-1")!!.attestedCredentialData.toList() shouldBe key.toList()
        passkeys.findAllByUserId(7).map { it.id } shouldBe listOf(saved.id)

        totps.save(
            UserTotp().apply {
                userId = 7
                secret = "ABC"
                lastStep = 42
                enabledAt = Instant.now()
            },
        )
        totps.findById(7).get().lastStep shouldBe 42

        recoveryCodes.save(
            UserRecoveryCode().apply {
                userId = 7
                codeHash = "h".repeat(64)
            },
        )
        recoveryCodes.findAllByUserIdAndUsedAtIsNull(7).size shouldBe 1

        devices.save(
            UserTrustedDevice().apply {
                userId = 7
                tokenHash = "d".repeat(64)
                expiresAt = Instant.now().plusSeconds(60)
            },
        )
        devices.findByTokenHash("d".repeat(64))!!.userId shouldBe 7L

        jdbc.update("DELETE FROM users WHERE id = 7")
        passkeys.count() shouldBe 0
        totps.count() shouldBe 0
        recoveryCodes.count() shouldBe 0
        devices.count() shouldBe 0
    }
}
