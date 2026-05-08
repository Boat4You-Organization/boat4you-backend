# Findings Register — Boat4You Pre-production Review

**Master indeks svih nalaza kroz sve faze.** Ažurira se na kraju svake faze.

Legend statusa:
- `OPEN` — nije adresirano, čeka odluku ili Faza-N fix
- `WAITING-DECISION` — trivijalan fix mogu napraviti, čekam tvoju potvrdu
- `OPS-VERIFY` — treba potvrda iz ops-side konfiguracije (nginx, env), out-of-repo
- `FIXED` — popravljeno, commit hash u koloni
- `DEFERRED` — odgodili smo svjesno (po fazi)
- `BLOCKED` — ovisi o drugom nalazu

---

## Faza 1 — Boundary / attack surface

**Status:** CLOSED 2026-05-08 (read pass + 20 trivial fixes + verifikacije + phase gate)

### CRIT (2)

| ID | Severity | Naslov | Status |
|---|---|---|---|
| F1-019 | CRIT | Stripe webhook nije idempotent; double-processing pri Stripe retry-ju | OPEN — blocker before go-live |
| F1-068 | CRIT | `/public/inquiries/{id}/send-test` email-bombing endpoint anonimno | OPEN — blocker before go-live (autor priznaje) |

### HIGH (15)

| ID | Severity | Naslov | Status |
|---|---|---|---|
| F1-002 | HIGH | Static OpenAPI YAML datoteke `permitAll()` u Spring Security configu | OPEN (yml-side fixed `2e451cc`; security-policy dio ostaje) |
| F1-003 | HIGH | Auth endpointi (login/register/reset/invite) bez per-IP rate limita | OPEN — OPS-VERIFY (postoji li nginx limit_req?) |
| F1-004 | HIGH | Slabi password requirementi (min 6 znakova, bez kompleksnosti, bez breach checka) | OPEN |
| F1-005 | HIGH | JWT bez `iss`/`aud` validacije; rola se ne re-fetcha iz DB | OPEN |
| F1-020 | HIGH | File upload validira samo Content-Type header, ne magic bytes | OPEN |
| F1-022 | HIGH | `PublicReservationRateLimiter` slijepo vjeruje X-Forwarded-For | OPEN — OPS-VERIFY (strip-a li nginx XFF?) |
| F1-024 | HIGH | `StripePaymentService.handleWebhookEvent` non-null assertions na user metadata | OPEN |
| F1-037 | HIGH | NAUSYS_URL default `http://ws.nausys.com` (HTTP, ne HTTPS) | OPEN |
| F1-041 | HIGH | DevEquipmentSyncController na `/public/dev` + samo profile gating (no auth) | OPEN |
| F1-056 | HIGH | `cancelReservation` non-atomic external delete + DB cancel; razdvojeno stanje moguće | OPEN |
| F1-057 | HIGH | `setPasswordForReservation` bez rate limita; reservationId enumeration | OPEN |
| F1-064 | HIGH | Public yacht search trigger-a synkroni external sync prema NauSys/MMK iz request thread-a | OPEN |
| F1-067 | HIGH | `/public/inquiries/{id}/email-preview` curi PII (autor: "before go-live") | OPEN — blocker before go-live |
| F1-069 | HIGH | `/public/inquiries` POST nema rate limita | OPEN |
| F1-070 | HIGH | `/public/image/{imageId}` resize bez validacije width/height (OOM/DoS) | OPEN — kandidat blocker |

### MED (19)

| ID | Severity | Naslov | Status |
|---|---|---|---|
| F1-001 | MED | CORS default `*` u kodu; mitigirano yml-om ali fragile | OPEN |
| F1-006 | MED | JWT secret format mismatch: docs hex, kod base64 | OPEN |
| F1-007 | MED | `removePrefix("Bearer")` case-sensitive | OPEN |
| F1-008 | MED | `JwtException` swallowed; `InternalLoginException` propada do 500 | OPEN |
| F1-010 | MED | Email enumeration kroz različite InternalLoginException error code-ove (verified) | OPEN — fix u Fazi 5 (cross-cutting) |
| F1-021 | MED | Path traversal kanonikalizacija (defense-in-depth, callers verified safe) | OPEN |
| F1-023 | MED | Rate limiter bucket map ne self-prune; memory leak | OPEN |
| F1-025 | MED | Stripe `initiatePayment` idempotency optional | OPEN |
| F1-026 | MED | Multiple `!!` non-null assertions u initiatePayment putu | OPEN |
| F1-028 | MED | `promoteReservationToBooking` može se izvršiti ponovno (zbog F1-019) | BLOCKED-BY F1-019 |
| F1-030 | MED | StripeWebhookController bez explicit max-size limita | OPEN |
| F1-033 | MED | Bez virus / malware skena na file uploadima | OPEN |
| F1-035 | MED | Self-signed `.p12` keystore u repu (prod NE koristi, ali leak privatnog ključa) | OPEN |
| F1-040 | MED | JWT access token expiration 24h (industrijski 15-60min) | DEFERRED-Faza5 |
| F1-053 | MED | Nedostaje HSTS header iz nginx-a | OPEN — OPS-VERIFY |
| F1-055 | MED | Globalni error handler curi internu put-strukturu u 500 response body | OPEN |
| F1-058 | MED | Repetitivni manual auth check; nedostaje class-level `@PreAuthorize` | OPEN |
| F1-062 | MED | Login timing attack: BCrypt samo ako user postoji | OPEN |
| F1-063 | MED | YachtController query param list size unvalidated | OPEN |
| F1-065 | MED | Document download Content-Disposition filename parcijalno saniran (samo `\"`) | OPEN |
| F1-071 | MED | `/secured/payments/stripe/create-checkout-session` bez ownership checka | OPEN |
| F1-072 | MED | `/public/payments/stripe/create-checkout-session` IDOR enumeration | OPEN |

### LOW (11)

| ID | Severity | Naslov | Status |
|---|---|---|---|
| F1-014 | LOW | Token table grow-and-no-cleanup; perf koncern | DEFERRED-Faza2 |
| F1-015 | LOW | BCrypt default cost (10), preporučeno 12 | DEFERRED (rotation overhead) |
| F1-018 | LOW | `// TODO Catch all exceptions` u refreshToken | OPEN |
| F1-027 | LOW | Generic RuntimeException("Stripe is not enabled") | OPEN |
| F1-034 | LOW | FileSystemService kanonikalizacija defense-in-depth (callers verified safe) | OPEN |
| F1-039 | LOW | Swagger gating defaultira na ON; prod env mora postaviti false | OPEN (mitigirano kroz F1-002 fix u prod yml-u) |
| F1-048 | LOW | nginx.conf TLS 1.0/1.1 (mitigirano za boat4you, samo default 404) | OPEN — OPS-VERIFY |
| F1-050 | LOW | nginx leak verzije (`server_tokens` zakomentiran) | OPEN — OPS-VERIFY |
| F1-051 | LOW | Default `_`-host nginx server block | OPEN — OPS-VERIFY |
| F1-066 | LOW | `IllegalStateException` s user-facing porukama u public endpointima | OPEN |
| F1-074 | LOW | `ReservationPaymentPhasesServiceTest` razilazi se s logikom servisa (29/103 fail) | OPEN — biz signoff (Mario) |

### INFO / POSITIVE (4)

| ID | Severity | Naslov |
|---|---|---|
| F1-052 | INFO | Let's Encrypt cert auto-renew preko Certbot-a (treba verificirati timer) |
| F1-054 | INFO | Spring Security default security headers aktivni (X-Frame-Options, X-Content-Type-Options, etc.) |
| F1-* | INFO | `ReservationController` ima ekselentne ownership guards — koristiti kao template |
| F1-* | INFO | GDPR endpointi imaju audit logging (mature dizajn) |

### FIXED (20)

| ID | Severity | Naslov | Commit |
|---|---|---|---|
| F1-049 | CRIT | Java app slušao na `*:8080`, izložen na public IP — bind na 127.0.0.1 | `02532a9` |
| F1-002 | HIGH | (yml-side) Disable swagger by default in prod profile | `2e451cc` |
| F1-036 | HIGH | DB credentials required (no literal default) | `ab5a210` |
| F1-009 | MED | Login/password exceptioni s context message-om | `7cc3b09` |
| F1-011 | MED | NPE risk na lastUnsuccessfulLogin u login lockout-u | `b43ae10` |
| F1-043 | MED | Rolling LocalDate.now() for MMK offer2 instead of hardcoded 2025-08-09 | `c7e1c2e` |
| F1-044 | MED | TestJobController → AdminJobController, drop dead code | `274dc5a` |
| F1-045 | MED | Audit response-body default false u root yml | `f7df7f9` |
| F1-046 | MED | SSL_KEYSTORE_PASSWORD required (no empty default) | `10c3c9b` |
| F1-059 | MED | runCatching onFailure logira email failure | `5d17a55` |
| F1-012 | LOW | Obrisan dead `findActiveByValue` | `2b631f1` |
| F1-016 | LOW | Drop hardcoded swagger server hosts | `d8feb1a` |
| F1-017 | LOW | Real OpenAPI title/description | `d8feb1a` |
| F1-029 | LOW | Sanitize paymentReference prije slanja na Stripe | `f17cc99` |
| F1-031 | LOW | Stripe webhook: distinguish signature vs general failure | `a7dbe8d` |
| F1-032 | LOW | validateImageBytes koristi maxFileSizeMb config | `82cc4da` |
| F1-042 | LOW | POST for state-changing /admin/mmk + /admin/nausys sync triggers | `c7e1c2e` |
| F1-047 | LOW | SERVER_HOST_PUBLIC required (no localhost default) | `0bc8025` |
| F1-060 | LOW | ReservationNotExistException umjesto orElseThrow() | `51dda3f` |
| F1-061 | LOW | Obrisan dead application.test.enabled config + komentar | `9b32c94` |
| F1-073 | LOW | YachtDoesNotExistException umjesto IllegalArgumentException | `b169ff1` |

---

## Total cumulative across all phases

| Severity | Faza 1 | Faza 2 | Faza 3 | Faza 4 | Faza 5 | Faza 6 | Faza 7 | TOTAL |
|---|---|---|---|---|---|---|---|---|
| CRIT | 2 | — | — | — | — | — | — | **2** |
| HIGH | 15 | — | — | — | — | — | — | **15** |
| MED | 19 | — | — | — | — | — | — | **19** |
| LOW | 11 | — | — | — | — | — | — | **11** |
| INFO | 4 | — | — | — | — | — | — | **4** |
| FIXED | 20 | — | — | — | — | — | — | **20** |
| DEFERRED | 3 | — | — | — | — | — | — | **3** |
| BLOCKED | 1 | — | — | — | — | — | — | **1** |
| **OPEN** | **47** | — | — | — | — | — | — | **47** |

Faza 1 STATUS: **CLOSED 2026-05-08.** 67 nalaza ukupno (66 pri kraju read passa + F1-074 iz phase gate-a). 20 fix-eva commit-ano (batch-1 + batch-2). compileKotlin ✓; detekt baseline 291 issues (pre-existing); 29/103 testova fail (pre-existing — F1-074). Korisnička akcija prije Faze 2: ops-verify F1-003 (gateway rate limit) i F1-022 (nginx XFF strip).
