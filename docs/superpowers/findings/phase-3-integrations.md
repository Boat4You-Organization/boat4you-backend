# Faza 3 — Vanjske integracije

**Status:** in progress (inventory + read pass)
**Datum starta:** 2026-05-11
**Scope per spec (`2026-05-07-boat4you-prod-review-design.md:96-109`):**
- `domains/external/nausys/*`
- `domains/external/mmk/*`
- Stripe integracija (`StripeConfig`, `StripePaymentService`, `StripeWebhookController`, `StripePaymentController`, `PublicStripePaymentController`)
- Viva integracija (uklonjen u V1_55 — **out of scope**, finding F2 spomenuto)
- Mail service (`EmailService`, `ReservationEmailService`, `InquiryEmailService`, `PaymentPendingNotificationService`, `BirthdayEmailJob`, ...)
- HTTP client usage (`RestTemplate` / `RestClient` / `WebClient` / `HttpClient`)
- Generated klijenti iz OpenAPI (`build/generated/swagger/**`)

---

## Inventory

### NauSys (16 files)

| Path | Role |
|---|---|
| `domains/external/nausys/client/NauSysClient.kt` | Base RestClient client |
| `domains/external/nausys/client/NauSysAuditedClient.kt` | Wraps with ServiceCall audit |
| `domains/external/nausys/client/NauSysRetryableClient.kt` | Retry policy wrapper |
| `domains/external/nausys/config/NauSysRestClientConfig.kt` | Spring RestClient bean config |
| `domains/external/nausys/config/NauSysAuthProvider.kt` | Auth headers / credentials |
| `domains/external/nausys/model/NauSysDate*Wrapper.kt` | Date/time wrappers (3 files) |
| `domains/external/nausys/service/NauSysCatalogueIntegrationService.kt` | Catalogue mapping |
| `domains/external/nausys/service/NauSysCatalogueSyncService.kt` | Catalogue scheduled-call orchestration |
| `domains/external/nausys/service/NauSysYachtIntegrationService.kt` | Yacht-level integration |
| `domains/external/nausys/service/NauSysYachtSyncService.kt` | Yacht sync orchestration |
| `domains/external/nausys/service/NauSysYachtOfferIntegrationService.kt` | Offer integration |
| `domains/external/nausys/service/NauSysYachtOfferIntegrationServiceAsync.kt` | Async offer integration |
| `domains/external/nausys/service/NauSysYachtOfferSyncService.kt` | Offer sync orchestration |
| `domains/external/nausys/service/NauSysAvailabilityIntegrationService.kt` | Availability integration |
| `domains/external/nausys/service/NauSysAvailabilitySyncService.kt` | Availability sync orchestration |
| `domains/external/nausys/service/NausysReservationIntegrationService.kt` | Reservation create/update/cancel on NauSys side |
| `domains/external/nausys/controller/NausysSyncController.kt` | Admin sync trigger endpoints |
| `domains/external/nausys/job/NausysSyncJob.kt` | `@Scheduled` triggers (Phase 4 deep-dive) |

### MMK (12 files)

| Path | Role |
|---|---|
| `domains/external/mmk/client/MmkClient.kt` | Base RestClient |
| `domains/external/mmk/client/MmkAuditedClient.kt` | Audit wrapper |
| `domains/external/mmk/client/MmkRetryableClient.kt` | Retry wrapper |
| `domains/external/mmk/config/MmkRestClientConfig.kt` | Spring RestClient bean config |
| `domains/external/mmk/model/MmkDateTimeWrapper.kt` | Date/time wrappers |
| `domains/external/mmk/service/MmkCatalogueIntegrationService.kt` | Catalogue mapping |
| `domains/external/mmk/service/MmkCatalogueSyncService.kt` | Catalogue scheduled-call orchestration |
| `domains/external/mmk/service/MmkYachtIntegrationService.kt` | Yacht-level integration |
| `domains/external/mmk/service/MmkYachtSyncService.kt` | Yacht sync orchestration |
| `domains/external/mmk/service/MmkYachtOfferIntegrationService.kt` | Offer integration |
| `domains/external/mmk/service/MmkYachtOfferIntegrationServiceAsync.kt` | Async offer integration |
| `domains/external/mmk/service/MmkYachtOfferSyncService.kt` | Offer sync orchestration |
| `domains/external/mmk/service/MmkAvailabilityIntegrationService.kt` | Availability integration |
| `domains/external/mmk/service/MmkAvailabilitySyncService.kt` | Availability sync orchestration |
| `domains/external/mmk/service/MmkOfferIntegrationUtils.kt` | Helper utils |
| `domains/external/mmk/service/MmkReservationIntegrationService.kt` | Reservation NauSys-side |
| `domains/external/mmk/controller/MmkSyncController.kt` | Admin sync triggers |
| `domains/external/mmk/job/MmkSyncJob.kt` | `@Scheduled` (Phase 4 deep-dive) |

### Stripe (5 files)

| Path | Role |
|---|---|
| `common/config/StripeConfig.kt` | Stripe SDK config |
| `domains/reservation/service/StripePaymentService.kt` | Checkout sessions + refunds + webhook handler |
| `domains/reservation/controllers/StripeWebhookController.kt` | Webhook endpoint |
| `domains/reservation/controllers/StripePaymentController.kt` | Secured payment endpoints |
| `domains/reservation/controllers/PublicStripePaymentController.kt` | Public payment endpoints |

### Mail / async email (suspect set — to triage in Batch)

- `domains/catalouge/services/EmailService.kt` — base mail sender
- `domains/reservation/service/ReservationEmailService.kt` — reservation flow emails
- `domains/catalouge/services/InquiryEmailService.kt` — inquiry replies
- `domains/reservation/job/PaymentPendingNotificationService.kt` — pending payment reminders
- `domains/reservation/job/PreCharterReminderJob.kt` (if exists; verify)
- `domains/users/job/BirthdayEmailJob.kt`
- Others — to discover during read pass

### Sync orchestration cross-provider

- `domains/external/service/ExternalSyncService.kt` — wraps NauSys/MMK calls
- `domains/external/service/ServiceCallCacheService.kt` — caches partner responses
- `domains/external/service/ServiceCallAuditService.kt` — audit ServiceCall row writes
- `domains/external/service/ExternalMappingService.kt` — entity ID mapping
- `domains/external/service/IntervalProvider.kt` — date range slicing for sync
- `domains/external/service/ReservationOptionsCombinationProvider.kt` — checkin/checkout day matrix
- `domains/external/service/YachtGroupingProvider.kt` — yacht batch logic
- `domains/external/service/YachtImageIntegrationService.kt` + Async + Sync — yacht image downloads (Phase 4 NFS path-traversal too)

### Out-of-scope (covered in later phases)

- All `*Job.kt` → Phase 4 (scheduled jobs deep-dive). Phase 3 will note integration-layer concerns but **not** locking/idempotency of the cron itself.
- `application.yml` config → Phase 5 (cross-cutting).
- `ImageDownloadJob`/NFS path → Phase 4.

---

## HikariCP / executor pools impact (HTTP context)

NauSys + MMK sync share VM3 with cron jobs. Tasks may use `@Async` executors or coroutines. Need to map:
- Connect/read/write timeouts on RestClient — any explicit?
- Retry backoff (max attempts, jitter)
- Circuit breaker / bulkhead (Resilience4j) — present?
- Webhook handler idempotency (F1-019 CRIT still open)
- TLS configuration (F1-037 HIGH: `NAUSYS_URL` defaults `http://`)

---

## Workflow Faze 3

Plan reading pass-a u 6 batch-eva (analogno Phase 2 patternu):

1. **Batch 1 — HTTP client foundation** (`NauSysRestClientConfig`, `MmkRestClientConfig`, `NauSysAuthProvider`, base `NauSysClient` / `MmkClient`, `*RetryableClient`, `*AuditedClient`) — timeouts, retry, secret handling, TLS, audit-injection points
2. **Batch 2 — NauSys integration services** (`NauSysCatalogueIntegrationService`, `NauSysYachtIntegrationService`, `NauSysYachtOfferIntegrationService`, `NauSysAvailabilityIntegrationService`, `NausysReservationIntegrationService`)
3. **Batch 3 — MMK integration services** (analogni MMK fajlovi)
4. **Batch 4 — Stripe** (`StripeConfig`, `StripePaymentService`, `StripeWebhookController`, `StripePayment*Controller`) — webhook idempotency (F1-019 CRIT), refund flow, public endpoint surface
5. **Batch 5 — Mail** (`EmailService`, `ReservationEmailService`, `InquiryEmailService`, `PaymentPendingNotificationService`, `BirthdayEmailJob` integration boundary) — PII u email-u, SMTP errors, async retry, bounce handling
6. **Batch 6 — Sync orchestration + admin controllers** (`ExternalSyncService`, `ServiceCallCacheService`, sync triggers in `MmkSyncController` / `NausysSyncController`) — backpressure, public→sync trigger surface (F1-064), admin admin sync auth

Za svaki batch: triage live u ovaj file kao `[F3-NNN]` nalazi.

---

## Findings

---

## Batch 1 — HTTP client foundation: RestClient config, auth, retry, audit wrappers (2026-05-11)

### [F3-001] HIGH availability — `NauSysRestClientConfig` i `MmkRestClientConfig` nemaju connect/read/write timeouts; slow partner endpoint blokira thread indefinitno
**Lokacija:**
- `domains/external/nausys/config/NauSysRestClientConfig.kt:13-19`
- `domains/external/mmk/config/MmkRestClientConfig.kt:18-44`

**Detekcija:** statička
**Opis:** Obje config klase grade `RestClient.builder().baseUrl(...).build()` bez ijednog timeout-a. Spring 6 `RestClient` default-a na ne-blokirajući behavior **ali ne postavlja connect/read/write deadlines** — pod hood-om koristi JDK `HttpClient` koji defaultira na sistemskim socket timeout-ima (linux: oko 2h connect, beskonačno read za TCP keep-alive).

Combined sa:
- **F1-064 HIGH** — public yacht search trigger-a synkroni external sync iz request thread-a. Spori NauSys = sporo public response.
- **F2-022 fix-precedent** — scheduled jobs koji se oslanjaju na sync u uniform vremenima (06:00).
- **HikariCP pool** = 25 connections per VM. Ako 25 request thread-ova istovremeno čeka NauSys, novi requesti blokiraju.

**Concrete failure scenario:** NauSys ima ops incident → svi response-ovi traju 90s+. Boat4You request thread-ovi se akumuliraju u `nauSysClient.defaultApi.allYachts(...)` poziv. 25 thread-ova zauzeto u <2 minute. Sve nove request-e (public search) odbije Tomcat thread pool. Cijeli VM2 nedostupan dok NauSys ne zavisi/reset-a connection.

Plus: bez timeouts, retry pattern (F3-002) je također destabiliziran — `@Retryable(maxAttempts=3, multiplier=3.0)` znači "do 9 attempts po cijenu deadlines" — ali svaki attempt može trajati 30+ minuta ako endpoint hangira.
**Posljedica:** **availability incident u partner sustavu = availability incident u Boat4You-u**. Pre-prod blocker za prvi NauSys outage scenario.
**Predloženi fix:** dodati timeouts u obje konfiguracije. Spring 6 idiom:
```kotlin
import org.springframework.http.client.JdkClientHttpRequestFactory
import java.time.Duration

@Bean("nauSysRestClient")
fun nauSysRestClient(): RestClient {
    val httpClient = java.net.http.HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()
    val factory = JdkClientHttpRequestFactory(httpClient).apply {
        setReadTimeout(Duration.ofSeconds(30))   // NauSys SLA expectation
    }
    return RestClient.builder()
        .baseUrl(nauSysBaseUrl)
        .requestFactory(factory)
        .build()
}
```
Vrijednosti: NauSys 10s connect / 30s read; MMK iste (verify partner SLA). Plus: razmotriti circuit breaker (Resilience4j) — open-on-error-rate, fallback na cached data. Phase 4 jobs će ovo pogoršati (multipliciraju calls) — fix prije Phase 4 fix-pass-a.
**Riziko-procjena fixa:** dira hot integration path, treba runtime testing — kratak ručni test s `tc qdisc add ... delay 60s` na partner IP-u u staging-u.
**Status:** OPEN — **HIGH, prod-blocker scenario**

---

### [F3-002] HIGH idempotency — `@Retryable(value = [Exception::class])` na state-changing operacijama dupliciuje side-effects pri transient error-u
**Lokacija:**
- `domains/external/nausys/client/NauSysRetryableClient.kt:159` (`createOption`), `:180` (`confirmReservation` aka `createBooking`), `:136` (`createInfo`), `:201` (`stornoOption`)
- `domains/external/mmk/client/MmkRetryableClient.kt:220` (`createOption`/`createReservation`), `:241` (`confirmReservation`), `:284` (`cancelOption`)

**Detekcija:** statička
**Opis:** Spring `@Retryable(value = [Exception::class], maxAttempts = 3, backoff = Backoff(delay = 1000L, multiplier = 3.0))` retries on **ANY** Exception. Aplicirano na pozive koji **kreiraju ili mijenjaju partner state** (rezervacije, opcije, otkaze).

**Tipičan failure mod:**
1. Boat4You šalje `createBooking` na NauSys.
2. NauSys uspješno kreira booking u svojoj DB-i, response 200 OK u tijeku.
3. Network blip (TCP RST, idle timeout) — Boat4You ne primi response.
4. Spring Retry: any Exception → retry attempt 2 → NauSys vraća error (jer booking već postoji s istim UUID-em) ili kreira **dupli** booking.
5. Boat4You klijent vidi error nakon retry exhaustion → ali partner DB ima **2 booking-a za istu rezervaciju**.

Idempotency strategije po partner-u:
- **NauSys**: `request.uuid` — generiran klijent-side, prosljeđuje u `createOption`/`createBooking`. NauSys može (ili ne mora — verify) odbiti dupli UUID. Trenutno code NE provjerava niti generira deterministic UUID za retry.
- **MMK**: `createReservation` (line 220) i `cancelReservation` (line 285) — provjeri da li MMK API ima idempotency-key header support; trenutni klijent ga ne postavlja. `bookingApi.cancelReservation(reservationId)` na reservation koji je već cancelled = idempotent na partner side (vraća isto stanje) ali to mora biti verificirano.

Combined s F1-019 CRIT (Stripe webhook non-idempotency) — dual-system idempotency je problematičan i kod payment-a i kod reservation booking-a.

Plus: F3-001 (no timeouts) — retry attempt može hangati indefinitno → cumulative request time može biti satima.
**Posljedica:** dupli booking-i pri partner pri transient mreže. Customer dispute scenario: "obratio sam se vama, naplatili ste me dvaput, partner kaže da je samo jedna rezervacija valid-a".
**Predloženi fix:** dvije razine:
- (a) **Sužiti retry scope na transient-only errors** — `@Retryable(value = [ResourceAccessException::class, HttpServerErrorException::class])`, ne `Exception`. Tako se 4xx (uključujući "duplicate") ne retry-aju.
- (b) **Idempotency key** — za svaki state-change request, generirati deterministic key (npr. `reservation_flow.id + ":option" / ":booking" / ":cancel"`), pohraniti u DB u idempotency tablicu, slati partneru (ako podržava). Pri retry: provjeri DB stanje umjesto blindly retry. Veliki refactor.

(a) je minimum za pre-prod. (b) za Faza 6 / Faza 7.

Plus: **zabraniti retry na metode označene `@Modifying` partner side**. Read-only metode (`getOffers`, `allYachts`, etc.) ostaju retryable as-is.
**Riziko-procjena fixa:** dira write path partner integration-a. (a) je 1-line annotation change, ali treba verify-grep za sve callere koji se oslanjaju na current retry behavior.
**Status:** OPEN — **HIGH, prod-blocker** (paired s F1-019 CRIT)

---

### [F3-003] HIGH security — NauSys credentials u request body putuju preko HTTP (plaintext) — F1-037 produbljen
**Lokacija:**
- `application-prod.yml:18` (`NAUSYS_URL: http://ws.nausys.com`)
- `domains/external/nausys/config/NauSysAuthProvider.kt:12-14` (`RestAuthentication(username, password)`)
- `NauSysAuditedClient.kt:55,71,86,...` — every call passes `nauSysAuthProvider.auth` as method parameter (which OpenAPI client serializes into request body)

**Detekcija:** statička
**Opis:** F1-037 zabilježilo "NAUSYS_URL default http://". Ali **gore od toga**: NauSys OpenAPI klijent ne koristi HTTP Basic Auth header — koristi `RestAuthentication` model u **request body-ju** kao JSON polje:
```json
{"credentials": {"username": "boat4you", "password": "..."}, ...other params...}
```
S `http://` default URL-om, ovo znači:
1. Username + password se serializira u plain JSON u svaki request body
2. Putuje preko unencrypted TCP do ws.nausys.com
3. Network attacker (corporate firewall, ISP MITM, public WiFi pri dev work-u s prod creds) capture-a creds u plaintext-u

Plus: F1-019, F1-022, F1-053 — TLS / HSTS pattern već je flag-an u nginx batch-u (Faza 7). Ali ovaj je outbound, ne inbound — nginx ne pomaže.

`NauSysAuthProvider.auth: RestAuthentication` je `lazy` initialized i reused. Ako prod env korigira `NAUSYS_URL` u `https://`, fix je trivijalan (env-only).
**Posljedica:** credentials leak risk. Severity ovisi o tome je li `ws.nausys.com` redirektuje na HTTPS auto (then Boat4You client follow-up bi gledao 301 to https://...) **ali sve to nakon prvog request-a koji već sadrži creds u plain bodyju.** Treba verificirati ručno: `curl -v http://ws.nausys.com/...`.
**Predloženi fix:** dva koraka:
- (a) **Provjeri partner side:** NauSys produkcija je dostupna preko `https://ws.nausys.com`? Verify ručno + Mario kontakt s NauSys support-om.
- (b) **Hardkodirati `https://` u config default + production env override:** `NAUSYS_URL: ${NAUSYS_URL:https://ws.nausys.com}` (i u `application-prod.yml`, `application-aws.yml`, `application-dev.yml`).

Plus: trace prod env u VM3 (deploy artefact) — ako ima `NAUSYS_URL=http://...` postavljen, prepiši na `https://`. To je ops-side fix, ne code.

(c) **Verify request body sadržaj** — `serializeExcludingCredentials` (NauSysAuditedClient:196) maskira credentials za audit log table, ali ne sprječava ih u **on-wire** body-ju. To je svrha; only encryption of transport pomaže.
**Riziko-procjena fixa:** zavisi od NauSys partner-side HTTPS podrške. Ako ne radi, ozbiljniji refactor potreban.
**Status:** OPEN — **HIGH** (eskalacija F1-037, partner-side verify required)

---

### [F3-004] MED security — MMK RestClient interceptor logira FULL request body + headers; reservation create payloads sadrže PII
**Lokacija:** `domains/external/mmk/config/MmkRestClientConfig.kt:22-39`
**Detekcija:** statička
**Opis:** `requestInterceptor` u MMK config-u:
```kotlin
val bodyStr = if (body.isNotEmpty()) String(body, Charsets.UTF_8) else "<empty>"
log.info("MMK request: {} {} headers={} body={}", request.method, request.uri, request.headers.filterKeys { it.lowercase() != "authorization" }, bodyStr)
val response = execution.execute(request, body)
log.info("MMK response: status={} headers={}", response.statusCode, response.headers)
```

Komentar prizna: "Body is small (Reservation / params), so logging it inline is acceptable here." — ali `Reservation` MMK model (vidi `MmkRetryableClient.createOption`) sadrži:
- Customer crew list (full names + DOB + passport, ako se prosljeđuje)
- Customer email / phone
- Yacht reservation period + price

**Sve to gore navedeno je PII.** Log fajlovi tipično idu u ELK / centralized log infrastructure (Phase 5 cross-cutting). PII u log fajlovima:
- Krši GDPR pravilo "purpose limitation" (Article 5.1.b) — logovi su za debugging, ne za customer data store.
- Right-to-erasure (Article 17) — kad user traži brisanje, NEMA pristupa za scrub-anje log-ova bez specific tooling.
- Pri data breach (log server kompromitiran), PII se izloži kao bonus.

Plus: response body NIJE logiran (samo status + headers) — to je dobro. Ali request body je dovoljan.

Comment napomena "verify exact wire format vs competitor" → diagnostic-only use case. Ne bi smio biti permanent prod log.
**Posljedica:** GDPR compliance gap. Ne immediately exploitable, ali audit findings za GDPR.
**Predloženi fix:** dvije razine:
- (a) **Skratiti log na MMK request URI + method + body length:** `log.info("MMK request: {} {} bodyLen={}", method, uri, body.size)`. Ovo daje observability bez PII.
- (b) **PII-aware mask:** prije logging-a, parse-aj body kao JSON i nul-iraj poznata polja (`crewList`, `email`, `phone`, `name`, `surname`). Veći refactor.
- (c) **Profile-gate:** logger only active pod `mmk-debug` profile-om. Default off u prod-u. Brz fix.

(c) je najpragmatičnije za pre-prod. (a) je permanent fix.

Plus: dodati u Phase 5 cross-cutting batch za log scrubbing audit.
**Riziko-procjena fixa:** trivijalan code change. MED concern.
**Status:** OPEN — Faza 5 (cross-cutting log audit) ili profile-gate prije prod-a

---

### [F3-005] MED retry — Spring `@Retryable` bez jitter; thundering herd risk pri scheduled syncs
**Lokacija:**
- `NauSysRetryableClient.kt:34-38` (companion object constants)
- `MmkRetryableClient.kt:25-29`

**Detekcija:** statička
**Opis:** `Backoff(delay = 1000L, multiplier = 3.0)` — deterministic 1s/3s/9s pauze. Nema `random = true` (Spring Retry support-a jitter via `random` flag) ili explicit `multiplierGenerator`.

**Why this matters:**
- Scheduled jobs (Phase 4) sa NauSys/MMK sync trigger se u istom trenutku za više agencija (`for agency in agencies { client.getOffers(...) }`).
- Ako partner endpoint ima brief outage (5 sekundi), sve concurrent requesti istovremeno fail-aju → svi retry simultaneously @1s → @3s → @9s.
- Partner endpoint kad se vrati, prvi shockwave od N requestova udari odjednom → potencijalno re-fail → ciklus se pogoršava.

Nije katastrofa za 2 partnera (NauSys + MMK) jer broj concurrent connections nije ogroman. Ali ako se Boat4You proširi na 5+ partnera, jitter postaje must-have.

Posebno relevant za F3-001 (no timeouts) scenario — bez timeouts, retry storm može držati partner endpoint busy znatno duže nego potrebno.
**Posljedica:** mild perf degradation u outage scenarijima. Pre-prod nije akutni problem.
**Predloženi fix:** dodati jitter u Backoff:
```kotlin
@Retryable(
    value = [Exception::class],  // (vidi F3-002 za sužavanje)
    maxAttempts = 3,
    backoff = Backoff(delay = 1000L, multiplier = 3.0, random = true)
)
```
`random = true` randomizira `delay * (multiplier^attempt)` u rasponu [delay, delay*multiplier^attempt]. Trivijalno.
**Riziko-procjena fixa:** trivijalan. LOW invasiveness.
**Status:** WAITING-DECISION (trivijalan; group s F3-002 retry fix)

---

### [F3-006] LOW config robustness — `NauSysAuthProvider.username!!`/`password!!` NPE pri praznoj env varijabli; treba required-fail
**Lokacija:** `domains/external/nausys/config/NauSysAuthProvider.kt:9-14`
**Detekcija:** statička
**Opis:**
```kotlin
@Component
class NauSysAuthProvider(
    @Value("\${application.external.nausys.username}") val nauSysUsername: String? = null,
    @Value("\${application.external.nausys.password}") val nauSysPassword: String? = null,
) {
    val auth: RestAuthentication by lazy {
        RestAuthentication(nauSysUsername!!, nauSysPassword!!)
    }
}
```
`!!` non-null assertion će baciti NPE pri prvom poziv-u `nauSysAuthProvider.auth` ako su env varijable prazne ili null. `application-prod.yml` koristi `${NAUSYS_USERNAME}` (bez default-a) → ako env nije postavljen, Spring Boot resolve-a u empty string, ne null. Onda `nauSysUsername = ""` (not null), `!!` ne baca, ali RestAuthentication sa praznim username = 401 unauthorized pri prvom calls — koji se onda retry-a 3 puta (F3-002). Bolji failure mode: **fail-fast pri startup-u**.

Sibling F1-036 (DB credentials required, no literal default) — već fixed s `${DB_USER:?DB_USER required}` (Spring Placeholder syntax). Isti pattern bi trebao biti za `NAUSYS_USERNAME` i `NAUSYS_PASSWORD`.
**Posljedica:** late failure mode — sync job 06:30 fail-a sa NauSys 401 nakon retry, umjesto da app refuse-a startup ako creds nisu postavljeni.
**Predloženi fix:** u `application-prod.yml`:
```yaml
username: ${NAUSYS_USERNAME:?NAUSYS_USERNAME required}
password: ${NAUSYS_PASSWORD:?NAUSYS_PASSWORD required}
```
Plus: u NauSysAuthProvider, ukloniti `String? = null` defaultsa — koristiti `String` (non-nullable). Compile-time check.
**Riziko-procjena fixa:** trivijalan; analogan F1-036 fix-u.
**Status:** WAITING-DECISION (trivijalan, paralelno s ostalim required-env fix-evima)

---

### [F3-007] LOW design inconsistency — `transactionTemplate.execute` pattern za audit samo u `*Async` metodama; svi state-change metodi bi trebali audit u svom TX-u ili None
**Lokacija:**
- `NauSysRetryableClient.kt:70-77` (`getFreeYachtsSearchForAsync` koristi `transactionTemplate.execute<Unit>`)
- `MmkRetryableClient.kt:177-184` (`getOffersForAsync` isti)
- **Ali NE** u `createOption`, `confirmReservation`, `stornoOption` (NauSys) niti u `createOption`, `confirmReservation`, `cancelOption` (MMK) — koje su state-changing
**Detekcija:** statička
**Opis:** TransactionTemplate.execute pravi audit insert u **separate transaction** od outer call. Pattern je vrijedno za:
- **Async metode** koji se izvršavaju izvan request-thread-a (outer TX se možda već commit-ao ili nije postoji) — što i komentari `Async` implicitno potvrđuju.
- **State-changing metode** kod kojih outer TX može rollback (npr. reservation create fail nakon partner success) — ako audit ne ide u zaseban TX, audit traka **rollback-a se zajedno s neuspjelim outer-om**, tj. nema traga o partner pozivu koji JE napravljen.

**Trenutno code:** `createOption` itd. **NEMAJU** TransactionTemplate.execute → ako outer TX rollback-a (npr. nakon partner success, ali boat4you DB save fails), partner side ima novi booking ali boat4you ima ZERO audit u ServiceCall tablici. Forensics nemoguć.

To je point gdje F3-002 (idempotency) + F3-007 (audit rollback) **kombiniraju u opasan scenarij:**
1. Outer TX: Boat4you kreira reservation_flow → poziva `nauSysRetryableClient.createOption` → partner success.
2. Boat4you DB save dalje fails (CHECK constraint, missing FK, anything).
3. Outer TX rollback-a → boat4you nema record-a o pozivu.
4. Partner ima active option koji nikad ne istekne (jer client misli da reservation ne postoji).
5. Audit za debug? Nema je — F3-007.
6. Retry? Možda — F3-002 zavisi.
**Posljedica:** silent partner-side leaks (orphan options/bookings). Pre-prod nije akutni, ali zahtjeva monitoring (post-deploy: NauSys neaktivni options counter).
**Predloženi fix:** dvije opcije:
- (a) **All state-change metode dobivaju `transactionTemplate.execute<Unit>` wrap** za audit. Konzistentno.
- (b) **Decide jednom**: ako outer TX rollback znači da poziv nije relevant audit-irati, makni TransactionTemplate iz Async metoda i prebaci na konzistentno "no nested TX". Jednostavnije ali sa svojim risk-evima.

Preporuka: (a) — explicit audit isolation za sve state-change calls.
**Riziko-procjena fixa:** ~30 linija dodatnog koda. Drift risk: dodavanje novog state-change call-a u budućnosti bez TransactionTemplate-a.
**Status:** OPEN — eskalacija (audit policy decision)

---

### [F3-008] MED amplification — `NauSysRetryableClient.getReservation` zove 3 endpoint-a serijski + `@Retryable` na cijelu metodu = do 27 partner calls na jedan lookup
**Lokacija:** `domains/external/nausys/client/NauSysRetryableClient.kt:87-129`
**Detekcija:** statička
**Opis:** Metoda traži NauSys reservation by ID. Pošto "there is no API for fetching reservation status by ID", code probava 3 zasebne endpointove:
1. `getAllOptions(request)` — možda je option-status
2. `stornos(request)` — možda je cancelled
3. `getAllReservations(request)` — možda je confirmed booking

Svaki dobiva svoj `serviceCallAudit`. Plus cijela metoda je `@Retryable(maxAttempts = 3)`.

**Worst case math:**
- Single happy path: 1 call (returns from getAllOptions)
- All-three fallthrough: 3 calls
- With method-level retry: do 3 × 3 = **9 partner calls za jedan lookup** (jer retry restart-a iz getAllOptions)

Plus F3-001 (no timeouts) — svaki call može trajati minute. Plus F3-005 (no jitter) — multipliciraju się burst-evi.

Combined: jedan reservation status lookup može trajati 10+ min i otrgnuti 9 NauSys calls. NauSys vidi Boat4You kao thrash-source i may rate-limit nas.

Plus: 3 calls nisu **paralelno** (concurrent) — serial. To je dodatna latencija ali OK iz partner rate-limit perspective. Ako bi se prebacilo na parallel, threading + concurrency limit issue (F3-001 connection pool).

Ne vidi se da je `@Retryable` metoda namjerno tako definirana — vjerojatno copy-paste pattern iz drugih metoda. **Logički greška:** ako `getAllOptions` baci IO error → retry cijele metode (ponovo getAllOptions, pa storno, pa reservations) ima smisla. Ali ako `getAllOptions` succeed-a s status="ERROR" i fall-through na stornos → method retry razdvaja state-corrupted flow.

Plus: code već `runCatching` na svaki call individually. Ako bi se @Retryable maknuo s method razine i stavio per-attempt na svaki runCatching... ali to je rewrite. Najmanje invazivno: maknuti @Retryable s ove metode (jer fallthrough sam po sebi je "retry-ish").
**Posljedica:** amplification factor 9x na metodi koja se može pozivati periodički iz ReservationSyncService (Phase 4 deep-dive). Pri partner outage, naša pressure 9x normalna.
**Predloženi fix:** maknuti @Retryable s ove metode (fallthrough kroz 3 endpoint-a već je defensive enough); ili sužiti retry samo na network exceptions (F3-002 fix). Plus: dodati per-call timeout (F3-001 fix).
**Riziko-procjena fixa:** dira read path. Verify-grep callere — ovo izgleda kao sync-only call.
**Status:** OPEN — paralelno s F3-001/F3-002 fix-evima

---

### Sažetak Batch 1

- **HIGH (3):** F3-001 (no timeouts → availability), F3-002 (retry on any Exception → idempotency), F3-003 (NauSys creds in HTTP body, F1-037 deepen)
- **MED (3):** F3-004 (MMK interceptor logs PII body), F3-005 (no jitter → thundering herd), F3-008 (getReservation 9x amplification)
- **LOW (2):** F3-006 (NauSysAuthProvider `!!` NPE), F3-007 (TransactionTemplate audit inconsistency)

**Najkritičniji nalaz batch-a:** F3-001 + F3-002 + F3-008 zajedno = **fragility multiplier**. Ako NauSys ima 90s spike, Boat4You-jev VM2 može biti unresponsive 20+ minuta i dodavati 25× thread press na partner side. Pre-prod fix prioritet: timeouts (F3-001) + sužiti retry scope (F3-002).

**F3-003** je ozbiljniji ekvivalent F1-037 — credentials in body over HTTP. Verify NauSys partner-side HTTPS support i prebaci env var.

**Batch 1 završen. Batch 2 next:** NauSys integration services (NauSysCatalogueIntegrationService, NauSysYachtIntegrationService, NauSysYachtOfferIntegrationService, NauSysAvailabilityIntegrationService, NausysReservationIntegrationService).

---

## Batch 2 — NauSys integration services (2026-05-11)

### [F3-009] HIGH security — Customer PII (name, surname, eventually crew list) putuje NauSys-u u request body preko HTTP plaintext-a (F3-003 širenje s creds na PII)
**Lokacija:**
- `NausysReservationIntegrationService.kt:73-77` — `RestClient(name = reservationData.name, surname = reservationData.surname)` u body-ju `RestYachtReservationInfoRequest`-a
- Plus svaka subsequentna `createOption`/`confirmReservation` može slati customer detail-e ako partner API endpoint to traži

**Detekcija:** statička
**Opis:** F3-003 zabilježio creds u HTTP body-ju. Ali sve **customer PII** putuje istim putem: NauSys API model `RestClient` u `RestYachtReservationInfoRequest` body-ju sadrži name + surname. To je booking flow → svaki booking ima customer ime u plain HTTP body-ju ka `http://ws.nausys.com`.

Combined s F1-037 (default `http://`), GDPR pravila:
- Article 5(1)(f) — security of processing: PII u transit-u mora biti enkriptiran-a. Plaintext HTTP fail-a već u suštini.
- Article 32 — security measures: TLS je industry-standard za customer data.

Plus: ako NauSys eventualno traži passport/birthday data za crew list manifest (vidi MMK `crewListLink` u `MmkRetryableClient.kt:262`), to su sensitive identity dokumenti. **Treba verificirati što NauSys traži za confirmReservation crew list flow.**
**Posljedica:** GDPR breach risk u plain transit. Network attacker (corporate firewall, ISP, public WiFi pri dev work-u s prod creds) capture-a sve customer rezervacije.
**Predloženi fix:** ovaj finding **zatvara se zajedno s F3-003** — fix je isti: prebaciti `NAUSYS_URL` na `https://`. Verify partner-side HTTPS support s NauSys-om. Nema posebnog code change-a za F3-009 — to je side-effect F3-003 fix-a.

Razlog što ovo flag-am posebno: za stakeholder komunikaciju, "credentials over plaintext" zvuči urgentno ali ograničeno na opseg pri tech team-u. "ALL customer names over plaintext" je GDPR audit story koja eskaliera kod pravnog tima i CMOS-a.
**Status:** OPEN — pair s F3-003 fix (same env var change)

---

### [F3-010] MED data protection — `ReservationResponseWrapper.responseBody` čuva CIJELI NauSys response kao JSON string; vjerojatno persistira u DB bez audit-a / scrub-a
**Lokacija:** `NausysReservationIntegrationService.kt:180` (`responseBody = objectMapper.writeValueAsString(reservationResponse)`)
**Detekcija:** statička
**Opis:** `toResponseWrapper` mapira NauSys response u domain wrapper. Među mapiranjima — line 180 — `responseBody = objectMapper.writeValueAsString(reservationResponse)` čuva **cijeli NauSys response JSON** kao string field na wrapper-u. Caller-u (`ReservationFlowMutationService` ili sl.) je dostupan, vjerojatno persistira u `reservation.partner_response_body` ili `service_call.payload` ili sl. (treba verify gdje).

Što sadrži NauSys response (`RestYachtReservation` model):
- Customer data: client name, surname (možda passport / DOB ako su traženi)
- Crew list link (URL prema NauSys koji vodi do PDF-a s pasošima / državljanstvom)
- Payment plan iznos + datumi
- Pricing breakdown
- Yacht detail snapshot
- Internal NauSys IDs (`id`, `uuid`)

**Combined s F2-038 (ReservationDocument audit gap) + F2-001/F2-004 (audit trail dead):**
- `responseBody` JSON je pohranjen indefinitely
- GDPR right-to-erasure (Article 17): kad customer zatraži brisanje, ne može se selektivno scrub-ati JSON polje (mora se ili anonimizirati cijeli wrapper ili spirat-i record)
- Pri data breach DB-a, `responseBody` se eksponira u JSON-encoded formi (lakša za parser)

Plus: ako se `reservationResponse` mapira via Jackson, postoji rizik da MMK i NauSys structure-i imaju **nestale fields koje se serijaliziraju ali ne deserializiraju** (Jackson include-by-default rules). Audit za to što stvarno ide.
**Posljedica:** GDPR compliance gap. Plus storage bloat za rezervacijske history-je.
**Predloženi fix:** dvije razine:
- (a) **Minimalan:** mappirati samo fields koje stvarno trebamo iz `reservationResponse` i pohraniti structured field-e (već ima mostly — `externalId`, `dateFrom`, etc.). `responseBody` field obrisati — caller-i koji ga koriste prebaciti na specific fields.
- (b) **Sveobuhvatan:** ako je `responseBody` potreban za debugging / forenziku (np. "zašto je NauSys vratio ovaj price?"), pohraniti **scrubbed** version: prije serijaliziranja, `objectMapper.valueToTree(...).remove("client")` i sl. PII fields → onda serijalizira. Defense pattern paralelan s `serializeExcludingCredentials` u Phase 1 batch-u.

Plus: dodati u Phase 5 cross-cutting batch za PII-in-DB sweep — gdje god postoji `body: String` / `payload: String` / `request: String` (vidi CustomOffer.request u F2-22), provjeri postoji li scrubbing layer.
**Riziko-procjena fixa:** (a) trivijalan ali zahtjeva grep za caller usage. (b) više code za defense layer.
**Status:** OPEN — eskalacija (data minimization decision)

---

### [F3-011] MED resilience — Per-agency loop pattern `forEach { try {} catch (Exception) { log.error } }` bez rate-limit / circuit breaker; cascade failure pri partner outage
**Lokacija:**
- `NauSysYachtOfferIntegrationService.kt:48-54` — `agencies.forEach { try {} catch (Exception) { log.error("Error while syncing Nausys Offers ${agency.id}", e) } }`
- `NauSysAvailabilityIntegrationService.kt:30-44` — analogan pattern
- `NauSysYachtIntegrationService.kt:28-56` — analogan (s additional log + return@forEach)

**Detekcija:** statička
**Opis:** Per-agency loop catches ALL exceptions i nastavlja na sljedeću agenciju. **Tu je dobro:** jedan agency fail ne ruši cijeli sync job. **Ali nedostaje:**
1. **Rate-limit budget** — ako NauSys vrati 429 (rate limit) na agency 1, sve sljedeće agency hit istu rate limit grešku. Loop pokuša svih N agencija = N × 429 → escalates rate-limit zabranu (NauSys može blokirati IP).
2. **Circuit breaker** — ako 50% agencije fail-aju, sync bi trebao stati i alert-irati operator-a, ne continue.
3. **Backoff inkrement** — sve agencije retried s defaultnim retry policy iz `NauSysRetryableClient` (3 attempts × 1s/3s/9s). Bez per-job rate-limit budget-a, brzo eskalira.

Combined s F3-001 (no timeouts) — failed agency može blokirati 10+ minuta po retry-u, pa cijeli job traje hours umjesto minutes.
**Posljedica:** scheduled sync pri partner outage-u radi load amplification. Plus: silent log error noise — koliko grešaka je acceptable po sync run-u? Nema treshold-a.
**Predloženi fix:** dva sloja:
- (a) **Pre-flight check** prije ulaska u loop: jedan probe call (`allCharterCompanies` za NauSys, `getCompanies` za MMK). Ako fail → abort sync, alert. Ne troši rate-limit budget na N redundant fails.
- (b) **In-loop circuit breaker** — Resilience4j `CircuitBreaker.decorateCheckedSupplier(...)`. Ako N agencija consecutively fail-aju, otvor circuit, abort. Phase 6 (deploy artefakti) može uvesti Resilience4j ovisnost.

Plus: counter metric — `sync.nausys.agency.failures` Micrometer / Prometheus. Phase 5 (cross-cutting observability) tema.
**Riziko-procjena fixa:** (a) trivijalan. (b) ovisi o Resilience4j adopcijom.
**Status:** OPEN — Faza 5 (resilience pattern) ili Faza 6

---

### [F3-012] MED bug — `NauSysYachtIntegrationService.yachtSync` re-sync path NIKAD ne refresh-a canonical NauSys yacht listu; novi yachti na partner-side undiscoverable
**Lokacija:** `NauSysYachtIntegrationService.kt:35-56`
**Detekcija:** statička
**Opis:** Yacht sync flow:
```kotlin
val existingAgencyYachts = externalMappingRepository.findAllExternalYachtIdsForAgency(agency.id, NAUSYS)
if (existingAgencyYachts.isEmpty()) {
    // first sync — fetch from NauSys, chunk, sync
    val yachtExternalIds = getAgencyYachtIdsFromNausys(agencyExternalId)
    yachtExternalIds.chunked(100).forEach { chunk -> processYachtSync(agencyExternalId, agency, chunk) }
    nauSysYachtSyncService.deactivateYachtsForAgency(agency.id, yachtExternalIds)
} else {
    // re-sync — use EXISTING DB-tracked IDs, don't fetch from NauSys
    existingAgencyYachts.chunked(100).forEach { chunk -> processYachtSync(agencyExternalId, agency, chunk) }
    nauSysYachtSyncService.deactivateYachtsForAgency(agency.id, existingAgencyYachts)
}
```

**Bug:** re-sync grana ne poziva `getAgencyYachtIdsFromNausys(agencyExternalId)` — ona koristi listu yacht-a koje smo već poznavali. Ako NauSys agency doda yacht 5001 nakon prve sync run-e, mi NIKAD ne saznamo jer u re-sync-u samo iteriramo postojeće yacht ID-eve.

`processYachtSync(chunk)` (line 72) sa chunk-om existing ID-eva poziva `allYachts(agencyExternalId, AllYachtsRequest(yachtIDs = chunk))` — NauSys vrati podatke za baš te yacht-e koje smo tražili. Novi yachti na NauSys-u (koji nemaju ExternalMapping kod nas) — invisible.

`deactivateYachtsForAgency(existingAgencyYachts)` (line 54) je defensive: označi kao deaktivirano sve yachte na našoj strani koji su u DB-i ali nisu vraćeni iz NauSys-a u zadnjoj sync run-i. Ali ne discoveruje nove. Asimetrija: detect-ano je deletion, ne addition.

**Concrete impact:** broker dodaje novu yacht na NauSys-u. Boat4You sync poslije, nema yacht. Booking flow za tog yacht-a nemoguć preko Boat4You-a. Customer postavi inquiry direktno preko NauSys-a → izgubljeni revenue.
**Posljedica:** silent yacht discovery gap. Pre-prod nije akutni problem jer fleet je relativno static, ali bilo koji partner add propušten dok se ručno ne pokrene full re-sync (ili izbrišu existing ExternalMappings za agency → fake "first sync" pattern).
**Predloženi fix:** unaprijediti re-sync da uvijek poziva `getAgencyYachtIdsFromNausys` (1 lightweight call s `onlyIDs = true`), unija s existing ID-evima, syncira sve, deaktivira one koji nisu vraćeni. Pseudo:
```kotlin
val nausysYachtIds = getAgencyYachtIdsFromNausys(agencyExternalId)
val allIdsToSync = (nausysYachtIds + existingAgencyYachts).distinct()
allIdsToSync.chunked(100).forEach { ... }
nauSysYachtSyncService.deactivateYachtsForAgency(agency.id, nausysYachtIds)  // deactivate by NauSys truth, not our truth
```
Ovaj fix dodaje 1 dodatni call po agency-ju (NauSys `allYachts` s `onlyIDs=true`, lagani).
**Riziko-procjena fixa:** trivijalan. Dira hot path sync flow-a — treba runtime testirati da je deactivation logic ispravan.
**Status:** OPEN — MED, prod-relevant bug fix (ali ne blocker)

---

### [F3-013] LOW code structure — Prod main-source importira `ProdTestSamples.DREAM_YACHT_AGENCY_ID` u 2 fajla, ali ne koristi
**Lokacija:**
- `NauSysYachtOfferIntegrationService.kt:3` (`import hr.workspace.boat4you.common.test.ProdTestSamples.DREAM_YACHT_AGENCY_ID`)
- `NauSysYachtIntegrationService.kt:3` (isti import)

**Detekcija:** statička
**Opis:** Detekt već flagged `MayBeConst` na `ProdTestSamples.DREAM_YACHT_AGENCY_ID` (vidi gate baseline 291 issues). Plus: ova 2 fajla importiraju polje, ali tijekom čitanja **ne vidim usage** u tijelima funkcija. Možda je nekad ostao iza zaboravljene fix-ane logike (npr. "only sync DREAM_YACHT_AGENCY in dev").

Bigger smell: **prod main-source code references `common/test/` package**. To je anti-pattern:
- Test utility-ji ne smiju biti class-loaded u prod runtime-u
- Ovisnost prod → test kreira inverted dependency
- `ProdTestSamples.kt` možda sadrži druge fields koji su accidental-no izloženi prod-u

Detekt config vjerojatno ne flag-a "unused import" jer `MayBeConst` skreće pažnju drugamo.
**Posljedica:** code smell + minor classloading overhead. Pre-prod nije risk-bitan.
**Predloženi fix:** dva trivijalna koraka:
- (a) Provjeriti grep cijelog repa za `ProdTestSamples.DREAM_YACHT_AGENCY_ID` — gdje se koristi:
  ```bash
  grep -rn "DREAM_YACHT_AGENCY_ID" src/
  ```
  Ako nikoji nije aktivno korišten, drop konstantu i import-e.
- (b) Premjestiti `ProdTestSamples.kt` iz `src/main/kotlin/.../common/test/` u `src/test/kotlin/...` (proper test source set). Ako prod main usage konstanti opravdano, refactor da konstanta ide u `common/config/` ili sl. — ne u `common/test/`.

**Status:** WAITING-DECISION (trivijalan, F2-029/F2-030 family)

---

### [F3-014] LOW concurrency — `NauSysYachtOfferIntegrationServiceAsync` `@Async` metoda + TODO "Nausys only one call at the time" → bez locking-a
**Lokacija:** `NauSysYachtOfferIntegrationServiceAsync.kt:24-32`
**Detekcija:** statička
**Opis:**
```kotlin
@Async("taskExecutor")
fun syncOffersForDateRange(...): CompletableFuture<Unit> {
    // TODO Nausys only one call at the time
    ...
}
```
TODO comment indicates that NauSys API ne podržava concurrent calls (probably rate-limit, possibly serialization issue on partner side). But the method is `@Async("taskExecutor")` — može se invoke-irati paralelno. **Nije osigurano** da samo jedan call ide u datom trenutku.

Vjerojatnost concurrency:
- Public search flow (F1-064) može pozvati ovo iz request thread-a
- Scheduled sync također može trigger-irati
- Admin "force-sync" iz `NausysSyncController.kt` (controllers Phase 6)

Ako 3 requesta odluče sync-irati paralelno, sve 3 idu @Async u taskExecutor pool → svi pozivaju NauSys istovremeno → 429 rate-limit → svi 3 fail → retry storm.

`taskExecutor` config — treba provjeriti u Phase 5 (cross-cutting config), default Spring `SimpleAsyncTaskExecutor` = unlimited threads → no backpressure.
**Posljedica:** NauSys rate-limit cascading failures. Ne immediately exploitable, ali pri load spike-u guarantee fail.
**Predloženi fix:** dvije opcije:
- (a) **Distributed lock** (ShedLock — Phase 4 tema): u `@Async` metodi prvo claim lock, ako ne uspijemo skip. Pattern matchira scheduled job locking.
- (b) **Java `Semaphore(1)`** kao class-level field, `acquire()` u metodi. Lokalno na VM-u, ali Phase 4 govori da samo VM3 pokreće NauSys sync — ako je tako, jedan-thread bilo gdje na VM3 je dovoljan.

Trebamo verify u Phase 4 (jobs) da li je sync VM3-only. Ako da → (b) je dovoljan. Ako ne → (a).

Plus: maknuti TODO marker nakon fix-a (detekt flags `ForbiddenComment` već za druge TODO-e).
**Riziko-procjena fixa:** dira async flow — treba runtime test.
**Status:** OPEN — pair s Faza 4 jobs locking decision

---

### [F3-015] LOW info leak — Error messages exposing internal NauSys partner IDs u `error()` poruci
**Lokacija:** `NausysReservationIntegrationService.kt:138, 144`
**Detekcija:** statička
**Opis:**
```kotlin
?: error("No Location for NauSys locationFromId=${reservationResponse.locationFromId} and no fallback supplied")
```
`error()` baca `IllegalStateException` s message koja sadrži:
- Riječ "NauSys" (otkriva partner sustav)
- `locationFromId=${id}` (otkriva internal partner ID)

Combined s F1-055 (global error handler curi internu put-strukturu u 500 response body) + F1-066 (`IllegalStateException` s user-facing porukama u public endpointima): ova exception poruka može stići customer-u kroz public flow (`createOption` se zove iz `PublicReservationController`/`StripePaymentController` chain).

Customer dobije 500 response: "No Location for NauSys locationFromId=12345 and no fallback supplied". Otkriva:
- Sustav koristi NauSys (info leak za competitor / scraper)
- NauSys location ID 12345 (može pomoći sa enumeration ostalih)
- "fallback supplied" terminologija (internal implementation detail)
**Posljedica:** info leak (low severity, F1-055/F1-066 family). Ne immediately exploitable.
**Predloženi fix:** generic error messages:
```kotlin
?: throw IllegalStateException("Cannot resolve charter location for partner reservation")  // for logs
// + map to a non-leak 502 response in ApiErrorHandler
```
Combined s F1-055 fix (Phase 5 cross-cutting error handling sweep) — Jedan fix pattern pokriva.
**Status:** OPEN — Faza 5 (cross-cutting error sanitization)

---

### [F3-016] INFO positive — `NauSysCatalogueIntegrationService` je thin facade pattern; `NauSysAvailabilityIntegrationService.syncYachtAvailability` per-year + per-agency catch-and-continue resilience
**Lokacija:**
- `NauSysCatalogueIntegrationService.kt` (cijeli file) — 14 metoda, sve su 2-3 line pass-through `client.X() → syncService.X(response)`. Clean separation HTTP boundary od business mapping logic.
- `NauSysAvailabilityIntegrationService.kt:29-44` — nested loop `agencies × years` s per-iteration try/catch. Jedna agency ili godina koja fail-uje ne ruši cijeli job.
- `NausysReservationIntegrationService.toResponseWrapper` — explicit fallback Location handling (line 133-144); brani od orphan ExternalMapping pri location dedup-u.

**Opis:** Pozitivni patterni vrijedni standardizirati:
- Thin facade — model za druga integracijska područja (Stripe, mail) sljedeća.
- Per-iteration resilience — kombinirano s F3-011 popravkom (rate-limit budget) bila bi solidno cross-cutting pattern.
- Defensive fallback handling — ne pukne na partial data corruption.
**Status:** INFO

---

### Sažetak Batch 2

- **HIGH (1):** F3-009 (customer PII u HTTP body, F3-003 širenje)
- **MED (3):** F3-010 (responseBody PII storage), F3-011 (per-agency loop bez rate-limit), F3-012 (yacht re-sync ne discoveruje nove)
- **LOW (3):** F3-013 (ProdTestSamples u prod main-source), F3-014 (@Async bez locking + TODO), F3-015 (NauSys partner ID u error message)
- **INFO (1):** F3-016 (thin facade + per-iter resilience + defensive fallback patterni)

**Najkritičniji nalaz batch-a:** F3-012 (yacht re-sync ne discoveruje nove yachte). Pre-prod nije blocker, ali za fleet rast je realan bug — broker doda yacht, kupac ne vidi.

**F3-009** je nominal HIGH ali zatvara se s F3-003 fix-om (isti env var change). Stakeholder komunikacija ti za eskalaciju s pravnim timom: "credentials over plaintext" + "customer PII over plaintext" zvuče različito.

**Batch 2 završen. Batch 3 next:** MMK integration services (MmkCatalogueIntegrationService, MmkYachtIntegrationService, MmkYachtOfferIntegrationService, MmkAvailabilityIntegrationService, MmkReservationIntegrationService) — analogna NauSys-u ali via Bearer auth (no creds in body, no F3-003/F3-009 in HTTPS).

---
