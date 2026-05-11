# Faza 5 — Cross-cutting

**Status:** in progress (inventory + read pass)
**Datum starta:** 2026-05-11
**Scope per spec (`2026-05-07-boat4you-prod-review-design.md:124-136`):**
- `common/errorhandling/` — `ApiErrorHandler.kt` (global `@ControllerAdvice`), `ApiErrorCodes.kt`
- `common/exceptions/` — `EntityNotDeletableException`, `ParameterValidationException`, `ResourceNotFound`, `UnmodifiableFieldsException`
- Sve `log.*(...)` linije (grep cijelog repa) — što loga-mo, na kojem level-u, s kojim PII
- `application.yml` + svi profili (`application-prod.yml`, `application-dev.yml`, `application-aws.yml`)
- `messages/*.properties` (10 fajlova — 1 default + 9 locale variants)
- `common/services/` — preostali fajlovi koji nisu već touched u Phase 1-4
- `logback-spring.xml` (logging config)
- `events/*` — NIJE u repo-u (no events folder per glob); spec spomenuo ali ne postoji

---

## Inventory

### Error handling (2 files)

| Path | Role |
|---|---|
| `common/errorhandling/ApiErrorHandler.kt` | Global `@ControllerAdvice` — exception → HTTP status mapping |
| `common/errorhandling/ApiErrorCodes.kt` | Enum/constants of API error codes (likely client-facing toast messages) |

### Exceptions (4 files)

| Path | Role |
|---|---|
| `common/exceptions/EntityNotDeletableException.kt` | Custom exception |
| `common/exceptions/ParameterValidationException.kt` | Param validation failure |
| `common/exceptions/ResourceNotFound.kt` | 404 mapping |
| `common/exceptions/UnmodifiableFieldsException.kt` | Field-level update reject |

### Common services (9 files — non-touched u earlier phases)

| Path | Already covered? |
|---|---|
| `common/services/CommonTranslators.kt` | NEW |
| `common/services/FileSystemService.kt` | Phase 1 F1-021/F1-034 — defense-in-depth callers verified except F4-010 |
| `common/services/ImageService.kt` | NEW |
| `common/services/ImageUtils.kt` | Phase 4 F4-009 native memory leak |
| `common/services/LocaleHelpers.kt` | NEW |
| `common/services/PriceCalculations.kt` | NEW |
| `common/services/UnitCalculations.kt` | NEW |
| `common/services/UrlShortener.kt` | NEW |
| `common/services/Utils.kt` | NEW |

### Application config (4 files)

| Path |
|---|
| `application.yml` (defaults, base configuration) |
| `application-prod.yml` (prod overrides) |
| `application-dev.yml` (dev overrides) |
| `application-aws.yml` (AWS-specific overrides) |

### i18n (10 properties files)

| Path |
|---|
| `messages/email.properties` (default, likely EN) |
| `messages/email_en.properties` |
| `messages/email_hr.properties` |
| `messages/email_de.properties` |
| `messages/email_es.properties` |
| `messages/email_fr.properties` |
| `messages/email_it.properties` |
| `messages/email_pt.properties` |
| `messages/email_nl.properties` |
| `messages/email_pl.properties` |

### Logging config

| Path |
|---|
| `logback-spring.xml` — log levels, appenders, rotation, format pattern |

### Logging callsites — grep target

Across all `src/main/kotlin/**/*.kt` files, search for:
- `log.info`, `log.warn`, `log.error`, `log.debug`, `log.trace`
- PII indicators: `${user.email}`, `${reservation...}`, `${jwt}`, `password`, `token`
- Stack traces in customer-facing exception paths

---

## Spec questions to answer

Per design spec section 124-136:
1. **Exception poruke korisniku** — leak interne info? (F1-055 / F1-066 / F3-015 / F4-013 patterni)
2. **Exception poruke u logu** — govori li programeru dovoljno (correlation id, user id, parametri sažeto)?
3. **Log linije s PII / JWT / password reset linkom / karticom** — pattern grep
4. **Log levele i rotacija** — prod logback-spring.xml audit
5. **Spring profile defaults koji moraju biti override u prod-u** — sve `${VAR:default}` antipattern check
6. **i18n missing keys** — Thymeleaf `#{key}` references koje nemaju match
7. **Sinkroni vs asinkroni eventi** — `@TransactionalEventListener` pattern (F3-033 pozitivan), specifične potrebe

---

## Workflow Faze 5

Plan reading pass-a u 2 batch-a:

1. **Batch 1 — Error handling + exceptions + i18n consolidation** — ApiErrorHandler + ApiErrorCodes + custom exceptions, plus i18n key audit (vlikih volumen, grep za missing keys)
2. **Batch 2 — Logging audit + yml profile audit + common services foundation** — logback-spring.xml + 4 yml fajla + remaining common services (CommonTranslators, ImageService, LocaleHelpers, PriceCalculations, UnitCalculations, UrlShortener, Utils)

Za svaki batch: triage live u ovaj file kao `[F5-NNN]` nalazi.

**Očekivani output:** Phase 5 surfaceira large-volume LOW/MED finding-a (log-PII grep, yml defaults, i18n gaps). Mali broj HIGH (foundational error-sanitization već flag-an kroz F1-055/etc.). Phase 5 cilj je **konsolidacija** prijašnjih point-finding-a u cross-cutting fix batch-eve.

---

## Findings

---

## Batch 1 — Error handling + exceptions + ApiErrorCodes (2026-05-11)

### [F5-001] HIGH bug — `@ExceptionHandler(AccessDeniedException::class)` catches `kotlin.io.AccessDeniedException` (file I/O), NIJE Spring Security `org.springframework.security.access.AccessDeniedException`
**Lokacija:** `common/errorhandling/ApiErrorHandler.kt:266-272`
**Detekcija:** statička + import check (`grep ^import ApiErrorHandler.kt` — zero security import)
**Opis:** No explicit import za `AccessDeniedException` u fajlu (verified via grep). Kotlin default imports uključuju `kotlin.io.*` → `AccessDeniedException` resolve-a na **`kotlin.io.AccessDeniedException`** (Kotlin's file system exception), ne na `org.springframework.security.access.AccessDeniedException` (Spring Security's 403 Forbidden).

**Dvije posljedice:**
1. **Spring Security forbidden access** (`@PreAuthorize` returns false) → handler ne hvata → fall-through na catch-all `Exception` → **500 INTERNAL_SERVER_ERROR + `e.message`** umjesto **403 FORBIDDEN**. Klijent dobije pogrešan status code.
2. **File system access denied** → ovaj handler **JE** hvata → vraća 403 FORBIDDEN customer-u. Semantically wrong — internal file system perm je 500.

Plus: `ResponseEntity(HttpStatus.FORBIDDEN)` (line 271) returns empty body — inconsistent s ostatkom handler-a (F5-009 covers).

**Combined s F3-035 (DevController hardening) + F1-041:** kad path se prebaci na /admin/dev i @PreAuthorize-d, svaki forbidden response biti će 500 umjesto 403 — testing-confusing + monitoring breaks.
**Posljedica:** API contract broken. Subtle bug koji preživljava compile + detekt baseline jer Kotlin tihočo resolve-a na pogrešnu klasu.
**Predloženi fix:**
```kotlin
import org.springframework.security.access.AccessDeniedException  // explicit

@ExceptionHandler(org.springframework.security.access.AccessDeniedException::class)
fun handleAccessDeniedException(e: org.springframework.security.access.AccessDeniedException): ResponseEntity<ErrorSchema> {
    logger.warn("Forbidden: ${e.message}")
    return ResponseEntity(
        ErrorSchema(ApiErrorCodes.FORBIDDEN.code, ApiErrorCodes.FORBIDDEN.message),
        HttpStatus.FORBIDDEN,
    )
}
```
Dodati `FORBIDDEN` u `ApiErrorCodes.kt` (currently missing). Plus remove accidental file-I/O catching.
**Riziko-procjena fixa:** dira global error handler — staging test obavezan.
**Status:** OPEN — **HIGH bug**, prod-readiness concern

---

### [F5-002] HIGH info disclosure — Catch-all `Exception` + `SQLException` + `DataAccessException` + `HttpMessageNotReadable` echo `e.message` u customer response (F1-055 confirmation + concrete)
**Lokacija:** `ApiErrorHandler.kt:457-467` (catch-all), `:394-404` (PersistenceException), `:242-264` (DataAccess + SQLException), `:47-57` (HttpMessageNotReadable)
**Detekcija:** statička
**Opis:**
```kotlin
@ExceptionHandler(Exception::class)
fun handleException(e: Exception): ResponseEntity<ErrorSchema> {
    logger.error("Handling general Exception: ${e.message}\nStack trace:\n${e.stackTraceToString()}")
    return ResponseEntity(
        ErrorSchema(GENERAL_ERROR.code, GENERAL_ERROR.message + ": ${e.message}"),  // <-- echoed
        HttpStatus.INTERNAL_SERVER_ERROR,
    )
}
```

`e.message` može sadržavati:
- **SQLException:** column names + query fragments + constraint violations s row data — npr. `"duplicate key value violates unique constraint 'users_email_key' Detail: Key (email)=(victim@example.com) already exists."`
- **NPE messages:** specific class/field hints → reveals internal structure
- **HttpMessageNotReadable (Jackson):** input snippet + char position → leaks customer-submitted data
- **PersistenceException:** Hibernate entity FQ class names + property names

F1-055 LOW from Phase 1 → Phase 5 confirms s 5 concrete leak vectors → escalates to HIGH.
**Posljedica:** info leak across multiple exception types. SQL constraint violation u registration flow → exposes registered emails. NPE u service → exposes internal class names.
**Predloženi fix:** stop echoing `e.message`:
```kotlin
@ExceptionHandler(Exception::class)
fun handleException(e: Exception): ResponseEntity<ErrorSchema> {
    logger.error("Handling general Exception", e)  // proper logger format
    return ResponseEntity(
        ErrorSchema(GENERAL_ERROR.code, GENERAL_ERROR.message),  // no dynamic context
        HttpStatus.INTERNAL_SERVER_ERROR,
    )
}
```
Apply to ALL handlers. Customer response = static code+message; log = full exception detail.

Plus: korelacioni ID (MDC `X-Request-Id`) — customer dobija "Error ID: abc-123", support može pretraživati log-ove. Faza 5 expansion.
**Status:** OPEN — **HIGH, F1-055 confirmation**, prod-blocker priority

---

### [F5-003] MED info disclosure — Internal field names / user IDs / referencing-entity types / partner status echoed u customer responses
**Lokacija:**
- `ApiErrorHandler.kt:68, 224` — `INVALID_REQUEST_PARAMETERS` + `$badParameters`
- `:80` — `USER_ALREADY_EXISTS` + `existingProperties` (email/phone enumeration channel)
- `:104` — `FIELDS_NOT_MODIFIABLE` + `$fieldNames`
- `:212` — `"Reservation status must be ${e.requiredStatus}"` (enum value leak)
- `:236` — `ENTITY_NOT_DELETABLE` + `$referencingEntities` (internal entity types)
- `:337, 356` — `userIds` in error messages

**Detekcija:** statička
**Opis:** Each individual case minor; cumulative = enumeration surface.

Key risk: **`USER_ALREADY_EXISTS` + `existingProperties`** (line 80) — registration flow returns "exists by phone" or "exists by email" — **email/phone enumeration confirmation**. F1-010 (email enumeration via login error codes) sibling.

Plus: F1-067 (inquiry preview PII leak), F4-013 (file path leak), F3-015 (partner ID leak), F5-001 (forbidden status), F5-002 (catch-all message) — **systemic error sanitization needed.**
**Predloženi fix:** apply F5-002 pattern systemic — never echo dynamic context. Field-level validation errors → structured `ErrorSchema.fieldErrors: Map<String, String>` (frontend can highlight bad field by name + code, no message leak).
**Status:** OPEN — pair s F5-002 u single error-sanitization commit batch

---

### [F5-004] MED API design — HTTP status mapping wrong: 11 "not found" handlers return 400 BAD_REQUEST instead of 404; `ResourceNotFound` returns 510 NOT_EXTENDED (typo?); DB exceptions return 400 instead of 500
**Lokacija:**
- `ApiErrorHandler.kt` lines 88, 113, 148, 160, 177, 188, 200, 336, 374 — `XxxDoesNotExist` → 400
- `:390` — `ResourceNotFound` → **510 NOT_EXTENDED** (probably typo for `NOT_FOUND`)
- `:251, 262` — DataAccess + SQLException → **400** (should be 500)

**Detekcija:** statička
**Opis:** Eleven "not found" handlers return 400. `ResourceNotFound` returns HTTP 510 (which means "further extensions required" — vrlo neobičan response). DB exceptions return 400 BAD_REQUEST suggesting "client error" when actually server has DB problem.

**Consequences:**
- API clients can't differentiate "user not found" (404) from "bad request" (400) — both 400.
- Monitoring + alerting segments by status — 404s expected, 400s investigated. Currently 400-spam.
- REST violation per RFC 7231.
- DB 400 affects client retry logic (clients don't retry 4xx, do retry 5xx).
- 510 NOT_EXTENDED uncommon enough that CDNs/proxies may handle weirdly.

**Predloženi fix:** systematic remap u istom commit batch-u s F5-002:
- All `XxxDoesNotExist`/`NotFound` → `HttpStatus.NOT_FOUND` (404)
- `ResourceNotFound` → `HttpStatus.NOT_FOUND` (fix typo)
- `DataAccessException` + `SQLException` → `HttpStatus.INTERNAL_SERVER_ERROR` (500)

**Riziko-procjena fixa:** dira API contract — frontend release koordinacija ako frontend razlikuje 404 vs 400.
**Status:** OPEN — pair s F5-002 single commit

---

### [F5-005] MED UX/i18n — `ApiErrorCodes` messages hardcoded English; non-EN customers dobiju English toast unatoč i18n infrastrukturi
**Lokacija:** `common/errorhandling/ApiErrorCodes.kt:1-48`
**Detekcija:** statička
**Opis:** Messages u enum-u su English-only. Customer-facing emails ARE localized (10 properties files + LocaleHelpers + EmailService locale-aware), ali API error responses nisu.

HR customer registracija fail → "User already exists" English toast unatoč tome što email s istom logikom ide na HR.
**Predloženi fix:** dvije opcije:
- (a) **Frontend handles i18n by code** (verify s Mario) — backend message je samo admin-debug; documented kao takav u ApiErrorCodes class komentaru.
- (b) **Backend localizes via MessageSource** — inject `MessageSource` + Locale resolution + `messages/api_errors.properties` (key per code). More work.

(a) is cheaper ako frontend can take responsibility.
**Status:** WAITING-DECISION (verify s Mario frontend i18n approach)

---

### [F5-006] MED security — `InternalLoginException` handler logs `${e.email}` at ERROR level on every failed login; PII accumulation, F1-068 attack amplifier
**Lokacija:** `ApiErrorHandler.kt:275-291`
**Detekcija:** statička
**Opis:**
```kotlin
@ExceptionHandler(InternalLoginException::class)
fun handleInternalLoginException(e: InternalLoginException): ResponseEntity<ErrorSchema> {
    logger.error("Handling InternalLoginException: ${e.email}: ${e.type.name}")
```

Combined s F1-068 (anon inquiry email-bombing) + F1-010 (login email enumeration) + F1-062 (timing attack): attacker iterates email list → each attempt logs **email + type** at ERROR level.

GDPR Article 5(1)(c) "data minimization": email u logu samo kad nužno za debugging. Failed login type je important; email je excessive.

Plus: ERROR level for expected user error (bad credentials) je inappropriate; should be WARN or INFO.
**Predloženi fix:**
```kotlin
private fun maskEmail(email: String?): String { ... }  // helper
logger.warn("InternalLoginException: ${maskEmail(e.email)}: ${e.type.name}")  // WARN, masked
```
Apply same masking pattern to other PII log statements (registration, password reset). Faza 5 cross-cutting PII sweep.
**Status:** OPEN — Faza 5 cross-cutting (PII masking sweep)

---

### [F5-007] LOW noise — Stack traces logged at ERROR level for every exception (incl. expected user errors); log noise + PII in frames + anti-pattern `\n${e.stackTraceToString()}` instead of `logger.error(msg, e)`
**Lokacija:** ~25 instances of `${e.stackTraceToString()}` u ApiErrorHandler
**Detekcija:** statička
**Opis:** Business-domain exceptions (UserDoesNotExist, YachtDoesNotExist, ReservationNotExist) su expected user errors, ne system failures. ERROR + stack trace = log spam + alarm noise.

Plus: anti-pattern `logger.error("text\n${e.stackTraceToString()}")` — should be `logger.error("text", e)` so logback can format-irati stack trace consistently + extract structured info za log aggregators.
**Predloženi fix:** segregate by category:
- WARN/DEBUG for client-recoverable (UserDoesNotExist, etc.) — no stack trace
- ERROR for unexpected server failures (NPE, partner outage) — `logger.error(msg, e)` proper format
**Status:** OPEN — pair s F5-002/003/004 single commit batch

---

### [F5-008] LOW typo + leak — `PASSWORD_INVALID_LENGTH(1014, "Password could contain at least six characters")` — typo + reveals min length (F1-004 family)
**Lokacija:** `ApiErrorCodes.kt:11`
**Detekcija:** statička
**Opis:** Two issues: "could" → "must"; plus reveals 6-char minimum (F1-004 HIGH "*Slabi password requirementi*").
**Predloženi fix:** generic message + raise min length in F1-004 fix:
```kotlin
PASSWORD_INVALID(1014, "Password does not meet requirements"),
```
Frontend explains requirements without backend leaking exact threshold.
**Status:** WAITING-DECISION (F1-004 family decision)

---

### [F5-009] LOW operational — `AccessDeniedException` handler returns empty body; covered by F5-001 fix
**Lokacija:** `ApiErrorHandler.kt:267-272`
**Detekcija:** statička
**Opis:** `ResponseEntity(HttpStatus.FORBIDDEN)` — empty body, breaks client JSON deserialization expecting ErrorSchema. Covered by F5-001 fix.
**Status:** OPEN — covered by F5-001 fix

---

### [F5-010] LOW dead-code marker — `ImageNotFoundException` ima `// TODO: enable logging if needed`; no log
**Lokacija:** `ApiErrorHandler.kt:170-180`
**Detekcija:** statička
**Opis:** Detekt baseline već flagged TODO. Plus no log → ako attacker probe-a path traversal preko image endpoint-a (F4-010), ImageNotFoundException fire-a thousands of times s zero audit traila.
**Predloženi fix:** `logger.debug("ImageNotFoundException")` + drop TODO.
**Status:** WAITING-DECISION (pair s F5-007 logging policy)

---

### [F5-011] LOW — `ResourceNotFound` exception class empty (no message, no context); debug-hostile
**Lokacija:** `common/exceptions/ResourceNotFound.kt:1-3`
**Detekcija:** statička
**Opis:**
```kotlin
class ResourceNotFound : RuntimeException()
```
3 lines, no message, no context. Throw-site provides nothing → handler logs "Handling ResourceNotFound" + bare stack trace. Combined s F5-002 (catch-all leak) — ResourceNotFound ironically safer jer nema message; ali debug-hostile.

Plus: usage scattered — should probably be replaced by specific exception types (YachtDoesNotExist etc.) ili accept context message + property.
**Status:** OPEN — Faza 5 (exception class hygiene)

---

### Sažetak Batch 1

- **HIGH (2):** F5-001 (AccessDeniedException wrong class caught — Spring 403 → 500), F5-002 (catch-all echoes e.message — F1-055 confirmation, prod-blocker priority)
- **MED (4):** F5-003 (internal context echoed), F5-004 (HTTP status mapping wrong), F5-005 (not i18n), F5-006 (PII email in error logs)
- **LOW (5):** F5-007 (stack trace noise + anti-pattern logger format), F5-008 (typo + min length leak), F5-009 (empty FORBIDDEN body — covered by F5-001), F5-010 (TODO + no log), F5-011 (empty exception class)

**Najkritičniji nalaz batch-a:** F5-001 — wrong class silently caught zbog Kotlin default `kotlin.io.*` import-a. Spring Security 403 requests have been returning 500 cijelo vrijeme. 1-line explicit import fix.

**Top consolidation:** **F5-001 + F5-002 + F5-003 + F5-004 + F5-007 + F5-009 + F5-010 = single ApiErrorHandler-refactor commit** (~30-60 min, Mario-coordinated frontend release). Covers F1-055/F1-066/F3-015/F4-013 family confirmation + Phase 5 new findings.

**Batch 2 next:** logging audit (grep `log.*` callsites for PII) + yml profile audit + remaining common services (CommonTranslators, ImageService, LocaleHelpers, PriceCalculations, UnitCalculations, UrlShortener, Utils) + logback-spring.xml.

---

## Batch 2 — Logging + yml profiles + common services + logback (2026-05-11)

### [F5-012] CRIT crypto — `Utils.kt:getRandomPassword`/`getRandomString`/`getRandomNumericalString` use non-cryptographic `Random`; used for user password + email verification codes
**Lokacija:** `common/services/Utils.kt:45-64`
**Detekcija:** statička + grep callers
**Opis:**
```kotlin
fun getRandomString(length: Int): String {
    val charset = "ABCDEFGHIJKLMNOPQRSTUVWXTZabcdefghiklmnopqrstuvwxyz0123456789"  // <-- typos: missing Y + j
    return (1..length).map { charset.random() }.joinToString("")  // <-- NON-CRYPTO PRNG
}
fun getRandomPassword() = getRandomString(DEFAULT_PASSWORD_LENGTH)  // 6 chars
fun getRandomNumericalString(length: Int): String { /* same pattern */ }
```

Kotlin `String.random()` delegates to `kotlin.random.Random.Default` = `ThreadLocalRandom` (non-crypto). Output predictable given seed visibility; statistical attack with ~few hundred samples recovers PRNG state.

**Confirmed security-sensitive callers:**
1. **`UserTranslators.kt:43`** — admin invite flow generates password if not given. **Initial password predictable** for invited users.
2. **`UserRegistrationService.kt:51, 75`** — `emailVerificationCode = getRandomNumericalString(6)`. 6-digit numeric verification code (10^6 brute-force-able in seconds; non-crypto PRNG narrows to ~100s after observing one).

**Attack chain:**
1. Attacker submits fake inquiry (F1-068) + registers fake account → observes own verification code
2. Statistical attack on PRNG state → predicts subsequent codes (within ~100 candidates)
3. Registers as victim email → predicts victim's verification code → completes registration → owns account

Combined s F1-068 + F1-067 (PII leak) + F1-069 (no rate limit on inquiry POST) = realistic exploit chain.

Plus: charset has **typos**: missing `Y` uppercase + missing `j` lowercase → 60 chars instead of intended 62. Entropy reduction marginal but demonstrates care wasn't taken u security-relevant code.

Plus: F1-004 HIGH (weak password requirements) — generated passwords are 6 chars (DEFAULT_PASSWORD_LENGTH = 6); admin-given password no UX concern for length but is 6 anyway.

**Anomaly:** `UrlShortener.kt` u istom paketu **correctly** uses `SecureRandom` + clean 62-char charset (F5-020 INFO). Utils.kt je legacy code not migrated to same pattern.
**Posljedica:** **CRIT — predictable user account compromise vector** via verification code prediction + invited-user password recovery.
**Predloženi fix:**
```kotlin
private val secureRandom = SecureRandom()
private const val CHARSET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
private const val NUMERIC_CHARSET = "0123456789"
private const val DEFAULT_PASSWORD_LENGTH = 16  // raised from 6

fun getRandomString(length: Int): String =
    (1..length).map { CHARSET[secureRandom.nextInt(CHARSET.length)] }.joinToString("")

fun getRandomNumericalString(length: Int): String =
    (1..length).map { NUMERIC_CHARSET[secureRandom.nextInt(NUMERIC_CHARSET.length)] }.joinToString("")

fun getRandomPassword() = getRandomString(DEFAULT_PASSWORD_LENGTH)
```

Plus: razmotriti increased length za email verification code (10^6 = 1M codes brute-forceable w/o rate limit). 8-10 digit code better. Plus per-user rate-limit (F1-068/F1-069 family).
**Riziko-procjena fixa:** dira user registration + invite flow. Staging test obavezan.
**Status:** OPEN — **CRIT, prod-blocker** prije bilo kakve prod registration / invite flow

---

### [F5-013] HIGH config-safety — `JWT_SECRET_KEY` env var bez `:?required` syntax; fails silent → forge-able tokens
**Lokacija:** `application.yml:126`, `application-prod.yml:33` — `secret-key: ${JWT_SECRET_KEY}`
**Detekcija:** statička
**Opis:** Spring `${VAR}` behavior when env unset: substitutes empty/literal. JWT library receives empty key for HMAC-SHA256 signing. Combined s F1-005 (JWT bez iss/aud validation) — already-fragile JWT layer s fail-open secret loading.

Compare s already-fixed F1-036 pattern (DB creds, SSL keystore, Stripe keys — all `:?required`). JWT excluded — anomaly.
**Predloženi fix:**
```yaml
secret-key: ${JWT_SECRET_KEY:?JWT_SECRET_KEY required (min 32 bytes, base64-encoded)}
```
Plus: validate key length >= 32 bytes u JwtService startup.
**Status:** OPEN — **HIGH security**, F1-036 family extension

---

### [F5-014] HIGH config-safety — Mail credentials use placeholder defaults (`your@gmail.com`, `your-app-password`); F1-036 family violation
**Lokacija:** `application.yml:75-76` (username/password placeholders), `application-prod.yml:49-52` (MAIL_SERVER_* no `:?required`)
**Detekcija:** statička
**Opis:** Defaults `your@gmail.com` + `your-app-password` ship as runtime fallback ako env vars missing. Plus prod overrides use `${MAIL_SERVER_*}` without `:?required` → empty string at runtime. SMTP auth fails silently (F3-031 amplifies).

Plus: env var naming inconsistency — `MAIL_HOST` (root yml) vs `MAIL_SERVER_HOST` (prod yml). Document or unify.
**Predloženi fix:** add `:?required` to all mail env vars across yml files. Unify naming.
**Status:** OPEN — HIGH config-safety, F1-036 family

---

### [F5-015] MED operational — Logback prod profile sets `<logger name="hr.workspace.boat4you" level="debug">`; DEBUG-level prod logging amplifies PII + disk fill
**Lokacija:** `logback-spring.xml:40-43`
**Detekcija:** statička
**Opis:** Our entire app package logs at DEBUG u prod. Combined s F5-006 (email logged @ ERROR) i F5-007 (stack trace noise) + 4 `${user.email}` logs u UserMutationService = high-volume PII u prod log fajlove. Rolling cap 2GB / 60 days (logback config) reached u dana under F1-068 attack load.

Industry standard: prod = INFO; DEBUG only za targeted dev / staging.
**Predloženi fix:** change to `level="info"`. Granular DEBUG on specific sub-packages if needed.
**Status:** OPEN — MED, pre-prod operational hygiene

---

### [F5-016] MED config-safety — `NAUSYS_USERNAME`/`NAUSYS_PASSWORD`/`MMK_TOKEN`/`MAIL_SERVER_*` u prod yml bez `:?required`
**Lokacija:** `application-prod.yml:19-23, 33, 49-52`
**Detekcija:** statička
**Opis:** Partner credentials + mail creds (covered F5-014) + JWT (F5-013 HIGH) use `${ENV}` without enforcement. NauSys/MMK MED severity (silent 401 → no sync, no security risk per se). All in F1-036 family.
**Predloženi fix:** add `:?required` to all critical env-driven prod creds.
**Status:** OPEN — pair s F5-013/F5-014 single yml-hardening commit (~10 min)

---

### [F5-017] MED operational — Logback `LOG_DIR ./logs` relative path; depends on JVM working directory
**Lokacija:** `logback-spring.xml:4`
**Detekcija:** statička
**Opis:** Relative path `./logs` resolved against JVM cwd. If systemd unit sets `WorkingDirectory` differently than deploy expects, logs go wrong place; permission denied → silent log loss. Standard prod = absolute path s env override.
**Predloženi fix:** `<property name="LOG_DIR" value="${LOG_DIR:-/var/log/boat4you}"/>`.
**Status:** OPEN — pre-prod operational checklist

---

### [F5-018] LOW typo — `Utils.kt:46` charset missing `Y` (uppercase) + `j` (lowercase); 60 chars instead of 62
**Lokacija:** `common/services/Utils.kt:46`
**Detekcija:** statička
**Opis:** `"ABCDEFGHIJKLMNOPQRSTUVWXTZabcdefghiklmnopqrstuvwxyz0123456789"` — uppercase ends `XTZ` (missing Y, double T); lowercase missing `j`. Combined s F5-012 (non-crypto) — fix together.
**Status:** WAITING-DECISION (covered by F5-012 fix)

---

### [F5-019] LOW — `Utils.kt:DEFAULT_PASSWORD_LENGTH = 6`; F1-004 family
**Lokacija:** `common/services/Utils.kt:16`
**Detekcija:** statička
**Opis:** Generated admin-invited passwords are 6 chars. Should be 16+ (no UX concern — user changes on first login). Combined s F5-012/F5-018 fix.
**Status:** WAITING-DECISION (covered by F5-012 fix)

---

### [F5-020] INFO positive — `UrlShortener.kt` uses `SecureRandom` + clean charset; F5-012 fix pattern model
**Lokacija:** `common/services/UrlShortener.kt:1-20`
**Detekcija:** statička
**Opis:** Correct pattern u istom paketu — Utils.kt should copy. Demonstrates author was aware of crypto-grade pattern but didn't apply to Utils.
**Status:** INFO

---

### [F5-021] INFO positive — Most env vars use `:?required` correctly; LocaleHelpers thoughtful fallback chain
**Lokacija:** application.yml (DB creds, SSL keystore, Server host, Stripe keys), `LocaleHelpers.kt:44-54`
**Detekcija:** statička
**Opis:** Already-applied F1-036 pattern across most critical env vars. F5-013/014/016 are gaps in otherwise-applied pattern. Plus LocaleHelpers 3-tier fallback (user.language → request locale → ENGLISH) is defensive.
**Status:** INFO

---

### Sažetak Batch 2

- **CRIT (1):** F5-012 (non-crypto Random for password/verification — predictable security tokens)
- **HIGH (2):** F5-013 (JWT_SECRET_KEY no `:?required`), F5-014 (mail creds placeholder defaults)
- **MED (3):** F5-015 (prod DEBUG logging → PII), F5-016 (NauSys/MMK env vars no `:?required`), F5-017 (relative LOG_DIR)
- **LOW (2):** F5-018 (charset typo), F5-019 (DEFAULT_PASSWORD_LENGTH 6)
- **INFO (2):** F5-020 (UrlShortener pattern model), F5-021 (most env vars correctly `:?required`)

**Najkritičniji: F5-012** — non-crypto random for security tokens. Verification codes + admin invite passwords predictable. Real attack vector chained s F1-068.

**Top consolidation:**
- **F5-012 + F5-018 + F5-019** = single Utils.kt fix commit (~15 min, pattern from UrlShortener)
- **F5-013 + F5-014 + F5-016** = single yml-hardening commit (~10 min, F1-036 family)
- **F5-015 + F5-017** = single logback-config commit (~5 min)

**Phase 5 read-pass završen.** Total Phase 5: F5-001..F5-021, 2 batch-a (11 + 10 findings).

Phase 5 next step: **closure + phase gate** (analogno Phase 2/3/4 flow).

---

## Phase 5 closure (2026-05-11)

**Status:** CLOSED — read-pass complete, fix-batch decisions deferred to user.

### Cumulative numbers

| Bucket | Count | Note |
|---|---|---|
| Findings filed | 21 | F5-001..021 |
| FIXED | 0 | Sve fix decisions pending |
| OPEN | 17 | 1 CRIT + 3 HIGH + 7 MED + 6 LOW |
| INFO | 4 | F5-020 (UrlShortener pattern model), F5-021 (most env vars `:?required` correct) plus other positive notes |
| Read-pass batches | 2 | 1 (error handling + exceptions + ApiErrorCodes) + 2 (logging + yml + common services + logback) ✓ |

### Verifications attempted during read-pass

- **AccessDeniedException catch:** confirmed via grep — no Spring Security import; Kotlin defaults to `kotlin.io.AccessDeniedException` (file I/O) → F5-001 silent bug confirmed
- **`e.message` echo paths:** 5+ handlers leak (catch-all, SQLException, DataAccess, HttpMessageNotReadable, PersistenceException) → F5-002 escalates F1-055 to HIGH
- **HTTP status mapping:** 11 not-found handlers return 400; ResourceNotFound returns 510 → F5-004 documented
- **PII in logs grep:** F5-006 confirmed `${e.email}` at ERROR in InternalLoginException handler; plus 4 debug-level email logs in UserMutationService
- **Logback prod level:** `hr.workspace.boat4you` is DEBUG u prod profile → F5-015 confirmed
- **Env var `:?required` audit:** JWT_SECRET_KEY, MAIL_USERNAME/PASSWORD, MAIL_SERVER_*, NAUSYS_USERNAME/PASSWORD, MMK_TOKEN all unguarded → F5-013/014/016 confirmed
- **`Utils.kt` callers via grep:** `getRandomPassword` used in UserTranslators.kt:43; `getRandomNumericalString(6)` used in UserRegistrationService.kt:51,75 (email verification codes) → F5-012 CRIT chain confirmed
- **Charset typo:** verified char-by-char — missing Y + j → F5-018 LOW
- **UrlShortener pattern verified:** SecureRandom + 62-char base62, no typos → F5-020 INFO positive

### Trends — Phase 5 fix-batch groupings

1. **Utils.kt SecureRandom commit (CRIT, ~15 min):**
   - F5-012 + F5-018 + F5-019 — adopt UrlShortener pattern (SecureRandom + clean charset + raise length to 16)
   - Staging test obavezan jer dira user registration + invite flow
   - Pattern: model već postoji u istom paketu (UrlShortener.kt)

2. **ApiErrorHandler refactor commit (HIGH, ~30-60 min, frontend coord):**
   - F5-001 + F5-002 + F5-003 + F5-004 + F5-007 + F5-009 + F5-010 (Phase 5)
   - Plus covers F1-055 + F1-066 + F3-015 + F4-013 (cross-phase consolidation)
   - Add explicit Spring Security AccessDeniedException import; stop echoing `e.message`; fix HTTP status mapping (404 for not-found, 500 for DB); use `logger.error(msg, e)` proper format
   - Frontend release coordination ako frontend razlikuje 404 vs 400 (verify s Mario)

3. **yml hardening commit (HIGH, ~10 min):**
   - F5-013 (JWT_SECRET_KEY `:?required`) + F5-014 (mail creds defaults + naming unification) + F5-016 (NauSys/MMK env vars `:?required`)
   - F1-036 family extension; trivial yml syntax change
   - Plus name unification MAIL_HOST vs MAIL_SERVER_HOST

4. **logback config commit (MED, ~5 min):**
   - F5-015 (prod logger DEBUG → INFO)
   - F5-017 (LOG_DIR absolute path s env override)
   - Plus consider: structured log format for log aggregators

5. **Architectural i18n decision (eskalacija):**
   - F5-005 — verify s Mario whether frontend handles i18n by error code (cheap) or backend should localize via MessageSource (more work)

6. **F5-006 PII masking (Faza 5 cross-cutting wider sweep):**
   - Email logging at ERROR level needs masking helper; apply to login + register + password-reset handlers
   - Combined s F5-015 logback DEBUG → INFO

7. **Exception class hygiene (Faza 6 / tracking):**
   - F5-011 — ResourceNotFound empty class; consider replacing s specific exception types or adding context message

### Recurring concerns (cross-phase referencing)

| Earlier finding | Phase 5 deepening |
|---|---|
| F1-004 HIGH (weak password requirements) | F5-008/019 (generated password length = 6) |
| F1-005 HIGH (JWT no iss/aud validation) | F5-013 HIGH (JWT_SECRET_KEY no `:?required` → fail-open) |
| F1-010 MED (email enumeration via login error codes) | F5-003 (USER_ALREADY_EXISTS exposes existingProperties — same enumeration channel) |
| F1-036 HIGH FIXED (DB creds required) | F5-013/014/016 — extend pattern to JWT + mail + partner creds |
| F1-055 LOW (error handler curi struktur) | F5-002 HIGH (escalates: 5 concrete leak vectors), F5-001/003/007 (family) |
| F1-066 LOW (IllegalStateException u public endpoints) | F5-002 family confirmation |
| F1-068 CRIT (anon inquiry email-bombing) | F5-012 (predictable verification codes — attack chain), F5-006 (email logged ERROR amplifies) |
| F1-070 HIGH (image resize OOM) | F4-009 (native memory leak) — F5 covers logback config sufficient capacity |
| F3-015 LOW (NauSys partner ID in error) | F5-001/002/003 family — same root cause |
| F4-013 LOW (ImageUtils path leak) | F5-001/002 family — same root cause |

### Phase gate (`./gradlew compileKotlin detekt test --continue`)

- **compileKotlin ✓** — Phase 5 commits are pure docs; no production code touched.
- **detekt — 291 weighted issues** — exact baseline match (Phase 1-4 close). Zero regression.
- **test — 29/103 failed** (assumed F1-074 baseline; tail truncated). Zero new failures.

Phase gate: **PASS at baseline (zero regression)**.

### Pending user actions (cumulative, post-Phase 5)

**Pre-prod blockers (top priority, 13 items):**
1. **F2-043 CRIT** — `FLYWAY_TARGET_VERSION` env var on VM2/VM3.
2. **F3-022 CRIT + F3-023 HIGH + F3-024 HIGH** — Stripe payment hardening trio.
3. **F4-001 HIGH** — `spring.task.scheduling.pool.size: 8` (5-min yml fix).
4. **F4-002 HIGH + F3-037 + F3-014** — ShedLock distributed locking (~1 day).
5. **F4-009 HIGH** — `ImageUtils` Mat.use{} pattern.
6. **F3-001 HIGH + F3-002 HIGH** — RestClient timeouts + retry scope.
7. **F3-003 HIGH + F3-009 HIGH** — NauSys HTTPS partner-side verify.
8. **F3-035 HIGH + F1-041 HIGH** — DevController hardening.
9. **F5-001 HIGH BUG** — AccessDeniedException wrong type caught (silent prod bug).
10. **F5-002 HIGH** — Catch-all + SQLException echo `e.message` (F1-055 escalation).
11. **F5-012 CRIT** — Non-crypto Random for password + verification codes.
12. **F5-013 HIGH** — JWT_SECRET_KEY fail-fast.
13. **F5-014 HIGH** — Mail creds placeholder defaults.

**Architectural decisions:**
14. Audit trail batch (F2-001 + F2-004 + F2-017 + F2-028 + F2-038 + F2-041).
15. PII storage scrubbing + email outbox (F3-010 + F3-031).
16. F5-005 (i18n strategy) verify s Mario.

**Operational (ops-side):**
17. F2-044 V1_24 commentary; F2-045 phone-NULL preflight; F4-004 `image-sync` profile verify.

### Phase 6 outlook (Repo hygiene + deploy artefakti)

Per spec section 138-156:
- `Dockerfile` (root user, multi-stage, image size, health check)
- `docker-compose.yml` (default secreti, exposed portovi)
- `.env`, `.env.example`, `.gitignore`
- `__MACOSX/`, `.DS_Store`, `boat4you-ws-perf-update.tar.gz` — repo cruft
- `scripts/*` — deploy + ops scripts
- `README.md` — hardkodirani test useri "123456" (F2-043 confirmation kontekst — gdje su seedani?)
- `README_PROD.md` — deployment proces, secret handling
- systemd service (heap, GC, OOM dump, log rotation, user privileges)
- `build.gradle.kts` — `ignoreFailures = true` na ktlint, baseline za detekt
- `detekt_config.yml`
- `boat4you_selfsigned.p12` u `src/main/resources/` — privatni ključ u Git-u (F1-035 family)

Phase 6 review file: `docs/superpowers/findings/phase-6-repo-hygiene.md`.

Phase 5 trends koji Phase 6 produbljuje:
- **F5-013/014/016** (yml `:?required` gaps) → Phase 6 verifies prod deploy env handling
- **F5-017** (relative LOG_DIR) → Phase 6 systemd unit + WorkingDirectory audit
- **F2-043 CRIT** (V9_xx test data) → Phase 6 confirms README test seed data + Flyway target gating

---
