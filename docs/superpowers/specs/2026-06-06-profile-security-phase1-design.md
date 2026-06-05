# Profile → "Sign-in & security" (Phase 1) — Design

**Date:** 2026-06-06 · **Status:** DESIGN (no code written) · **Repos:** backend (Kotlin/Spring) + web (Next.js)

Phase 1 of the profile-settings upgrade. Goal: bring the account area to a professional security
baseline by adding a **Sign-in & security** card to `/my-profile` with three parts:

- **B. Connected accounts + Set-a-password** — closes the gap that social login created (Google-only
  accounts have an unguessable random password the user can't change from the UI).
- **C. Active sessions / devices** (full, "C2") — real device list with per-device sign-out.
- **D. i18n polish** — localise the few hardcoded English strings already in the profile.

Grounded in current code: `tokens` table = `value, is_revoked, is_expired, expires_at, user_id` (NO
device metadata today); `saveUserToken` stores only value/user/expiresAt; `TokenService` has
`revokeAllUserTokens`/`deleteAllUserTokens`; users now have `provider`/`provider_id` (from social login).

---

## B. Connected accounts + Set-a-password

### Backend
- **Migration `V1_97a` (or fold into the tokens migration):** `users.password_set BOOLEAN NOT NULL DEFAULT true`.
  Backfill: `UPDATE users SET password_set = (provider IS NULL)` — existing email accounts = true,
  any already-created social accounts = false (so they correctly see "Set a password").
- **Social create path (`OAuthService.createGoogleAccount`)** sets `password_set = false`.
- **`/auth/me` (UserModel)** additionally exposes `provider` (e.g. "GOOGLE" / null) and `passwordSet`.
- **`POST /users/me/set-password`** (authenticated): body `{ newPassword }`. Allowed **only when
  `passwordSet = false`** (otherwise 409 → client routes to the existing change-password). Validates via
  `PasswordPolicy` (min 12 + weak-list). On success: encode + save, set `passwordSet = true`,
  **revoke all OTHER sessions** (force re-auth elsewhere). Rate-limited (`PublicEndpointRateLimiter` or
  equivalent authed limiter).
- Existing **change-password** (old+new) stays for `passwordSet = true` users.

### Frontend
- New **Sign-in & security** card. "Connected accounts" row: **Google ✓ Connected** when `provider === 'GOOGLE'`.
- Password row: if `passwordSet === false` → **"Set a password"** button → modal (new + confirm, reuse
  `PasswordRequirements` + `FormValidator.minLength`). If `true` → existing Change-password.
- `setPassword` server action → `POST /users/me/set-password` → on success toast + (session revoked
  elsewhere, current stays).

### Out of scope (Phase 1)
- **Unlink Google** / connect Apple/Facebook. Unlink must first require a set password (avoid lockout) —
  design when Apple/FB land.

---

## C. Active sessions / devices (C2 — full)

### Backend
- **Migration `V1_97`:** add to `tokens` (all nullable — legacy rows = null):
  `user_agent VARCHAR(512)`, `ip_address VARCHAR(64)`, `last_used_at TIMESTAMP`,
  `session_group VARCHAR(36)` (UUID shared by the access+refresh pair minted in one login).
- **Capture at token creation:** `saveUserToken` (and every caller — `login`, `issueTokenAtRegistration`,
  OAuth, refresh) receives the `HttpServletRequest`. Parse `User-Agent` header + resolve client IP
  (reuse the rate-limiter's existing X-Forwarded-For/remote-addr resolution). The access+refresh pair
  for one login share a freshly generated `session_group` UUID.
- **`last_used_at` (throttled):** in `JwtAuthenticationFilter`, after the token row is loaded for
  revocation check, update `last_used_at` **only if `now - last_used_at > 5 min`** (TUNABLE) — avoids a
  DB write on every request while keeping "last active" usefully fresh.
- **Endpoints (authed):**
  - `GET /users/me/sessions` → one entry per `session_group`: `{ sessionGroup, userAgent, ipAddress,
    createdAt, lastUsedAt, current: bool }`. `current` = the group of the presenting token. Only lists
    valid (not revoked/expired) tokens; collapses access+refresh into one entry. Legacy rows
    (`session_group IS NULL`) are excluded — they age out within the refresh TTL (3 days).
  - `DELETE /users/me/sessions/{sessionGroup}` → revoke both tokens in that group.
  - `POST /users/me/sessions/revoke-others` → revoke every session except the current group.

### Frontend
- **Device list** under the security card: each row shows a friendly label parsed from `userAgent`
  **on the client** ("Chrome · macOS") — keeps the backend free of a UA-parsing dependency — plus
  "last active" (relative) and a **"This device"** badge for `current`. Per-row **Sign out**; a
  **"Sign out of all other devices"** button calls `revoke-others`.
- `getSessions` / `revokeSession` / `revokeOtherSessions` server actions (authed via `authFetch`).

### Decisions made (flag if you disagree)
- **Device name parsed on the frontend** from raw `user_agent` (no backend UA lib).
- **`last_used_at` throttle = 5 min** (tunable).
- **No geo-IP / location in v1** — store IP, show device + last-active only. City/country lookup is a
  later add (needs a geo-IP source).
- **Legacy (pre-migration) sessions are not listed** — they expire within 3 days; avoids a messy
  "Unknown device" bucket. Within 3 days of deploy every session has full metadata.

---

## D. i18n polish (parallel, ~30 min)
Localise hardcoded English in `views/Profile`: `Birthday` (label + description), `Member since`,
`Last login`, `Total bookings` → keys across all 9 locales. Plus the pending confirm-email page strings.

---

## Cross-cutting edge cases
- `set-password` only when `passwordSet=false`; if already set → 409, client falls back to change-password.
- `revoke-others` must keep the **current** session alive (never revoke the presenting group).
- Social-only user who somehow can't set in-app → Google-verified email means **forgot-password** is a
  working fallback.
- Revoking the **current** session from the device list = a deliberate logout (allowed).
- All new routes authenticated + rate-limited; no PII (IP/UA) leaves the owner's own `/users/me/*`.

## Rough sequencing (when approved → writing-plans)
1. BE migration (`password_set` + tokens metadata) → 2. token-capture + session_group + last_used →
3. endpoints (set-password, sessions list/revoke) → 4. `/auth/me` fields → 5. FE security card
(connected accounts + set-password) → 6. FE device list → 7. i18n polish. Deploy = backend (cusma2+3)
+ web (cusma1), same recipe as the social-login deploy.

## Out of scope (later phases)
Unlink/connect more providers · geo-IP location · optional customer 2FA · login/security audit log
(separate AuthAuditLog feature) · Phase 2 engagement (favorites/saved-search) · Phase 3 money/identity.
