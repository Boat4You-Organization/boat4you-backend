# Findings Register — Boat4You Pre-production Review

**Master indeks svih nalaza kroz sve faze.** Ažurira se na kraju svake faze.

Legend statusa:
- `OPEN` — nije adresirano, čeka odluku ili Faza-N fix
- `WAITING-DECISION` — trivijalan fix mogu napraviti, čekam tvoju potvrdu
- `FIXED` — popravljeno, commit hash u koloni
- `DEFERRED` — odgodili smo svjesno
- `BLOCKED` — ovisi o drugom nalazu

---

## Faza 1 — Boundary / attack surface

### CRIT (2)

| ID | Severity | Naslov | Status |
|---|---|---|---|
| F1-019 | CRIT | Stripe webhook nije idempotent; double-processing pri Stripe retry-ju | OPEN |
| F1-068 | CRIT | `/public/inquiries/{id}/send-test` email-bombing endpoint anonimno | OPEN |

### HIGH (16)

| ID | Severity | Naslov | Status |
|---|---|---|---|
| F1-002 | HIGH | Swagger pathovi permitAll u Spring Security; gating samo preko springdoc property | OPEN |
| F1-003 | HIGH | Auth endpointi (login/register/reset/invite) bez per-IP rate limita | OPEN |
| F1-004 | HIGH | Slabi password requirementi (min 6 znakova, bez kompleksnosti, bez breach checka) | OPEN |
| F1-005 | HIGH | JWT bez `iss`/`aud` validacije; rola se ne re-fetcha iz DB | OPEN |
| F1-020 | HIGH | File upload validira samo Content-Type header, ne magic bytes | OPEN |
| F1-022 | HIGH | `PublicReservationRateLimiter` slijepo vjeruje X-Forwarded-For | OPEN |
| F1-024 | HIGH | `StripePaymentService.handleWebhookEvent` non-null assertions na user metadata | OPEN |
| F1-036 | HIGH | Default DB credentials (`postgres:postgres` / `boat4you_app:boat4you_app`) u yml-u | OPEN |
| F1-037 | HIGH | NAUSYS_URL default `http://ws.nausys.com` (HTTP, ne HTTPS) | OPEN |
| F1-041 | HIGH | DevEquipmentSyncController na `/public/dev` + samo profile gating (no auth) | OPEN |
| F1-056 | HIGH | `cancelReservation` non-atomic external delete + DB cancel; razdvojeno stanje moguće | OPEN |
| F1-057 | HIGH | `setPasswordForReservation` bez rate limita; reservationId enumeration | OPEN |
| F1-064 | HIGH | Public yacht search trigger-a synkroni external sync prema NauSys/MMK iz request thread-a | OPEN |
| F1-067 | HIGH | `/public/inquiries/{id}/email-preview` curi PII (autor: "before go-live") | OPEN |
| F1-069 | HIGH | `/public/inquiries` POST nema rate limita | OPEN |
| F1-070 | HIGH | `/public/image/{imageId}` resize bez validacije width/height (OOM/DoS) | OPEN |

### MED (25)

| ID | Severity | Naslov | Status |
|---|---|---|---|
| F1-001 | MED | CORS default `*` u kodu; mitigirano yml-om ali fragile | OPEN |
| F1-006 | MED | JWT secret format mismatch: docs hex, kod base64 | OPEN |
| F1-007 | MED | `removePrefix("Bearer")` case-sensitive | OPEN |
| F1-008 | MED | `JwtException` swallowed; `InternalLoginException` propada do 500 | OPEN |
| F1-009 | MED | Exception klase bez `super(message)`; logging gubi context | WAITING-DECISION |
| F1-010 | MED | Email enumeration kroz različite InternalLoginException tipove | OPEN |
| F1-011 | MED | Login lockout NPE risk (`!!` na lastUnsuccessfulLogin) | WAITING-DECISION |
| F1-021 | MED | Path traversal kanonikalizacija (defense-in-depth, callers OK) | OPEN |
| F1-023 | MED | Rate limiter bucket map ne self-prune; memory leak | OPEN |
| F1-025 | MED | Stripe `initiatePayment` idempotency optional | OPEN |
| F1-026 | MED | Multiple `!!` non-null assertions u initiatePayment putu | OPEN |
| F1-028 | MED | `promoteReservationToBooking` može se izvršiti ponovno (zbog F1-019) | BLOCKED-BY F1-019 |
| F1-030 | MED | StripeWebhookController bez explicit max-size limita | OPEN |
| F1-033 | MED | Bez virus / malware skena na file uploadima | OPEN |
| F1-035 | MED | Self-signed `.p12` keystore u repu (prod NE koristi, ali leak privatnog ključa) | OPEN |
| F1-040 | MED | JWT access token expiration 24h (industrijski 15-60min) | WAITING-DECISION |
| F1-043 | MED | `MmkSyncController.offer2` hardkodiran datum 2025-08-09 | WAITING-DECISION |
| F1-044 | MED | `TestJobController` u prod kodu, krivi package, dead-code komentar | WAITING-DECISION |
| F1-045 | MED | `audit.response-body: true` default; PII u audit log | WAITING-DECISION |
| F1-046 | MED | `SSL_KEYSTORE_PASSWORD` default empty string | OPEN |
| F1-053 | MED | Nedostaje HSTS header iz nginx-a | OPEN |
| F1-055 | MED | Globalni error handler curi internu put-strukturu u 500 response body | OPEN |
| F1-058 | MED | Repetitivni manual auth check; nedostaje class-level `@PreAuthorize` | OPEN |
| F1-059 | MED | Empty `runCatching{...}.onFailure{}` proguta exception bez logiranja | WAITING-DECISION |
| F1-062 | MED | Login timing attack: BCrypt samo ako user postoji | OPEN |
| F1-063 | MED | YachtController query param list size unvalidated | OPEN |
| F1-065 | MED | Document download Content-Disposition filename parcijalno saniran (samo `\"`) | OPEN |
| F1-071 | MED | `/secured/payments/stripe/create-checkout-session` bez ownership checka | OPEN |
| F1-072 | MED | `/public/payments/stripe/create-checkout-session` IDOR enumeration | OPEN |

### LOW (18)

| ID | Severity | Naslov | Status |
|---|---|---|---|
| F1-012 | LOW | `findActiveByValue` dead code; misleading naming | WAITING-DECISION |
| F1-014 | LOW | Token table grow-and-no-cleanup; perf koncern | DEFERRED-Faza2 |
| F1-015 | LOW | BCrypt default cost (10), preporučeno 12 | WAITING-DECISION |
| F1-016 | LOW | Hardkodirani server hosts u OpenAPI configu | WAITING-DECISION |
| F1-017 | LOW | Generic OpenAPI title i description placeholder | WAITING-DECISION |
| F1-018 | LOW | `// TODO Catch all exceptions` u refreshToken | OPEN |
| F1-027 | LOW | Generic RuntimeException("Stripe is not enabled") | OPEN |
| F1-029 | LOW | Stripe paymentRef neuredan input → bank statement | WAITING-DECISION |
| F1-031 | LOW | Webhook catch-all exception zatamnjuje razlog | WAITING-DECISION |
| F1-032 | LOW | `validateImageBytes` hardkodiran 15MB umjesto config | WAITING-DECISION |
| F1-034 | LOW | FileSystemService kanonikalizacija defense-in-depth | OPEN |
| F1-039 | LOW | Swagger gating defaultira na ON; prod env mora postaviti false | OPEN |
| F1-042 | LOW | Mmk/NausysSyncController GET za state-changing | WAITING-DECISION |
| F1-047 | LOW | `server.host-public` default `http://localhost:5173` u password reset | WAITING-DECISION |
| F1-048 | LOW | nginx.conf TLS 1.0/1.1 (mitigirano za boat4you, samo default 404) | OPEN |
| F1-050 | LOW | nginx leak verzije (`server_tokens` zakomentiran) | WAITING-DECISION |
| F1-051 | LOW | Default `_`-host nginx server block | WAITING-DECISION |
| F1-060 | LOW | `findById(id).orElseThrow()` bez supplier-a → 500 | WAITING-DECISION |
| F1-061 | LOW | `application.test.enabled` postavljen u yml, nigdje se ne čita | WAITING-DECISION |
| F1-066 | LOW | `IllegalStateException` s user-facing porukama u public endpointima | OPEN |
| F1-073 | LOW | `IllegalArgumentException("Yacht not found")` umjesto domain ex. | WAITING-DECISION |

### INFO / POSITIVE (4)

| ID | Severity | Naslov |
|---|---|---|
| F1-052 | INFO | Let's Encrypt cert auto-renew preko Certbot-a (treba verificirati timer) |
| F1-054 | INFO | Spring Security default security headers aktivni (X-Frame-Options, X-Content-Type-Options, etc.) |
| F1-* | INFO | `ReservationController` ima ekselentne ownership guards — koristiti kao template |
| F1-* | INFO | GDPR endpointi imaju audit logging (mature dizajn) |

### FIXED (1)

| ID | Severity | Naslov | Commit |
|---|---|---|---|
| F1-049 | CRIT | Java app slušao na `*:8080`, izložen na public IP — **bind na 127.0.0.1** | `02532a9` |

---

## Total cumulative across all phases

| Severity | Faza 1 | Faza 2 | Faza 3 | Faza 4 | Faza 5 | Faza 6 | Faza 7 | TOTAL |
|---|---|---|---|---|---|---|---|---|
| CRIT | 2 | — | — | — | — | — | — | **2** |
| HIGH | 16 | — | — | — | — | — | — | **16** |
| MED | 25 | — | — | — | — | — | — | **25** |
| LOW | 18 | — | — | — | — | — | — | **18** |
| INFO | 4 | — | — | — | — | — | — | **4** |
| FIXED | 1 | — | — | — | — | — | — | **1** |
| **OPEN** | **65** | — | — | — | — | — | — | **65** |
