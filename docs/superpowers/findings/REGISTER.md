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
| F1-003 | HIGH | Auth endpointi (login/register/reset/invite) bez per-IP rate limita | DEFERRED-Faza7 — confirmed missing on VM2 nginx (2026-05-08) |
| F1-004 | HIGH | Slabi password requirementi (min 6 znakova, bez kompleksnosti, bez breach checka) | OPEN |
| F1-005 | HIGH | JWT bez `iss`/`aud` validacije; rola se ne re-fetcha iz DB | OPEN |
| F1-020 | HIGH | File upload validira samo Content-Type header, ne magic bytes | OPEN |
| F1-022 | HIGH | `PublicReservationRateLimiter` slijepo vjeruje X-Forwarded-For | DEFERRED-Faza7 — confirmed scenario C ($proxy_add_x_forwarded_for) on VM2 (2026-05-08) |
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
| F1-053 | MED | Nedostaje HSTS header iz nginx-a | DEFERRED-Faza7 (nginx batch) |
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
| F1-048 | LOW | nginx.conf TLS 1.0/1.1 (mitigirano za boat4you, samo default 404) | DEFERRED-Faza7 (nginx batch) |
| F1-050 | LOW | nginx leak verzije (`server_tokens` zakomentiran) | DEFERRED-Faza7 (nginx batch) |
| F1-051 | LOW | Default `_`-host nginx server block | DEFERRED-Faza7 (nginx batch) |
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

## Faza 2 — Data layer (persistence, entities, migrations)

**Status:** CLOSED 2026-05-11 (read-pass kroz 7 batch-eva + 3 fixes + closure summary + phase gate at baseline). 50 findings: 3 FIXED, 44 OPEN (1 CRIT + 1 HIGH + 19 MED + 23 LOW), 3 INFO. **Gate: zero regression** (compileKotlin clean, detekt 291 baseline, test 29/103 baseline — sve F1-074). Pending user action: F2-043 (`FLYWAY_TARGET_VERSION` env var verify), F2-044 (V1_24 Mario commentary), arhitektonska odluka za audit-trail batch.

### CRIT (1)

| ID | Severity | Naslov | Status |
|---|---|---|---|
| F2-043 | CRIT | V9_xx test data migracije run-aju u prod-u bez `FLYWAY_TARGET_VERSION` override-a; insertira 15+ Workspace.hr team usera s shared bcrypt hash | OPEN — **prod-blocker; verify env var** |

### HIGH (1)

| ID | Severity | Naslov | Status |
|---|---|---|---|
| F2-044 | HIGH | `V1_24__drop_columns.sql` destructive DROP COLUMN bez rationale komentara — code reviewer ne može verify safety | OPEN — 30min Mario commentary |

### MED (16)

| ID | Severity | Naslov | Status |
|---|---|---|---|
| F2-001 | MED | `creatorId`/`modifierId` audit kolone se nikad ne popunjavaju (AbstractEntity TODO) | OPEN — eskalacija (architectural decision) |
| F2-004 | MED | `CustomRevisionListener` hardkodira `modifier_user_id = 1L` — audit log laže | OPEN — eskalacija (uz F2-001) |
| F2-011 | MED | `findAllAdminEmailAddresses` ne filtrira `deleted_at IS NULL` (potencijalni GDPR breach) | OPEN — verify GDPR delete flow prvo |
| F2-013 | MED | `TokenEntity.@ManyToOne user` EAGER — fetched na svaki auth request | OPEN — Faza 5 (cross-cutting perf) |
| F2-014 | MED | `findAllValidTokenByUserId` koristi OR umjesto AND — "valid" semantika netočna | WAITING-DECISION |
| F2-016 | MED | `RoleAssignmentEntity` ima EAGER user i role @ManyToOne → auth path N+1 | OPEN — Faza 5 |
| F2-021 | MED | `findForReplacementSearch` vs `countForReplacementSearch` divergentne WHERE klauzule | OPEN — Faza 5 (test coverage) ili tracking-only |
| F2-023 | MED | `InquiryRepository.findAllByParamsForAdmin` triple leading-wildcard LIKE = full table scan (F1-068 DoS multiplier) | OPEN — Faza 6 (index migracije) |
| F2-024 | MED | `countByEmailIgnoreCaseAndIdNot` poziva se per inquiry create, bez funkcionalnog `LOWER(email)` indeksa | OPEN — Faza 6 (vezano za F2-023) |
| F2-026 | MED | `OfferPaymentPlan.equals/hashCode` na mutable poljima u `MutableSet` na Offer-u → broken Set invariant | OPEN — eskalacija (entity contract change) |
| F2-030 | MED | `AgencyRepository`: 3× JOIN FETCH na kolekciju bez DISTINCT (cartesian product preko žice) + 4. ima F2-023/F2-029 pattern | WAITING-DECISION (3× DISTINCT trivijalno) |
| F2-031 | MED | `Agency.agencySources` EAGER OneToMany + `Page<Agency>` admin = N+1 per page | OPEN — Faza 5 (perf + runtime verify) |
| F2-033 | MED | Public location autocomplete (`LocationViewRepository.findByNameAndIdsNotIn`) LOWER+leading-wildcard LIKE = seq scan na svaki public search | OPEN — Faza 6 (vezano za F2-023/F2-024/F2-034) |
| F2-036 | MED | `ReservationPaymentPhase.equals` null-bug + mutable Set anti-pattern (F2-026 sibling); F1-019 multiplier za payment double-capture | OPEN — eskalacija (entity contract change, F2-026 family) |
| F2-037 | MED | `calculateTotalPaid` JPQL SUM null → Kotlin `BigDecimal` non-null NPE risk | WAITING-DECISION (trivial COALESCE) |
| F2-038 | MED | `ReservationDocument` audit gap — signed contracts + internal admin docs nemaju tamper-evidence trail | OPEN — eskalacija (F2-001/F2-004 dependency + legal compliance) |
| F2-045 | MED | `V1_64` SET NOT NULL bez UPDATE safety net — prod deploy fail-a na NULL phone redu | OPEN — pre-prod operational checklist |
| F2-046 | MED | `V1_57` hardkodira ordinal payment_type values koje V1_90 kasnije mapira u STRING — drift risk | OPEN — tracking-only (already applied) |
| F2-047 | MED | `V1_69`/`V1_70` country.id == location.id numerical overlap assumption — fragile, no constraint | OPEN — Faza 6 (data model documentation) |

### LOW (23)

| ID | Severity | Naslov | Status |
|---|---|---|---|
| F2-002 | LOW | `@Audited` na `AbstractEntity` + `store_data_at_delete:true` → `_revisions` rastu za sve | OPEN — Faza 2 follow-up |
| F2-003 | LOW | `entity_status` soft-delete pattern nije dokumentiran ni centraliziran | OPEN — Faza 5 (cross-cutting modeling) |
| F2-005 | LOW | `dataSyncCacheManagerCustomizer` nije profile-gated | WAITING-DECISION |
| F2-006 | LOW | Single-entry cache thrashing kandidati (`usedVesselTypesCache` itd.) | OPEN — verify u Batch 3 |
| F2-007 | LOW | `yachtExtrasCache` deklariran s key `Yacht::class.java` umjesto `Long` | OPEN — verify callers |
| F2-008 | LOW | Većina config cache-eva ima 10h TTL bez explicit `@CacheEvict` na admin mutaciji | OPEN — verify admin mutation servisa |
| F2-009 | LOW | `UserEntity.@Formula("concat(name,' ',surname)")` se loada u svaki SELECT (deprecated) | WAITING-DECISION |
| F2-010 | LOW | `UserRepository.findByEmail` JOIN FETCH bez DISTINCT | WAITING-DECISION |
| F2-012 | LOW | `findAllByBirthdayMonthDay` native query bez funkcionalnog indeksa | OPEN — defer Faza 6 |
| F2-015 | LOW | `revokeAllUserTokens` N+1 update umjesto bulk UPDATE | OPEN — Faza 5 (perf + audit decision) |
| F2-017 | LOW | `Yacht`/`YachtImage`/`YachtTranslation` ne extendaju `AbstractEntity` — nema auditа | OPEN — eskalacija (velik refactor + migracija) |
| F2-020 | LOW | `findWithReservationOptionsByAgency` JOIN FETCH bez DISTINCT | WAITING-DECISION |
| F2-025 | LOW | `offersByYachtAndStatusCache` key tip `Yacht` entity (sibling F2-007) — cache praktički nije korišten | OPEN — fix paralelno s F2-007 |
| F2-027 | LOW | JPA `orphanRemoval=true` vs DB `OnDelete SET_NULL` na istom FK → orphan rows pri direct SQL delete-u | OPEN — Faza 6 (data integrity sweep) |
| F2-028 | LOW | Offer/OfferExtra/OfferPaymentPlan/Inquiry/CustomYachtDetail/CustomOffer/ReservationOption ne extendaju AbstractEntity (proširenje F2-017) | OPEN — eskalacija (architectural decision) |
| F2-029 | LOW | `STR(:search)` JPQL funkcija redundantna u `findAllByParamsForAdmin` | WAITING-DECISION |
| F2-032 | LOW | `LocationViewRepository` declares `JpaRepository<_, Long>` ali `LocationView.id` je String | WAITING-DECISION |
| F2-034 | LOW | LOWER+LIKE familija u Manufacturer/Model/Agency admin/Location (low-frequency siblings F2-023/F2-033) | OPEN — Faza 6 (jedna index migracija) |
| F2-039 | LOW | `ReservationFlowRepository.findIdsInReservationFlowChain` recursive CTE bez cycle detection — corruption loop diverges | OPEN — Faza 6 (defensive coding) ili tracking-only |
| F2-040 | LOW | `ReservationViewRepository.findAllReservationsByParams` 6-column LOWER+LIKE admin search (F2-023 family) | OPEN — Faza 6 (vezano s F2-023/F2-024/F2-033/F2-034) |
| F2-041 | LOW | `ReservationFlow.status` TODO + ReservationFlow/Document/ExternalReservationPaymentPlan ne extendaju AbstractEntity (F2-028 family) | OPEN — eskalacija (F2-028 architectural decision) |
| F2-048 | LOW | `V1_54`/`V1_60`/`V1_67` recreate yacht_search_view s hardkodiranim `o.status <> 4` (superseded by V1_90+R__1_03) | OPEN — tracking-only / convention note |
| F2-049 | LOW | `V1_88` dedup regex normalization drift s `ManufacturerAliasResolver.kt` Kotlin ekvivalentom | OPEN — Faza 6 (drift-prevention pattern) |

### FIXED (3)

| ID | Severity | Naslov | Commit |
|---|---|---|---|
| F2-018 | MED | Migracija svih `@Enumerated` ORDINAL → STRING (18 enum kolona, 22 entity polja, V1_90 + R__ views) | `0d1242a` |
| F2-019 | MED | Native queryji u `YachtRepository` + service callers prelaze na enum.name() string literale | `0d1242a` |
| F2-022 | HIGH | Scheduled cleanup native/JPQL queryji koriste PostgreSQL `CURRENT_DATE - INTERVAL` + `:cutoff` parameter | `0dc514f` |

---

## Faza 3 — Vanjske integracije (NauSys, MMK, Stripe, mail, HTTP klijenti)

**Status:** CLOSED 2026-05-11 (read-pass kroz 6 batch-eva + closure summary + phase gate at baseline). 40 findings: 0 FIXED, 32 OPEN (1 CRIT + 7 HIGH + 14 MED + 10 LOW), 8 INFO. **Gate: zero regression** (compileKotlin clean, detekt 291 baseline, test 29/103 baseline — sve F1-074). Pending user action: Stripe payment hardening trio (F3-022/023/024), NauSys/MMK HTTP foundation (F3-001/002), F3-035 DevController hardening (paired s F1-041 closeout), plus F3-003/009 NauSys HTTPS verify s partner-om.

### CRIT (1)

| ID | Severity | Naslov | Status |
|---|---|---|---|
| F3-022 | CRIT | `StripePaymentService.handleWebhookEvent` non-idempotent — F1-019 konkretizacija (dupli partner confirm + dupli email + state corruption pri Stripe retry) | OPEN — **prod-blocker** |

### HIGH (7)

| ID | Severity | Naslov | Status |
|---|---|---|---|
| F3-001 | HIGH | `NauSysRestClientConfig`/`MmkRestClientConfig` nemaju connect/read/write timeouts — partner slow = VM2 unresponsive | OPEN — **prod-blocker scenario** |
| F3-002 | HIGH | `@Retryable(Exception::class)` na state-changing calls (createOption/confirmReservation/stornoOption/cancelReservation) duplicira partner side-effects (F1-019 sibling) | OPEN — **prod-blocker pair s F1-019** |
| F3-003 | HIGH | NauSys credentials u request body putuju preko HTTP plaintext (F1-037 produbljen) | OPEN — partner-side HTTPS verify + env var fix |
| F3-009 | HIGH | Customer PII (name, surname, crew list) putuje NauSys-u u HTTP body plaintext (F3-003 širenje na PII — GDPR breach risk) | OPEN — pair s F3-003 fix |
| F3-023 | HIGH | `setSessionIdOnPaymentPhases` overwrites stripeSessionId bez check-a; old-session completion = orphan payment, customer money-loss scenario | OPEN — **prod-blocker scenario** |
| F3-024 | HIGH | Webhook `@Transactional` wraps partner confirmExternalReservation + DB + email; partial-failure → partner confirmed + DB rollback drift | OPEN — pair s F3-022 fix |
| F3-035 | HIGH | `DevEquipmentSyncController` `/public/dev/...`: only `@Profile("dev")`, no `@PreAuthorize`, all `@GetMapping` — F1-041 deepened (triple-defense missing) | OPEN — **HIGH, paired s F1-041 closeout** |

### MED (14)

| ID | Severity | Naslov | Status |
|---|---|---|---|
| F3-004 | MED | `MmkRestClientConfig` interceptor logira FULL request body + headers; Reservation payload PII u log-u | OPEN — Faza 5 (cross-cutting log audit) ili profile-gate |
| F3-005 | MED | `@Retryable` Backoff bez jitter — thundering herd risk pri partner outage burst-u | WAITING-DECISION (group s F3-002 retry fix) |
| F3-008 | MED | `NauSysRetryableClient.getReservation` 3 serial endpoint-a × @Retryable = do 9 partner calls po lookup-u | OPEN — paralelno s F3-001/002 fix |
| F3-010 | MED | `ReservationResponseWrapper.responseBody` čuva cijeli NauSys response JSON; PII u DB bez scrubbing layer-a | OPEN — eskalacija (data minimization decision) |
| F3-011 | MED | Per-agency forEach + try/catch swallow bez rate-limit / circuit breaker; cascade failure pri partner outage | OPEN — Faza 5 (resilience) ili Faza 6 |
| F3-012 | MED | `NauSysYachtIntegrationService.yachtSync` re-sync path nikad ne discoveruje nove yachte na partner-side | OPEN — MED bug fix (ne blocker) |
| F3-017 | MED | `MmkYachtIntegrationService.yachtTranslationsSync` 6-language × N-agency × @Retryable amplification (~900 calls); single-threaded | OPEN — Faza 5 (perf parallelism) ili pair s F3-011 |
| F3-025 | MED | Mnogo `!!` non-null assertions na Stripe webhook payload-u; NPE pri malformed events | WAITING-DECISION (group s F3-022/F3-024 webhook fix batch) |
| F3-026 | MED | `payFullAmount=false` webhook flow uvijek označi `first()` phase; bug-čekanju za installment 2 path | OPEN — pair s F3-022 fix |
| F3-027 | MED | Webhook handler tiho ignorira refund / dispute / expired Stripe event types | OPEN — eskalacija (Stripe event handling roadmap) |
| F3-030 | MED | User-controlled `inquiry.email`/`user.name`/surname putuju u `setReplyTo`/`setTo` bez explicit CRLF/comma sanitizacije — comma-injection risk | OPEN — Faza 5 (input validation sweep) |
| F3-031 | MED | SMTP send failures swallowed bez retry / audit — transactional email loss silent (booking confirmation, password reset) | OPEN — Faza 5 / pre-prod minimum (email_outbox pattern) |
| F3-036 | MED | `ServiceCallCacheService` koristi `Objects.hash().toLong()` (Int range cast) — collision risk na 13M-combinacijski yacht offer cache → silent stale-offer skip | OPEN — MED, real bug; pair s F2-002 retention |
| F3-037 | MED | `ExternalSyncService.yachtSyncsInProgress` JVM-local mutex; VM2 + VM3 ne dijele state — double concurrent sync per yachtId | OPEN — pair s Faza 4 jobs locking |

### LOW (10)

| ID | Severity | Naslov | Status |
|---|---|---|---|
| F3-006 | LOW | `NauSysAuthProvider.username!!`/`password!!` NPE pri praznom env var — treba Spring required syntax | WAITING-DECISION (paralelno s required-env fix) |
| F3-007 | LOW | `transactionTemplate.execute` audit pattern samo u `*Async`; state-change calls gube audit pri TX rollback | OPEN — eskalacija (audit policy decision) |
| F3-013 | LOW | Prod main-source importira `ProdTestSamples.DREAM_YACHT_AGENCY_ID` u 2 fajla (unused, inverted test dependency) | WAITING-DECISION (trivijalan) |
| F3-014 | LOW | `@Async syncOffersForDateRange` + TODO "Nausys only one call at the time" bez locking-a | OPEN — pair s Faza 4 jobs locking decision |
| F3-015 | LOW | `error("No Location for NauSys locationFromId=$id")` exposes partner internal IDs (F1-055 family); MMK sibling u istoj fix scope | OPEN — Faza 5 (cross-cutting error sanitization) |
| F3-018 | LOW | `MmkYachtIntegrationService.SUPPORTED_LANGUAGES` izričito izostavlja EN — verify intended (default fallback?) | WAITING-DECISION (verify hipoteza) |
| F3-019 | LOW | `MmkYachtOfferIntegrationServiceAsync.syncOffersForAgencyYachtsOld` deprecated ali aktivan `@Async` + 90 linija duplicirane impl | WAITING-DECISION (grep + delete) |
| F3-028 | LOW | `toCentsLong()` koristi `RoundingMode.UP` → slight customer overcharge per payment phase | WAITING-DECISION (Mario business choice) |
| F3-032 | LOW | `helper.setTo` multi-recipient pokazuje sve adresarima; admin notification leak ako se ikad pojavi multi-recipient flow | OPEN — Faza 5 (defensive design) |
| F3-038 | LOW | `ExternalSyncService.syncYachtOffers` chained `!!` on `yacht.agency!!.primarySource!!.externalSystem!!` — NPE fragility (F1-026 family) | WAITING-DECISION (trivijalan, group s F1-026) |

---

## Faza 4 — Scheduled jobs + heavy native (jobs, OpenCV, PDF gen)

**Status:** READ-PASS COMPLETE — Batch 1 (Scheduled jobs) ✓, Batch 2 (Heavy native) ✓. **Next: phase closure + gate decision.**

### HIGH (3)

| ID | Severity | Naslov | Status |
|---|---|---|---|
| F4-001 | HIGH | Spring `@Scheduled` default single-thread `TaskScheduler`; long sync chains blokiraju ostale crons + cron drift | OPEN — **pre-prod blocker** (5-min yml fix) |
| F4-002 | HIGH | Profile-only locking (`@Profile("data-sync")`); 2-VM double-fire risk; no ShedLock / distributed lock (paired s F3-037 + F3-014) | OPEN — **pre-prod blocker**, pair s F3-037 fix |
| F4-009 | HIGH | `ImageUtils` ne release-a intermediate Mat / MatOfByte u success ni exception path-evima — production native memory leak (~1.5GB/day VM3) | OPEN — **production stability bug** |

### MED (5)

| ID | Severity | Naslov | Status |
|---|---|---|---|
| F4-003 | MED | Cron clustering xx:00 (DeleteExpired + MMK catalogue + OptionExpiry); DB connection + thread pool contention | OPEN — pair s F4-001 |
| F4-004 | MED | `ImageDownloadJob` zahtjeva `image-sync` profile koji NIJE postavljen u tracked deploy config — job effectively dead | WAITING-DECISION (verify prod env + Mario intent) |
| F4-005 | MED | `NausysSyncJob.runYachtSync` chains yacht+offer u 1 metodu bez time-budget-a; 30-90 min wall-time blocks scheduler | OPEN — pair s F4-001 + F3-001 |
| F4-010 | MED | `YachtImageService.File(uploadDir + "/" + yachtImage.url)` path concatenation bez canonicalize — F1-021 concrete exploit point | OPEN — Faza 5 (path traversal sweep) |
| F4-011 | MED | `CharterAgreementService.useFastMode()` može onemogućiti XXE protection; no explicit XML parser hardening | OPEN — Faza 5 (template + XML hardening) |

### LOW (4)

| ID | Severity | Naslov | Status |
|---|---|---|---|
| F4-006 | LOW | `runCatalogueSync` + `runCatalogueBackupSync` 95% duplication — drift risk | WAITING-DECISION (trivial refactor) |
| F4-007 | LOW | `GenerateInvoiceJob` bez `shouldRunScheduledSync` check; missing backup-sync pattern | WAITING-DECISION (verify InvoiceService idempotency prvo) |
| F4-012 | LOW | `CharterAgreementService.renderToPdf` chained `!!` na flow.user/yacht/dateFrom — F2-041 fictitious reservation edge case | WAITING-DECISION (verify if fictitious reaches PDF render) |
| F4-013 | LOW | `ImageUtils.IllegalArgumentException("Could not load image from path: $imagePath")` curi file path; F1-055/F4-010 family | OPEN — Faza 5 (cross-cutting error sanitization) |

### INFO (2)

| ID | Naslov |
|---|---|
| F4-008 | Positive: backup-sync pattern + explicit cron offset comments + Mario decision history u job-ovima |
| F4-014 | Positive: ImageUtils releases primary Mats, CharterAgreement runCatching currency, lazy signatureDataUrl, defensive fallback chains |

---

## Total cumulative across all phases

| Severity | Faza 1 | Faza 2 | Faza 3 | Faza 4 | Faza 5 | Faza 6 | Faza 7 | TOTAL |
|---|---|---|---|---|---|---|---|---|
| CRIT | 2 | 1 | 1 | 0 | — | — | — | **4** |
| HIGH | 13 | 1 | 7 | 3 | — | — | — | **24** |
| MED | 18 | 19 | 14 | 5 | — | — | — | **56** |
| LOW | 8 | 23 | 10 | 4 | — | — | — | **45** |
| INFO | 4 | 3 | 8 | 2 | — | — | — | **17** |
| FIXED | 20 | 3 | 0 | 0 | — | — | — | **23** |
| DEFERRED-Faza7 (nginx batch) | 6 | 0 | 0 | 0 | — | — | — | **6** |
| DEFERRED-other | 3 | 0 | 0 | 0 | — | — | — | **3** |
| BLOCKED | 1 | 0 | 0 | 0 | — | — | — | **1** |
| **OPEN** | **41** | **44** | **32** | **12** | — | — | — | **129** |

---

## Faza 7 nginx ops batch (DEFERRED 2026-05-08)

Po dogovoru s korisnikom, sve nginx config promjene primjenjuju se u jednom deploy window-u na kraju review-a (Faza 7). Findings ostaju trackani, ali ne blokiraju code review faze 2-6.

| ID | Naslov | Verification status (2026-05-08) |
|---|---|---|
| F1-003 | Per-IP rate limit za auth endpointe | `nginx -T \| grep limit_req` vraća prazno → CONFIRMED MISSING |
| F1-022 | XFF strip / nginx ne smije prosljeđivati klijentski header | `proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for` → CONFIRMED scenario C UNSAFE |
| F1-048 | TLS 1.0/1.1 deprecated (default 404, mitigirano) | not re-verified, presumed unchanged |
| F1-050 | server_tokens (verzija leak) | not re-verified, presumed unchanged |
| F1-051 | Default `_`-host server block | not re-verified, presumed unchanged |
| F1-053 | HSTS header missing | not re-verified, presumed unchanged |

Faza 7 plan: jedan diff `/etc/nginx/sites-enabled/<conf>` koji adresira sve gore u istom commit window-u, validate `nginx -t`, reload `systemctl reload nginx`, smoke test.

---

Faza 1 STATUS: **CLOSED 2026-05-08.** 67 nalaza ukupno (66 pri kraju read passa + F1-074 iz phase gate-a). 20 fix-eva commit-ano (batch-1 + batch-2). compileKotlin ✓; detekt baseline 291 issues (pre-existing); 29/103 testova fail (pre-existing — F1-074). Nginx-side findings (6) deferrirani na Fazu 7 — ne blokiraju Fazu 2.
