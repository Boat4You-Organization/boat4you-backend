# Boat4You Pre-Production Review — SUMMARY

**Branch:** `prod-review-2026-05-07` (off main `6f11eef`)
**Review dates:** 2026-05-07 to 2026-05-11
**Spec:** `docs/superpowers/specs/2026-05-07-boat4you-prod-review-design.md`
**Reviewer:** Code-review pass through 7-phase design spec
**Subject:** Boat4You web service backend (Spring Boot 3.5, Kotlin 2.3, JDK 21, Postgres 18)

---

## Headline number

**181 finding-a kroz 7 faza** | **23 FIXED** (in-flight kroz review) | **158 OPEN** | **22 INFO/positive notes**

| Severity | Open | Fixed | Total |
|---|---|---|---|
| **CRIT** | 5 | 0 | **5** |
| **HIGH** | 30 | 0 | **30** |
| **MED** | 67 | 0 | **67** |
| **LOW** | 56 | 23 | **79** |
| **INFO** | 22 | — | **22** |
| DEFERRED to Phase 7 (nginx batch) | 6 | — | 6 |
| DEFERRED other | 3 | — | 3 |
| BLOCKED | 1 | — | 1 |

---

## Deploy readiness verdict

### 🔴 **NOT deploy-ready** — 5 CRIT + 30 HIGH OPEN

Top blockers koji **MORAJU biti riješeni prije prvog production deploy-a:**

#### CRIT (5)

| ID | Finding | Action required |
|---|---|---|
| **F1-019** | Stripe webhook non-idempotent (Phase 1) | Implement event-level idempotency table + phase-level check (concrete fix u F3-022) |
| **F1-068** | `/public/inquiries/{id}/send-test` email-bombing anon endpoint | Rate limit + auth gating; coupled s F1-067 PII leak |
| **F2-043** | V9_xx test data Flyway migracije izvršavaju u prod-u (inserts 15+ Workspace.hr team accounts s shared bcrypt hash) | Set `FLYWAY_TARGET_VERSION=1.90` env var on VM2/VM3; restructure V9_* to test-only folder; sanitize V9_00 emails |
| **F3-022** | `StripePaymentService.handleWebhookEvent` non-idempotent (F1-019 konkretizacija) | Event-level idempotency table + phase-level `paidOn` guard + `Session.expire()` na overwrites (pair s F3-023/F3-024) |
| **F5-012** | `Utils.kt:getRandomPassword/getRandomNumericalString` use non-cryptographic `Random` for user passwords + email verification codes (predictable security tokens) | Adopt `SecureRandom` pattern from `UrlShortener.kt`; raise `DEFAULT_PASSWORD_LENGTH` 6 → 16; pair s F5-018/019 |

#### HIGH (30 — top consolidations)

**Stripe payment hardening trio (3 HIGH paired s F1-019 CRIT + F3-022 CRIT):**
- F3-023 — `setSessionIdOnPaymentPhases` overwrites without check (orphan payment money-loss scenario)
- F3-024 — `@Transactional` wraps partner API + DB + email (partial-failure → partner confirmed + DB rolled back)
- F2-026 + F2-036 (MED escalations) — `OfferPaymentPlan` + `ReservationPaymentPhase` mutable equals/hashCode in MutableSet (payment double-capture vector)

**HTTP foundation (4 HIGH):**
- F3-001 — NauSys/MMK RestClient no connect/read/write timeouts (partner outage = VM2 unresponsive)
- F3-002 — `@Retryable(Exception::class)` on state-changing calls (createOption/confirmReservation duplicates partner side effects)
- F3-003 — NauSys credentials in HTTP body plaintext (F1-037 deepening)
- F3-009 — Customer PII (name, surname, crew list) in NauSys HTTP body (F3-003 GDPR-scope extension)

**Scheduler foundation (3 HIGH):**
- F4-001 — Default single-thread `@Scheduled` blocks all crons + drift
- F4-002 — Profile-only locking; 2-VM double-fire risk; no ShedLock
- F4-009 — `ImageUtils` native memory leak ~1.5GB/day VM3 → eventual OS OOM kill

**Error handling (3 HIGH):**
- F5-001 — `@ExceptionHandler(AccessDeniedException::class)` catches `kotlin.io.AccessDeniedException` (file I/O) instead of Spring Security's; **silent prod bug** → 403 requests returning 500
- F5-002 — Catch-all + SQLException + DataAccess echo `e.message` to customer (F1-055 confirmation with 5 concrete leak vectors)
- F5-013 — `JWT_SECRET_KEY` env var bez `:?required` syntax; fails silent (fail-open JWT signing)

**Config + container (4 HIGH):**
- F5-014 — Mail credentials use placeholder defaults (`your@gmail.com`, `your-app-password`)
- F6-001 — `Dockerfile` no `USER` directive → container runs as root (verify if container ships prod)
- F6-002 — **`README.md` documents test user passwords "123456" sa SYSTEM_ADMIN role** — combined s F2-043 = documented single-glance prod admin takeover
- F6-003 — `boat4you-ws-perf-update.tar.gz` 298 MB u repo root, contents unknown (potencijalno PII u backup)

**Boundary + dev exposure (Phase 1 unfixed HIGH):**
- F1-002 — Static OpenAPI YAMLs `permitAll()` (yml fixed; security-policy gap)
- F1-004 — Weak password requirements (min 6 chars, no complexity, no breach check)
- F1-005 — JWT no `iss`/`aud` validation; role not re-fetched from DB
- F1-020 — File upload validates only Content-Type, not magic bytes
- F1-024 — `StripePaymentService.handleWebhookEvent` `!!` assertions on user metadata
- F1-037 — `NAUSYS_URL` default `http://`
- F1-041 — `DevEquipmentSyncController` on `/public/dev` + profile-only auth (paired with F3-035)
- F1-056 — `cancelReservation` non-atomic external delete + DB cancel
- F1-057 — `setPasswordForReservation` no rate limit; `reservationId` enumeration
- F1-064 — Public yacht search triggers synchronous external sync from request thread
- F1-067 — `/public/inquiries/{id}/email-preview` PII leak
- F1-069 — `/public/inquiries` POST no rate limit
- F1-070 — `/public/image/{imageId}` no width/height validation (OOM/DoS, coupled with F4-009)

---

## Pre-prod fix-batch plan (recommended sequencing)

Sve fix-eve grupiraj u logical commit batch-eve. Sequence by impact-per-effort:

### Phase A — Low-risk quick wins (~30-60 min)

1. **README delete + docs cleanup commit:**
   - F6-002 — delete test user table from README (5 min, immediate priority)
   - F6-008 — update README sync endpoint docs to POST + admin paths (5 min)
   - F6-005 — clean .env.example: drop Viva block, fix port, unify mail naming (5 min)

2. **yml-hardening commit** (F1-036 family extension):
   - F4-001 — `spring.task.scheduling.pool.size: 8`
   - F5-013 — `JWT_SECRET_KEY:?required`
   - F5-014 — Mail creds `:?required`
   - F5-016 — NauSys/MMK env vars `:?required`
   - F6-006 — docker-compose `SSL_KEYSTORE_PASSWORD:?required`

3. **logback config commit:**
   - F5-015 — DEBUG → INFO za hr.workspace.boat4you u prod
   - F5-017 — LOG_DIR absolute path s env override

### Phase B — Code fix batch (~3-4h)

4. **Stripe payment hardening commit** (CRIT/HIGH trio + Phase 2 pair):
   - F3-022 — event-level `processed_stripe_events` idempotency table + check
   - F3-023 — `Session.expire(oldSessionId)` before overwrite
   - F3-024 — email out-of-TX via `@TransactionalEventListener(AFTER_COMMIT)` (already-implemented pattern in EmailService — F3-033 INFO)
   - F3-025 — defensive null checks na Stripe payload
   - F3-026 — `oldest()` filter for unpaid phases
   - F2-026 + F2-036 — id-based equals/hashCode on payment phase entities

5. **NauSys/MMK HTTP foundation commit:**
   - F3-001 — RestClient timeouts (adopt StripeConfig pattern)
   - F3-002 — `@Retryable` narrow scope (transient-only)
   - F3-005 — jitter on backoff
   - F3-008 — `getReservation` 9× amplification fix

6. **DevController hardening commit** (F1-041 closeout):
   - F3-035 — move `/public/dev` → `/admin/dev`; add `@PreAuthorize("hasRole('SYSTEM_ADMIN')")`; convert state-changing GET → POST

7. **Utils.kt SecureRandom commit** (F5-012 CRIT):
   - F5-012 — adopt UrlShortener pattern (SecureRandom + clean 62-char charset)
   - F5-018 — fix charset typos (Y + j)
   - F5-019 — raise DEFAULT_PASSWORD_LENGTH 6 → 16

8. **ApiErrorHandler refactor commit** (Phase 5 consolidation):
   - F5-001 — explicit Spring Security `AccessDeniedException` import + handler
   - F5-002 — stop echoing `e.message` to customer
   - F5-003 — drop dynamic context in customer messages
   - F5-004 — fix HTTP status mapping (404 / 500)
   - F5-007 — proper `logger.error(msg, e)` format + WARN-level for expected errors
   - F5-009 — proper ErrorSchema body for forbidden
   - F5-010 — log on ImageNotFoundException + drop TODO

### Phase C — Architectural decisions (~1-2 days each)

9. **ShedLock distributed locking** (F4-002 + F3-037 + F3-014):
   - Add dependency, Flyway migration for `shedlock` table, `@SchedulerLock` annotations on cron methods

10. **ImageUtils Mat.use{}** (F4-009 — staging test obavezan):
    - Extension function + apply to all 3 image processing methods

11. **Audit trail batch** (F2-001 + F2-004 + F2-017 + F2-028 + F2-038 + F2-041):
    - SecurityContext audit injection u AbstractEntity `@PrePersist`/`@PreUpdate`
    - Hook CustomRevisionListener to current user
    - AbstractEntity expansion to Yacht/Offer-flow/Reservation-flow entities
    - ReservationDocument audit table

12. **PII storage scrub + email outbox:**
    - F3-010 — `responseBody` field scrubbing or removal
    - F3-031 — `email_outbox` pattern (Flyway migration + scheduled retry job)

### Phase D — Operational verifications (Mario ops-side, ~1h total)

13. **Production env audit:**
    - F2-043 — verify `FLYWAY_TARGET_VERSION` env var on VM2/VM3 systemd units
    - F4-004 — verify `image-sync` profile on VM3 if needed
    - F3-003 — verify NauSys partner-side HTTPS support; switch URL
    - F6-003 — inspect `boat4you-ws-perf-update.tar.gz` contents; secure-delete ako PII
    - F6-004 — partner credential rotation policy
    - F4-011 — Mario commentary on V1_24 migration history
    - F2-045 — preflight `SELECT COUNT(*) FROM inquiry WHERE phone IS NULL`

### Phase E — Faza 6 perf indexes (deferred):

14. **Single index migration commit** (F2-023 + F2-024 + F2-033 + F2-034 + F2-040 + F1-068 family):
    - `CREATE EXTENSION IF NOT EXISTS pg_trgm`
    - Functional `LOWER(name)` indexes na manufacturer/model/agency/inquiry/reservation_flow
    - Trigram GIN indexes na leading-wildcard search columns
    - Plus controller-side `name.length >= 2` validations

### Phase F — Faza 7 nginx batch (separate deploy window per Mario):

15. **Nginx ops batch** (F1-003 + F1-022 + F1-048 + F1-050 + F1-051 + F1-053):
    - One nginx config diff for all 6 findings
    - `nginx -t` validate + `systemctl reload nginx` + smoke test

---

## Read-pass coverage

| Phase | Scope | Read | Findings |
|---|---|---|---|
| 1 | Boundary / attack surface | ✓ pre-existing | 67 |
| 2 | Data layer (persistence) | ✓ 7 batches | 50 |
| 3 | External integrations | ✓ 6 batches | 40 |
| 4 | Scheduled jobs + native | ✓ 2 batches | 14 |
| 5 | Cross-cutting | ✓ 2 batches | 21 |
| 6 | Repo hygiene + deploy | ✓ 1 batch | 13 |
| 7 | Final verification | this | — |

**Total: 22 batches across 6 read-pass phases + 1 verification phase = 205 finding-references catalogued (181 unique findings).**

---

## What's working well — positive notes

22 INFO findings document patterns već-applied-correctly. Highlights:

- **EmailService** (`F3-033`) — best-engineered file u code base-u. `@TransactionSynchronization.afterCommit` defer + virtual thread executor + dev-log-to-file + multi-brand + locale + dynamic attachments + rich historical comments.
- **Stripe SDK config** (`F3-029`) — proper timeouts (5s/15s/2 retries) + idempotency-key support + signature verification. Pattern model za NauSys/MMK adoption.
- **Admin sync controllers** (`F3-039`) — properly @PreAuthorize SYSTEM_ADMIN + @Profile data-sync + @PostMapping (F1-042/043/044 fixes already applied).
- **BookingSequenceRepository** (`F2-042`) — `@Lock(PESSIMISTIC_WRITE)` for concurrent-safe sequence generation.
- **ReservationDocumentRepository** — DTO projection avoiding heavyweight BYTEA load.
- **GDPR soft-delete migration** (`F2-050` INFO) — V1_78 anonymization strategy + partial unique index, OWASP password-reset TTL, V1_79 audit log.
- **`UrlShortener.kt`** (`F5-020`) — proper `SecureRandom` + clean charset (model za F5-012 fix).
- **MMK offer sync** (`F3-020`) — chunked-of-3 parallelism + 15-min per-batch timeout (model za NauSys adoption).
- **systemd VM2/VM3 split** (`F6-013`) — non-root cusma2/cusma3 users; proper environment file separation.
- **`.gitignore`** — solid coverage: env, p12 (commented for explicit decision), tar.gz, OS cruft, IDE files.
- **Most env vars use `:?required` correctly** (`F5-021`) — DB creds, SSL keystore, SERVER_HOST_PUBLIC, Stripe keys (F1-036 family fixes).

---

## What was fixed during review

**Phase 1 (pre-session):** 20 fixes — F1-009/F1-011/F1-012/F1-016/F1-017/F1-029/F1-031/F1-032/F1-036/F1-042/F1-043/F1-044/F1-045/F1-046/F1-047/F1-049/F1-060/F1-061/F1-073/F1-002 (yml-side)

**Phase 2 (this session):** 3 fixes
- F2-018/F2-019 (commit `0d1242a`) — full enum ORDINAL → STRING migration (V1_90 + 22 entity fields + service callers + view R__ migrations)
- F2-022 (commit `0dc514f`) — scheduled cleanup PostgreSQL date syntax (`CURRENT_DATE - INTERVAL '30 days'` + `:cutoff` parameter binding)

**Phase 3-6 (this session):** 0 fixes — all read-pass, no code changes.

**Total: 23 FIXED across review.**

---

## Final phase gate

`./gradlew compileKotlin detekt test --continue` results:
- **compileKotlin:** ✓ clean
- **detekt:** 291 weighted issues — exact baseline match across all 7 phases (zero regression)
- **test:** 29/103 failed — F1-074 family (`ReservationPaymentPhasesServiceTest`); zero new failures

**Phase gate: PASS at baseline.**

---

## Quick references

- **Master findings index:** [REGISTER.md](REGISTER.md) — every finding cross-referenced by phase + severity + status
- **Per-phase deep dives:** `phase-N-<topic>.md` files (1-7)
- **Spec document:** [`docs/superpowers/specs/2026-05-07-boat4you-prod-review-design.md`](../specs/2026-05-07-boat4you-prod-review-design.md) — 7-phase design + hibridna fix policy + commit-message format
- **Phase 1 closure commit:** `7369434` / pushed `9d97675`
- **Phase 2 closure:** `0af3478` / pushed `f31f2e8`
- **Phase 3 closure:** `f47dd93` / pushed `8aec61d`
- **Phase 4 closure:** `efcd2c8` / pushed `f3da781`
- **Phase 5 closure:** `235fdb8` — unpushed
- **Phase 6 closure:** `924e5ab` — unpushed
- **Phase 7 closure:** _this commit_ — pending

---

**Review complete 2026-05-11.** Recommendation: implement Phase A (low-risk quick wins ~30-60 min) immediately + plan Phase B (code fix batch ~3-4h) + Phase D (operational verifications, 1h Mario ops side) before scheduling production deploy. Phase C (architectural decisions) i Phase E (perf indexes) može pratiti prvi prod-deploy iteratively. Phase F (nginx batch) je single deploy window separate.
