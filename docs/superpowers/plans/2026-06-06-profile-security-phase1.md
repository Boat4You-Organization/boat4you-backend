# Profile "Sign-in & security" (Phase 1) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a "Sign-in & security" area to `/my-profile` — connected accounts + set-a-password for social users, and a device-aware active-sessions list with per-device sign-out.

**Architecture:** Backend adds session metadata (`user_agent/ip/last_used_at/session_group`) to the existing `tokens` table and a `password_set` flag to `users`; new authed `/users/me/*` endpoints list/revoke sessions and set a password for social accounts. Web adds a security card (connected accounts + set-password modal) and a device list, wired into the existing per-section Profile editor.

**Tech Stack:** Kotlin/Spring + Flyway + JPA (backend, cusma2+cusma3); Next.js 16/React 19/MUI/next-intl (web, cusma1). Spec: `docs/superpowers/specs/2026-06-06-profile-security-phase1-design.md`.

**Verification model (this project):** No unit-test suite runs in deploy (`bootJar -x test`). Each backend task verifies with `./gradlew compileKotlin ktlintMainSourceSetCheck` (its own files must be ktlint-clean; 557 pre-existing violations elsewhere are `ignoreFailures=true` and out of scope). Web tasks verify with `npx tsc --noEmit` + `npx eslint <files>`. End-to-end = curl/journal (backend) and browser (web), as in the social-login deploy.

**Gotchas to respect (from this session):** KDoc must not contain `/**` (e.g. `/auth/oauth/**`) — opens a nested comment, breaks ktlint/compile. Web `index.ts` re-export must be `import X; export default X;` (NOT `export { default } from` — `no-restricted-exports`). useEffect with a cleanup return must make all returns consistent (`return undefined`). New next-intl `common` keys need a cast `(t as unknown as (k:string)=>string)('key')`. App DB user = `boat4you_owner`. Backend deploy cusma2 FIRST then cusma3; web build-on-server cusma1, df-check + NO `.next.bak`.

---

## File Structure

**Backend (create):**
- `src/main/resources/db/migration/V1_97__session_metadata_and_password_set.sql` — schema.
- `.../security/services/ClientIpResolver.kt` — X-Forwarded-For/remoteAddr → client IP (reused by session capture).
- `.../security/services/SessionService.kt` — list/revoke session groups.
- `.../security/controllers/SessionController.kt` — `GET/DELETE/POST /users/me/sessions*`.
- `.../security/controllers/SetPasswordController.kt` — `POST /users/me/set-password`.
- `.../security/dto/SessionDto.kt` — response DTO + request bodies.

**Backend (modify):**
- `.../security/jpa/TokenEntity.kt` — add userAgent/ipAddress/lastUsedAt/sessionGroup.
- `.../domains/users/jpa/UserEntity.kt` — add passwordSet.
- `.../security/jpa/TokenRepository.kt` — session queries.
- `.../security/services/UserAuthService.kt` — capture metadata + sessionGroup in saveUserToken; carry group on refresh.
- `.../security/services/OAuthService.kt` — `createGoogleAccount` sets passwordSet=false.
- `.../security/JwtAuthenticationFilter.kt` — throttled last_used_at update.
- `.../domains/users/services/UserTranslators.kt` (or wherever `UserEntity.toUserModel` lives) — expose provider + passwordSet.
- OpenAPI `User` model — add `provider` + `passwordSet` (if `/auth/me` is spec-bound; else extend the mapper only).

**Web (create):**
- `src/components/Profile/SecuritySection/SecuritySection.tsx` (+ index.ts) — connected accounts + password row.
- `src/components/Profile/SecuritySection/SetPasswordModal.tsx` — set-password modal.
- `src/components/Profile/SecuritySection/DeviceList.tsx` — sessions list + revoke.
- `src/utils/static/parseUserAgent.ts` — UA string → "Chrome · macOS".

**Web (modify):**
- `src/models/user.model.ts` — add provider?/passwordSet.
- `src/actions/auth.actions.ts` — getSessions/revokeSession/revokeOtherSessions/setPassword.
- `src/views/Profile/Profile.tsx` — render SecuritySection; i18n the hardcoded labels.
- `messages/*/common.json` (9) — new keys.

---

## Task 1: Migration — session metadata + password_set

**Files:** Create `src/main/resources/db/migration/V1_97__session_metadata_and_password_set.sql`

- [ ] **Step 1: Write the migration**

```sql
-- Phase 1 security. Device-aware sessions: tag each token row with the device
-- (user_agent/ip), when it was last seen, and a session_group UUID shared by the
-- access+refresh pair minted in one login (so the UI shows one entry per device).
-- All nullable — legacy tokens stay null and age out within the 3-day refresh TTL.
ALTER TABLE tokens ADD COLUMN IF NOT EXISTS user_agent VARCHAR(512);
ALTER TABLE tokens ADD COLUMN IF NOT EXISTS ip_address VARCHAR(64);
ALTER TABLE tokens ADD COLUMN IF NOT EXISTS last_used_at TIMESTAMP;
ALTER TABLE tokens ADD COLUMN IF NOT EXISTS session_group VARCHAR(36);
CREATE INDEX IF NOT EXISTS idx_tokens_session_group ON tokens (session_group);

-- "Has the user chosen their own password?" Existing email accounts have; social
-- accounts created by the Google-login launch got a random one they never set.
ALTER TABLE users ADD COLUMN IF NOT EXISTS password_set BOOLEAN NOT NULL DEFAULT true;
UPDATE users SET password_set = (provider IS NULL);
```

- [ ] **Step 2: Verify Flyway accepts it locally (dry compile — migration is applied at startup on deploy)**

Run: `cd boat4you-ws-main && ./gradlew compileKotlin`
Expected: `BUILD SUCCESSFUL` (SQL isn't compiled, but this confirms nothing else broke). The migration applies on the next backend startup (cusma2 first).

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/db/migration/V1_97__session_metadata_and_password_set.sql
git commit -m "feat(security): V1_97 — tokens session metadata + users.password_set"
```

---

## Task 2: Entity fields — TokenEntity + UserEntity

**Files:** Modify `.../security/jpa/TokenEntity.kt`, `.../domains/users/jpa/UserEntity.kt`

- [ ] **Step 1: Add columns to TokenEntity** (after the existing `expiresAt` field, before the `@ManyToOne user`)

```kotlin
    @Column(name = "user_agent", columnDefinition = "VARCHAR(512)", nullable = true)
    var userAgent: String? = null

    @Column(name = "ip_address", columnDefinition = "VARCHAR(64)", nullable = true)
    var ipAddress: String? = null

    @Column(name = "last_used_at", columnDefinition = "TIMESTAMP", nullable = true)
    var lastUsedAt: Instant? = null

    /** UUID shared by the access+refresh pair from one login → one device entry. */
    @Column(name = "session_group", columnDefinition = "VARCHAR(36)", nullable = true)
    var sessionGroup: String? = null
```

- [ ] **Step 2: Add passwordSet to UserEntity** (near the `password` field)

```kotlin
    /**
     * True once the user has chosen their own password. False for social-only
     * accounts (created with a random hash) so the UI offers "Set a password"
     * instead of "Change password". Backfilled by V1_97 as (provider IS NULL).
     */
    @Column(name = "password_set", columnDefinition = "BOOLEAN", nullable = false)
    var passwordSet: Boolean = true
```

- [ ] **Step 3: Verify**

Run: `./gradlew compileKotlin ktlintMainSourceSetCheck 2>&1 | grep -E "TokenEntity|UserEntity|BUILD"`
Expected: no `TokenEntity`/`UserEntity` ktlint lines; `compileKotlin` succeeds.

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/hr/workspace/boat4you/security/jpa/TokenEntity.kt src/main/kotlin/hr/workspace/boat4you/domains/users/jpa/UserEntity.kt
git commit -m "feat(security): TokenEntity session metadata + UserEntity.passwordSet"
```

---

## Task 3: ClientIpResolver + capture metadata at token creation

**Files:** Create `.../security/services/ClientIpResolver.kt`; Modify `.../security/services/UserAuthService.kt`

- [ ] **Step 1: ClientIpResolver** (reuse the same X-Forwarded-For logic the rate-limiter uses — left-most XFF entry else remoteAddr; trim)

```kotlin
package hr.workspace.boat4you.security.services

import jakarta.servlet.http.HttpServletRequest
import org.springframework.stereotype.Component

@Component
class ClientIpResolver {
    fun resolve(request: HttpServletRequest): String {
        val xff = request.getHeader("X-Forwarded-For")
        if (!xff.isNullOrBlank()) {
            return xff.split(",").first().trim().take(64)
        }
        return (request.remoteAddr ?: "").take(64)
    }
}
```

- [ ] **Step 2: Thread sessionGroup + request into `saveUserToken`** (Modify `UserAuthService`)

Inject `clientIpResolver: ClientIpResolver` into the constructor. Change `saveUserToken` to:

```kotlin
    private fun saveUserToken(
        dbUser: UserEntity,
        jwtToken: String,
        expiresAt: Date,
        httpRequest: HttpServletRequest,
        sessionGroup: String,
    ) {
        val now = Instant.now()
        val token =
            TokenEntity().apply {
                value = jwtToken
                user = dbUser
                this.expiresAt = expiresAt.toInstant()
                this.sessionGroup = sessionGroup
                userAgent = httpRequest.getHeader("User-Agent")?.take(512)
                ipAddress = clientIpResolver.resolve(httpRequest)
                lastUsedAt = now
            }
        tokenRepository.save(token)
    }
```

- [ ] **Step 3: Generate one sessionGroup per login/registration** (Modify the two issuance sites)

In `login` and `issueTokenAtRegistration`, replace the two `saveUserToken(...)` calls with:

```kotlin
        val sessionGroup = java.util.UUID.randomUUID().toString()
        saveUserToken(dbUser, jwtToken.first, jwtToken.second, httpRequest, sessionGroup)
        saveUserToken(dbUser, refreshToken.first, refreshToken.second, httpRequest, sessionGroup)
```

- [ ] **Step 4: Refresh carries the session forward** (Modify `refreshToken`)

When `refreshToken` mints a new access token from a presented refresh token, reuse that refresh token's `sessionGroup` (so the device entry persists) instead of a new UUID. Load the presented refresh `TokenEntity` (already done for validation), read `.sessionGroup ?: UUID.randomUUID().toString()`, and pass it to `saveUserToken` for the new access token.

- [ ] **Step 5: Verify**

Run: `./gradlew compileKotlin ktlintMainSourceSetCheck 2>&1 | grep -E "ClientIpResolver|UserAuthService|BUILD"`
Expected: no ktlint lines for these files; build succeeds.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/hr/workspace/boat4you/security/services/ClientIpResolver.kt src/main/kotlin/hr/workspace/boat4you/security/services/UserAuthService.kt
git commit -m "feat(security): capture user-agent/ip/session_group at token issuance"
```

---

## Task 4: Throttled last_used_at update in the auth filter

**Files:** Modify `.../security/JwtAuthenticationFilter.kt`

- [ ] **Step 1: Add a 5-minute throttled touch** where the filter already loads the token row for the revocation check. After confirming the token is valid (not revoked/expired):

```kotlin
        val fiveMinAgo = Instant.now().minusSeconds(300)
        if (storedToken.lastUsedAt == null || storedToken.lastUsedAt!!.isBefore(fiveMinAgo)) {
            storedToken.lastUsedAt = Instant.now()
            tokenRepository.save(storedToken)
        }
```

Place it so it only runs on the success path (valid token). If the filter currently does NOT load the `TokenEntity` (only decodes the JWT), add a `tokenRepository.findByValue(jwt)` there guarded by the existing validity checks — confirm against the current filter body before writing.

- [ ] **Step 2: Verify** — `./gradlew compileKotlin ktlintMainSourceSetCheck 2>&1 | grep -E "JwtAuthenticationFilter|BUILD"` → clean + build OK.

- [ ] **Step 3: Commit** — `git commit -am "feat(security): throttled last_used_at on authenticated requests"`

---

## Task 5: SessionService + SessionController + queries

**Files:** Create `.../security/dto/SessionDto.kt`, `.../security/services/SessionService.kt`, `.../security/controllers/SessionController.kt`; Modify `.../security/jpa/TokenRepository.kt`

- [ ] **Step 1: TokenRepository — valid tokens with a session_group for a user**

```kotlin
    @Query(
        """
        SELECT t FROM TokenEntity t
        WHERE t.user.id = :userId AND t.sessionGroup IS NOT NULL
          AND t.isRevoked = false AND t.isExpired = false
          AND (t.expiresAt IS NULL OR t.expiresAt > CURRENT_TIMESTAMP)
        """,
    )
    fun findActiveSessionTokens(userId: Long): List<TokenEntity>
```

- [ ] **Step 2: SessionDto**

```kotlin
package hr.workspace.boat4you.security.dto

import java.time.Instant

data class SessionDto(
    val sessionGroup: String,
    val userAgent: String?,
    val ipAddress: String?,
    val createdAt: Instant?,
    val lastUsedAt: Instant?,
    val current: Boolean,
)

data class SetPasswordBody(val newPassword: String)
```

(`createdAt` comes from `AbstractEntity` — confirm the getter name; use it.)

- [ ] **Step 3: SessionService** — group by `sessionGroup`, pick the most-recently-used token as the representative, mark `current` by the presented token's group.

```kotlin
package hr.workspace.boat4you.security.services

import hr.workspace.boat4you.security.dto.SessionDto
import hr.workspace.boat4you.security.jpa.TokenRepository
import hr.workspace.boat4you.security.jpa.TokenService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class SessionService(
    private val tokenRepository: TokenRepository,
    private val tokenService: TokenService,
) {
    @Transactional(readOnly = true)
    fun listSessions(userId: Long, currentTokenValue: String?): List<SessionDto> {
        val currentGroup = currentTokenValue?.let { tokenRepository.findByValue(it)?.sessionGroup }
        return tokenRepository.findActiveSessionTokens(userId)
            .groupBy { it.sessionGroup!! }
            .map { (group, tokens) ->
                val rep = tokens.maxByOrNull { it.lastUsedAt ?: it.createdAt }!!
                SessionDto(
                    sessionGroup = group,
                    userAgent = rep.userAgent,
                    ipAddress = rep.ipAddress,
                    createdAt = rep.createdAt,
                    lastUsedAt = rep.lastUsedAt,
                    current = group == currentGroup,
                )
            }
            .sortedByDescending { it.lastUsedAt ?: it.createdAt }
    }

    @Transactional
    fun revokeSession(userId: Long, sessionGroup: String) {
        tokenRepository.findActiveSessionTokens(userId)
            .filter { it.sessionGroup == sessionGroup }
            .forEach { it.isRevoked = true; tokenRepository.save(it) }
    }

    @Transactional
    fun revokeOtherSessions(userId: Long, currentTokenValue: String?) {
        val currentGroup = currentTokenValue?.let { tokenRepository.findByValue(it)?.sessionGroup }
        tokenRepository.findActiveSessionTokens(userId)
            .filter { it.sessionGroup != currentGroup }
            .forEach { it.isRevoked = true; tokenRepository.save(it) }
    }
}
```

(Confirm `AbstractEntity.createdAt` exists/type; if the property differs, adjust `rep.createdAt`.)

- [ ] **Step 4: SessionController** — read the bearer token from the request to resolve "current".

```kotlin
package hr.workspace.boat4you.security.controllers

import hr.workspace.boat4you.security.dto.SessionDto
import hr.workspace.boat4you.security.getAuthenticatedUserId
import hr.workspace.boat4you.security.services.SessionService
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpHeaders.AUTHORIZATION
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class SessionController(
    private val sessionService: SessionService,
    private val httpRequest: HttpServletRequest,
) {
    private fun currentToken(): String? =
        httpRequest.getHeader(AUTHORIZATION)?.removePrefix("Bearer")?.trim()

    @PreAuthorize("isAuthenticated()")
    @GetMapping("/users/me/sessions")
    fun listSessions(): List<SessionDto> =
        sessionService.listSessions(getAuthenticatedUserId(), currentToken())

    @PreAuthorize("isAuthenticated()")
    @DeleteMapping("/users/me/sessions/{sessionGroup}")
    fun revokeSession(@PathVariable sessionGroup: String) =
        sessionService.revokeSession(getAuthenticatedUserId(), sessionGroup)

    @PreAuthorize("isAuthenticated()")
    @PostMapping("/users/me/sessions/revoke-others")
    fun revokeOthers() =
        sessionService.revokeOtherSessions(getAuthenticatedUserId(), currentToken())
}
```

(Confirm `getAuthenticatedUserId()` import path — it's used elsewhere in `security/`. These routes are NOT under `/public/**` or `/auth/**`, so the default `.authenticated()` rule already protects them; no SecurityConfiguration change needed.)

- [ ] **Step 5: Verify** — `./gradlew compileKotlin ktlintMainSourceSetCheck 2>&1 | grep -E "Session|TokenRepository|BUILD"` → clean + build OK.

- [ ] **Step 6: Commit** — `git add` the 4 files; `git commit -m "feat(security): list + revoke active sessions endpoints"`.

---

## Task 6: Set-password endpoint + passwordSet wiring + expose on /auth/me

**Files:** Create `.../security/controllers/SetPasswordController.kt`; Modify `OAuthService.kt`, the `UserEntity.toUserModel` mapper, OpenAPI `User` model.

- [ ] **Step 1: OAuthService.createGoogleAccount sets passwordSet=false** — in the `dbUser.apply { ... }` block add `passwordSet = false`.

- [ ] **Step 2: Set-password service method** (add to `UserAuthService`, reusing `passwordService` + `PasswordPolicy` + `tokenService`):

```kotlin
    @Transactional
    fun setInitialPassword(userId: Long, newPassword: String) {
        val dbUser = userRepository.findById(userId).getOrElse { throw UserDoesNotExistException() }
        if (dbUser.passwordSet) {
            throw ParameterValidationException(mapOf("password" to "Password already set; use change-password"))
        }
        PasswordPolicy.validate(newPassword)
        dbUser.password = passwordService.encodePassword(newPassword)
        dbUser.passwordSet = true
        userRepository.save(dbUser)
        tokenService.revokeAllUserTokens(userId) // force re-auth everywhere after first password set
    }
```

(Note: this revokes ALL sessions incl. current — acceptable for a first password set; the client re-logs in. If you prefer to keep the current session, switch to a "revoke others" variant. Decision: full revoke for simplicity/security.)

- [ ] **Step 3: SetPasswordController**

```kotlin
package hr.workspace.boat4you.security.controllers

import hr.workspace.boat4you.security.dto.SetPasswordBody
import hr.workspace.boat4you.security.getAuthenticatedUserId
import hr.workspace.boat4you.security.services.UserAuthService
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

@RestController
class SetPasswordController(
    private val userAuthService: UserAuthService,
) {
    @PreAuthorize("isAuthenticated()")
    @PostMapping("/users/me/set-password")
    fun setPassword(@RequestBody body: SetPasswordBody) =
        userAuthService.setInitialPassword(getAuthenticatedUserId(), body.newPassword)
}
```

- [ ] **Step 4: Expose `provider` + `passwordSet` on the user model** — in `UserEntity.toUserModel()` set `provider = this.provider` and `passwordSet = this.passwordSet`. Add both fields to the OpenAPI `User` schema (`provider: string nullable`, `passwordSet: boolean`) if `/auth/me` returns the generated `User`; regenerate models (`./gradlew compileKotlin` triggers the swagger codegen). If the mapper builds `User(...)` positionally, add the two args.

- [ ] **Step 5: Verify** — `./gradlew compileKotlin ktlintMainSourceSetCheck 2>&1 | grep -E "SetPassword|OAuthService|UserTranslators|BUILD"` → clean + build OK.

- [ ] **Step 6: Commit** — `git commit -am "feat(security): set-password for social accounts + expose provider/passwordSet"`

---

## Task 7: Backend deploy + smoke

- [ ] **Step 1: bootJar** — `export JAVA_HOME=$(/usr/libexec/java_home -v 21); ./gradlew bootJar` → `build/libs/boat4you-0.0.1-SNAPSHOT.jar`.
- [ ] **Step 2: scp to cusma2+cusma3** as `webservice_new.jar` (recipe: `reference_backend_deploy`).
- [ ] **Step 3: swap+restart cusma2 FIRST** (`echo 'uSMA!1cu' | sudo -S -p '' systemctl restart boat4you.service`), confirm journal `now at version v1.97` + `Started Boat4you`; then cusma3.
- [ ] **Step 4: Smoke** (need a real bearer token — log in as a test user first):
  - `GET https://api.boat4you.com/users/me/sessions` with `Authorization: Bearer <token>` → 200 + a JSON array containing the current session (`current: true`).
  - `POST /users/me/set-password` as a password-set user → 4xx with the "already set" message.

---

## Task 8: Web — UserModel + server actions

**Files:** Modify `src/models/user.model.ts`, `src/actions/auth.actions.ts`

- [ ] **Step 1: UserModel** — add `provider?: string | null;` and `passwordSet?: boolean;`.

- [ ] **Step 2: Session + set-password actions** (append to `auth.actions.ts`, using `authFetch` like the other `/users/me/*` actions; types reuse `PayloadResponse`):

```ts
export type SessionInfo = {
  sessionGroup: string;
  userAgent?: string | null;
  ipAddress?: string | null;
  createdAt?: string | null;
  lastUsedAt?: string | null;
  current: boolean;
};

export async function getSessions(): Promise<SessionInfo[]> {
  try {
    const res = await authFetch(`${process.env.NEXT_PUBLIC_BOAT_WS_API_URL}/users/me/sessions`, { method: 'GET' });
    if (!res.ok) return [];
    return await res.json();
  } catch {
    return [];
  }
}

export async function revokeSession(sessionGroup: string): Promise<{ success: boolean }> {
  try {
    const res = await authFetch(
      `${process.env.NEXT_PUBLIC_BOAT_WS_API_URL}/users/me/sessions/${encodeURIComponent(sessionGroup)}`,
      { method: 'DELETE' },
    );
    return { success: res.ok };
  } catch {
    return { success: false };
  }
}

export async function revokeOtherSessions(): Promise<{ success: boolean }> {
  try {
    const res = await authFetch(`${process.env.NEXT_PUBLIC_BOAT_WS_API_URL}/users/me/sessions/revoke-others`, {
      method: 'POST',
    });
    return { success: res.ok };
  } catch {
    return { success: false };
  }
}

export async function setInitialPassword(newPassword: string): Promise<{ success: boolean; message?: string }> {
  try {
    const res = await authFetch(`${process.env.NEXT_PUBLIC_BOAT_WS_API_URL}/users/me/set-password`, {
      method: 'POST',
      body: JSON.stringify({ newPassword }),
    });
    if (!res.ok) {
      let message = 'Could not set password';
      try { const b = await res.json(); message = b?.message ?? message; } catch { /* non-JSON */ }
      return { success: false, message };
    }
    return { success: true };
  } catch {
    return { success: false, message: 'Unexpected error' };
  }
}
```

(Confirm `authFetch` sets `Content-Type: application/json`; if not, add headers to the set-password call.)

- [ ] **Step 2 verify:** `npx tsc --noEmit` clean + `npx eslint src/actions/auth.actions.ts src/models/user.model.ts` → 0 errors.
- [ ] **Step 3 commit:** `git commit -am "feat(profile): session + set-password server actions + UserModel fields"`

---

## Task 9: Web — parseUserAgent util

**Files:** Create `src/utils/static/parseUserAgent.ts`

- [ ] **Step 1:** minimal, dependency-free UA → friendly label.

```ts
export const parseUserAgent = (ua?: string | null): string => {
  if (!ua) return 'Unknown device';
  const browser =
    /Edg\//.test(ua) ? 'Edge'
    : /OPR\/|Opera/.test(ua) ? 'Opera'
    : /Chrome\//.test(ua) ? 'Chrome'
    : /Safari\//.test(ua) ? 'Safari'
    : /Firefox\//.test(ua) ? 'Firefox'
    : 'Browser';
  const os =
    /iPhone|iPad/.test(ua) ? 'iOS'
    : /Android/.test(ua) ? 'Android'
    : /Mac OS X/.test(ua) ? 'macOS'
    : /Windows/.test(ua) ? 'Windows'
    : /Linux/.test(ua) ? 'Linux'
    : '';
  return os ? `${browser} · ${os}` : browser;
};
```

- [ ] **Step 2 verify:** `npx eslint src/utils/static/parseUserAgent.ts` → 0.
- [ ] **Step 3 commit:** `git commit -am "feat(profile): parseUserAgent helper"`

---

## Task 10: Web — SetPasswordModal + DeviceList + SecuritySection

**Files:** Create the three components under `src/components/Profile/SecuritySection/` (+ `index.ts` = `import SecuritySection from './SecuritySection'; export default SecuritySection;`).

- [ ] **Step 1: SetPasswordModal.tsx** — modal with new+confirm password, reuse `PasswordRequirements` + `FormValidator.minLength(MIN_PASSWORD_LENGTH)`; on submit call `setInitialPassword`, toast result, `onClose`. Model the markup on the existing `ForgotPasswordForm` password fields (show/hide toggle). On success the backend revoked all sessions → also call the existing `logout()`/redirect so the user re-logs with their new password (note this clearly in the success toast).

- [ ] **Step 2: DeviceList.tsx** — `useEffect` loads `getSessions()`; render each as a row: `parseUserAgent(s.userAgent)` + `· This device` badge when `s.current`, IP (muted), "last active" via `dayjs(s.lastUsedAt).fromNow()`. Per non-current row a "Sign out" button → `revokeSession(s.sessionGroup)` then refresh list. A "Sign out of all other devices" button → `revokeOtherSessions()` then refresh. Disable actions on the current row's sign-out (or treat as logout — keep simple: hide sign-out on current).

- [ ] **Step 3: SecuritySection.tsx** — props `{ user: UserModel }`. Renders:
  - "Connected accounts": if `user.provider === 'GOOGLE'` show a "Google — Connected ✓" row (static chip).
  - "Password": if `user.passwordSet === false` → "Set a password" button opening `SetPasswordModal`; else a short "Password is set — change it in Personal information" hint (the existing change-password stays in the Profile form).
  - `<DeviceList />`.

- [ ] **Step 4 verify:** `npx tsc --noEmit` clean + `npx eslint src/components/Profile/SecuritySection` → 0. Watch the gotchas: `index.ts` re-export form; any `useEffect` cleanup returns consistent.

- [ ] **Step 5 commit:** `git commit -am "feat(profile): SecuritySection — connected accounts, set-password, device list"`

---

## Task 11: Web — wire into Profile + i18n

**Files:** Modify `src/views/Profile/Profile.tsx`, `messages/*/common.json` (9)

- [ ] **Step 1:** Render `<SecuritySection user={user} />` in `Profile.tsx` (after `AccountInfoSection`, before/after the personal-info form — own card).

- [ ] **Step 2:** Replace hardcoded English (`Birthday`, the birthday description, `Member since`, `Last login`, `Total bookings`) with `t('...')` keys. Add all new keys (security section labels + the polish) to the 9 `common.json` via a script (insert after a stable key, like the social-login `orContinueWithEmail` insertion). New keys (English values; translate per locale): `signInAndSecurity`, `connectedAccounts`, `googleConnected`, `setPassword`, `passwordIsSet`, `activeSessions`, `thisDevice`, `lastActive`, `signOut`, `signOutOtherDevices`, `birthday`, `birthdayDescription`, `memberSince`, `lastLogin`, `totalBookings`. Use the `(t as unknown as (k:string)=>string)` cast for freshly-added keys where the strict union complains.

- [ ] **Step 3 verify:** for each locale `python3 -c "import json;json.load(open('messages/<loc>/common.json'))"` parses; `npx tsc --noEmit` + `npx eslint src/views/Profile/Profile.tsx` → 0.

- [ ] **Step 4 commit:** `git commit -am "feat(profile): mount SecuritySection + i18n profile strings (9 locales)"`

---

## Task 12: Web deploy + smoke

- [ ] **Step 1:** `git push origin main` (both repos pushed before deploy, per the local→git→live→memory pattern).
- [ ] **Step 2:** cusma1 deploy (recipe `reference_server_infra` web gotchas): `df -h /` (truncate nginx logs + rm `.next/cache/images` if <5G free), tar+scp the changed source, extract, `NODE_OPTIONS=--max-old-space-size=2048 yarn build`, `echo 'ccCCuuUU1!' | sudo -S -p '' systemctl restart nextapp.service`, restart watchdog with sudo. NO `.next.bak`.
- [ ] **Step 3:** verify new `BUILD_ID` in `https://www.boat4you.com` HTML + homepage 200.
- [ ] **Step 4:** browser smoke: `/my-profile` → Sign-in & security card shows; (as a Google-only test user) "Set a password" works; device list shows "This device"; "Sign out of all other devices" works.

---

## Self-review notes
- **Spec coverage:** B (Tasks 1,2,6,8,10,11) · C2 sessions (Tasks 1–5,8,9,10,11) · D i18n (Task 11). All covered.
- **Open confirmations flagged inline** (not placeholders — real "verify against current code" checks): `AbstractEntity.createdAt` getter name; exact `JwtAuthenticationFilter` body (does it already load the token row?); whether `/auth/me` returns the generated `User` (OpenAPI) vs a hand-mapped model; `authFetch` default Content-Type.
- **Type consistency:** `sessionGroup` (string) used identically across entity, repo, service, DTO, controller, web action, components. `passwordSet`/`provider` consistent BE↔web.
- **Out of scope (later):** unlink/connect providers, geo-IP, customer 2FA, audit log, Phase 2/3.
```
