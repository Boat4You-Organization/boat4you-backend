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

## Batch 3 — MMK integration services (2026-05-11)

### Prelim — što se NE ponavlja iz Batch 2

MMK ima HTTPS bazni URL (`https://www.booking-manager.com/api/v2`) + Bearer token u headeru → **F3-003 i F3-009 ne važe na MMK side**. Customer PII (clientName, crew list link) ide preko TLS-a.

Što SE direktno prenosi iz Batch 2 (note-only, ne novi finding ID):
- **F3-010** sibling — `MmkReservationIntegrationService.kt:183` ima isti `responseBody = objectMapper.writeValueAsString(reservationResponse)` PII storage pattern. Fix istovremen s F3-010.
- **F3-011** sibling — `MmkAvailabilityIntegrationService.kt:25-40` per-agency × per-year forEach + try/catch swallow, bez rate-limit budget. Plus `MmkYachtIntegrationService.kt:41-50` isti pattern. Fix istovremen s F3-011 (resilience batch).
- **F3-015** sibling — `MmkReservationIntegrationService.kt:141, 147` ima `error("No Location for MMK baseFromId=${id}...")` — isti partner ID leak. Fix istovremen s F3-015 (error sanitization batch).

---

### [F3-017] MED amplification — `MmkYachtIntegrationService.yachtTranslationsSync` 6-language × N-agency × @Retryable = stotine MMK calls po jednom sync run-u
**Lokacija:** `domains/external/mmk/service/MmkYachtIntegrationService.kt:83-114`
**Detekcija:** statička
**Opis:** `yachtTranslationsSync` iterira:
```
for each agency in active_agencies (~50+):
    for each language in [FR, DE, PT, IT, ES, HR] (6):
        mmkAuditedClient.getYachts(companyId = agencyExternalId, language = lang)
```
Linearno: **~300 calls** za 50 agencija × 6 jezika. Plus svaki poziva `@Retryable(maxAttempts = 3)` na client layer → worst case **900 calls** ako sve fail-aju.

Sekvencijalno (nije chunked parallelism kao u `MmkYachtOfferIntegrationService.yachtOfferSync`). Combined s F3-001 (no timeouts) — ako MMK ima 5-sec spike po pozivu, cijeli translation sync traje 25+ minuta linerano.

Plus: každim get-yachts pozivom MMK vraća full yacht catalog u tom jeziku — Veliki payload-i, gotovo identičan response uz različite name/description translations. Wasteful bandwidth.

Comparison s `MmkYachtOfferIntegrationService.yachtOfferSync` — ovaj koristi `agencies.chunked(3) + CompletableFuture.allOf + 15-min orTimeout` pattern. **`yachtTranslationsSync` ne**.
**Posljedica:** translation sync je single-thread bottleneck. Ako se izvršava istovremeno s drugim sync job-ovima (Phase 4 deep-dive za scheduling overlap), HikariCP / partner-side rate-limit pressure se kumulira.
**Predloženi fix:** dvije razine:
- (a) **Adopt isti chunked-parallel pattern** kao `yachtOfferSync` — `agencies.chunked(3).forEachIndexed { ... CompletableFuture.allOf(...) orTimeout 15-min }`. Cuts wall-time 3×.
- (b) **MMK API support za multi-language**: provjeri ima li `getYachts` parametar koji vraća sve jezike u jednom response-u (`languages = [FR, DE, ...]` ili sl.). Ako da, 50× redukcija calls.

(a) je trivijalan refactor (~20 linija). (b) ovisi o MMK API capabilities.
**Riziko-procjena fixa:** (a) dira async + transaction handling — verify-test za TransactionTemplate kompatibilnost.
**Status:** OPEN — Faza 5 (perf parallelism) ili pair s F3-011 batch fix

---

### [F3-018] LOW completeness — `MmkYachtIntegrationService.SUPPORTED_LANGUAGES` izričito **isključuje** EN; nejasno je li EN "default" ili gap
**Lokacija:** `domains/external/mmk/service/MmkYachtIntegrationService.kt:22-32`
**Detekcija:** statička
**Opis:**
```kotlin
companion object {
    val SUPPORTED_LANGUAGES = setOf<SupportedLanguagesEnum>(
        SupportedLanguagesEnum.FR,
        SupportedLanguagesEnum.DE,
        SupportedLanguagesEnum.PT,
        SupportedLanguagesEnum.IT,
        SupportedLanguagesEnum.ES,
        SupportedLanguagesEnum.HR,
    )
}
```
Set izričito izostavlja `EN`. MMK `SupportedLanguagesEnum` enum vjerojatno ima EN kao value. Pitanje:
- **Hipoteza A:** EN je "default" — MMK vraća EN content kad nema language parameter (line 66 koristi `companyId = ..., inventory = RAW` bez language). Tada je EN content već u DB-u iz `yachtSync` poziva, pa `yachtTranslationsSync` samo dodaje translations za druge jezike. **Verify:** `yachtSync` line 66 zaista vraća EN content?
- **Hipoteza B:** EN content je negdje gubi — yacht translation flow propušta EN. Verify if there's UI surface showing EN content where it goes through translation table.

Bez komentara nema mogućnosti odlučiti koja hipoteza vrijedi bez runtime testiranja.
**Posljedica:** ako hipoteza B važi, EN content (vjerojatno default UI jezik) je crippled. Pre-prod inquiry: ako boat4you customer base je 60% EN-language, ovo je perception issue.
**Predloženi fix:** trivijalan komentar na companion object:
```kotlin
companion object {
    // EN content is fetched by `yachtSync` itself (MMK returns EN when no
    // `language` param is set). Translation sync only mirrors the non-EN
    // variants. If MMK starts requiring explicit `language=EN` for
    // English content, add EN here and update yacht_translations to dedup.
    val SUPPORTED_LANGUAGES = setOf<SupportedLanguagesEnum>(...)
}
```
Plus: integration test koji verificira EN content presence.
**Riziko-procjena fixa:** dokumentacijski. LOW.
**Status:** WAITING-DECISION (verify hipoteza A vs B)

---

### [F3-019] LOW dead code — Deprecated `syncOffersForAgencyYachtsOld` i dalje compile-a, `@Async` annotated, i dalje 90+ linija duplicirane implementacije
**Lokacija:** `domains/external/mmk/service/MmkYachtOfferIntegrationServiceAsync.kt:166-263`
**Detekcija:** statička
**Opis:** Metoda `syncOffersForAgencyYachtsOld` je annotated:
```kotlin
/**
 * @deprecated Use syncOffersForAgencyYachts instead
 * I'm keeping this in an order to preserve logic for old MMK integration. ...
 */
@Deprecated("Use syncOffersForAgencyYachtsOld instead")
@Async("taskExecutor")
fun syncOffersForAgencyYachtsOld(...) { ... 90 lines ... }
```

Three concerns:
1. **Code dupliciran** — 90 linija logike paralelne s novom `syncOffersForAgencyYachts`. Drift risk: bugfix-evi koji se primijene u jednu mogu propustiti drugu.
2. **`@Async("taskExecutor")` i dalje aktivan** — Spring bean scanning ne ignora `@Deprecated`. Ako neki caller pozove ovu metodu, ide u taskExecutor pool.
3. **Caller-i** — grep da li je ovo i dalje pozvano negdje. Ako ne, drop.

Comment kaže "I'm keeping this in an order to preserve logic for old MMK integration" — implicira da je možda potreban fallback / regression toggle. Ali nema feature flag-a koji ga aktivira.
**Posljedica:** code smell + maintenance overhead. Ne immediately exploitable.
**Predloženi fix:** grep-verify caller-e:
```bash
grep -rn "syncOffersForAgencyYachtsOld" src/
```
- Ako nema caller-a → obriši metodu.
- Ako ima caller koji je također `@Deprecated` → ukloni cijeli chain.
- Ako stvarno je u upotrebi negdje → makni `@Deprecated` ili dodaj komentar zašto je still active.

**Status:** WAITING-DECISION (trivijalan grep + delete)

---

### [F3-020] INFO positive — `MmkYachtOfferIntegrationService.yachtOfferSync` chunked-of-3 parallelism + 15-min per-batch timeout
**Lokacija:** `domains/external/mmk/service/MmkYachtOfferIntegrationService.kt:31-58`
**Detekcija:** statička
**Opis:** Pattern koji bi NauSys offer sync trebao adoptirati:
```kotlin
agencies.chunked(3).forEachIndexed { index, agencyBatch ->
    val futures = agencyBatch.map { agency -> mmkYachtOfferIntegrationServiceAsync.syncOffersForAgencyYachts(agency, agency.getExternalId()!!) }
    try {
        CompletableFuture.allOf(*futures.toTypedArray())
            .orTimeout(15, java.util.concurrent.TimeUnit.MINUTES)
            .join()
    } catch (e: Exception) {
        log.error("MMK offer sync batch $index timed out or failed — agencies in batch: ...", e)
    }
}
```

Što ovaj pattern radi dobro:
- **Bounded parallelism:** 3 agencije per batch (ne unlimited @Async), upravljiv load na partner + taskExecutor pool
- **Per-batch deadline:** 15-min `orTimeout` — ako jedna agency hangira, ne blokira ostatak posla. Sibling F3-001 fix u sklopu RestClient layer-a, ali ovaj ujedno mitigira na orchestration level-u.
- **Per-batch logging:** error sadrži ID + ime svih agencija u batchu — observability solidan.

Plus: comment objašnjava motiv ("a single hung agency call ... used to block the entire 811-agency sync indefinitely") — historical context preserved.
**Posljedica:** Pre-prod nije problem. Vrijedno standardize-irati za NauSys + drugi sync job-ove.
**Predloženi fix:** dokumentirati pattern u `docs/superpowers/architecture.md` (ako postoji) ili kao CLAUDE.md note. Plus: tracking ticket za NauSys adopt istog pattern-a.
**Status:** INFO (apply pattern u F3-011 fix)

---

### [F3-021] INFO positive — `MmkReservationIntegrationService.confirmReservation` graceful crew list link fallback s nested try/catch
**Lokacija:** `domains/external/mmk/service/MmkReservationIntegrationService.kt:90-110`
**Detekcija:** statička
**Opis:**
```kotlin
fun confirmReservation(mmkReservationId: Long, ...): ReservationResponseWrapper {
    return try {
        val reservationResponse = mmkRetryableClient.confirmReservation(mmkReservationId)
        val crewListLinkResponse = try {
            val response = mmkRetryableClient.crewListLink(reservationResponse.id)
            response.link
        } catch (e: Exception) {
            log.error("Error fetching crew list link for MMK reservation ID: $mmkReservationId", e)
            null  // graceful — booking goes through without crew list URL
        }
        toResponseWrapper(reservationResponse, crewListLinkResponse, ...)
    } catch (e: Exception) {
        log.error("Error confirming MMK reservation", e)
        throw ExternalReservationException("Failed to confirm MMK reservation with ID: $mmkReservationId")
    }
}
```

Što je dobro:
- **Booking primary path je critical** — fail propagiše do caller-a (booking se ne završava bez confirmation).
- **Crew list link je secondary** — fail logira ali ne ruši booking. Customer dobija booking confirmation bez crew list URL-a u email-u, ali booking je važeći.
- **Graceful degradation** je explicit (vs swallow-and-pretend-all-OK pattern).

Worth standardize-irati: razlikovati "critical" partner calls (booking confirm) od "enhancement" (crew list, options metadata). Faza 5 cross-cutting pattern.

Plus: `partnerMsg = e.responseBodyAsString.trim().removeSurrounding("\"")` u createOption (line 81) — surfaces partner-side reject string kroz `ExternalOptionException("MMK: ${partnerMsg.ifBlank { "rejected option" }}")`. Pattern omogući korisnik nešto bolje od generic "external system failed" toast-a. **Combined s F3-015** (error info leak) treba pazljiv balans: korisnik dobije generic message, log dobije partner detalje.
**Status:** INFO (model za "primary vs enhancement call" pattern)

---

### Sažetak Batch 3

- **MED (1):** F3-017 (yachtTranslationsSync 6×N amplification — sibling F3-011 ali poseban scope)
- **LOW (2):** F3-018 (SUPPORTED_LANGUAGES ne uključuje EN — verify intent), F3-019 (deprecated `syncOffersForAgencyYachtsOld` dead code)
- **INFO (2):** F3-020 (chunked-3 + 15-min orTimeout pattern), F3-021 (graceful crew list link fallback)

**Note:** F3-010/F3-011/F3-015 ekvivalenti se direktno preslikavaju na MMK side (`MmkReservationIntegrationService.kt:183` responseBody, `MmkAvailabilityIntegrationService.kt:25-40` per-agency forEach, `MmkReservationIntegrationService.kt:141,147` partner ID leak). Fix-evi za te 3 finding-a u Phase 2 prefix-evima (F3-010, F3-011, F3-015) automatski pokrivaju MMK side ili su trivijalne sibling izmjene.

**MMK je solidniji od NauSys-a u nekoliko stvari:**
- HTTPS bazna URL → F3-003/F3-009 ne važe
- Chunked-parallelism + per-batch timeout u offer sync → ne treba F3-011 zaseban fix
- Bearer header umjesto creds-in-body → audit cleaner
- F3-021 graceful crew list fallback pattern

**Batch 3 završen. Batch 4 next:** Stripe — `StripeConfig`, `StripePaymentService`, `StripeWebhookController`, `StripePaymentController`, `PublicStripePaymentController`. Pair s F1-019 CRIT (webhook idempotency) + F1-031 (signature error) + F2-026/F2-036 (payment phase entity contract).

---

## Batch 4 — Stripe payment integration (2026-05-11)

### [F3-022] CRIT idempotency — `StripePaymentService.handleWebhookEvent` ne provjerava `paidOn` na re-delivery → dupli partner confirm + dupli email + duplikat reservation state-change
**Lokacija:** `domains/reservation/service/StripePaymentService.kt:169-201`
**Detekcija:** statička
**Opis:** Ovo je konkretizacija **F1-019** u Phase 3 contextu. Stripe explicitly designs webhook delivery as **at-least-once with retries**. Stripe docs: "*Endpoints must be idempotent. Stripe may deliver the same event multiple times.*"

Trenutni code u `handleWebhookEvent`:
```kotlin
if (event.type == "checkout.session.completed") {
    val dbPaymentPhases = paymentPhaseRepository.findByStripeSessionIdOrderByDeadlineAsc(session.id)
    if (payFullAmount != null && payFullAmount) {
        dbPaymentPhases.forEach(setPaymentMetadata(paymentIntentId))  // SET paidOn = Instant.now()
    } else if (payFullAmount != null && !payFullAmount) {
        dbPaymentPhases.first().apply(setPaymentMetadata(paymentIntentId))
    } else if (paymentPhaseId != null) {
        dbPaymentPhases.find { it.id == paymentPhaseId }!!.apply(setPaymentMetadata(paymentIntentId))
    }
    if (paymentPhaseId == null) {
        promoteReservationToBooking(reservationId)  // → confirmExternalReservation (partner API) + DB save + email
    }
}
```

**Failure scenario:**
1. Customer plaća → Stripe Checkout completes → Stripe šalje `checkout.session.completed` webhook.
2. Boat4You handle: `paidOn = Instant.now()`, partner confirmReservation poziv, email poslan.
3. Network blip između Boat4You ACK i Stripe → Stripe ne primi 200 OK → retry za 1 minutu.
4. Druga isporuka istog event-a:
   - `paidOn` se **PREPISUJE** s newer Instant.now() (silent — gubi se točan timestamp originale)
   - `setPaymentMetadata` postavlja **stripePaymentIntentId** isto (idempotent na DB samo ako paymentIntentId je isti)
   - **`promoteReservationToBooking` POZIV** se ponovo izvršava jer `paymentPhaseId == null` (initial reservation case)
   - `confirmExternalReservation` zove NauSys/MMK confirm — **drugi confirm na partner-side** = potencijalni dupli partner booking ili 4xx error
   - `sendConfirmationForReserved` — **drugi confirmation email customer-u** ("Vaša rezervacija je potvrđena" 2×)

Combined s F3-002 + F3-008 (retry × 9 partner calls) i F3-024 (TX wrap unutar webhook handler-a) — escalates.

**Što treba postojati za idempotency:**
- Provjera `paidOn != null` prije write-a → već-procesiran event = no-op
- ALI gornja check je nedovoljna jer dvije Stripe webhook deliveries mogu doći **istovremeno** (race condition) — treba pessimistic lock na payment phase, ili DB unique constraint na (`reservationFlowId`, `stripePaymentIntentId`) koji rejectira duplicate INSERT.
- Plus: idempotency-key store. Stripe `event.id` je guaranteed unique per delivery — pohrani u tablicu `processed_stripe_events(event_id PRIMARY KEY, processed_at)` i abort ako ID već postoji.

**Posljedica:** Real-money double-charge nije moguć (Stripe naplaćuje customer-a samo jednom, Stripe-side idempotent). **Ali:** dupli partner booking → revenue dispute. Dupli email → customer confusion + support load. Reservation state corruption (paidOn dva puta zapisan, drugi possibly nije ni `Instant.now()` ali nešto sasvim drugo u edge case-u).
**Predloženi fix:** dva sloja zajedno:
- (a) **Event-level idempotency:** dodaj tablicu `processed_stripe_events` (Flyway migracija):
  ```sql
  CREATE TABLE processed_stripe_events (
      event_id      VARCHAR(255) PRIMARY KEY,
      event_type    VARCHAR(64) NOT NULL,
      processed_at  TIMESTAMPTZ NOT NULL DEFAULT now()
  );
  ```
  Code: prvi step u `handleWebhookEvent` je `INSERT INTO processed_stripe_events (...) ON CONFLICT DO NOTHING RETURNING event_id`. Ako ON CONFLICT → vraćamo 200 OK + abort processing. Stripe vidi 200 i ne retry-a.
- (b) **Phase-level idempotency:** prije `setPaymentMetadata(paymentIntentId)`, provjeri `if (phase.paidOn != null) return`. Belt-and-braces.

Plus razmotri kako:
- Move `promoteReservationToBooking` u zaseban transactional helper koji checks "is reservation already in CONFIRMED state?"
- Email send — ne raditi unutar same TX-a (vidi F3-024)

**Riziko-procjena fixa:** dira hot payment path. **Mora se runtime-testirati u staging-u prije prod-a.** Stripe daje "test webhooks" feature za retry simulation — koristiti.
**Status:** OPEN — **CRIT, prod-blocker** (F1-019 confirmed)

---

### [F3-023] HIGH money-loss bug — `setSessionIdOnPaymentPhases` prepisuje `stripeSessionId` na phase bez čuvanja prethodne; drugi-paid-prvi-session = orphan payment
**Lokacija:** `domains/reservation/service/StripePaymentService.kt:217-230`
**Detekcija:** statička
**Opis:**
```kotlin
private fun setSessionIdOnPaymentPhases(...) {
    if (payFullAmount != null && payFullAmount) {
        reservationFlow.paymentPhases.forEach { it.stripeSessionId = sessionId }
    } else if (...) { reservationFlow.paymentPhases.oldest().stripeSessionId = sessionId }
    else if (paymentPhaseId != null) { reservationFlow.paymentPhases.find { it.id == paymentPhaseId }!!.stripeSessionId = sessionId }
}
```

Direct field assignment bez check-a postojeće vrijednosti. **Failure scenario:**
1. Customer klikne "Pay" → kreira se Stripe Session A, `phase.stripeSessionId = "sess_A"`.
2. Customer napusti Stripe Checkout stranicu bez plaćanja (nije auto-cancel; session ostaje "open" 24h kod Stripe-a).
3. Customer dolazi natrag, klikne "Pay" ponovo (npr. drugi browser tab, drugi uređaj, dan kasnije ali u istom 24h prozoru).
4. Backend kreira Session B, `phase.stripeSessionId = "sess_B"` (overwrites "sess_A").
5. **Customer u prvoj kartici (sess_A) završi plaćanje** (zaboravljeno session ostalo otvoreno; Stripe Checkout je još valid).
6. Stripe šalje webhook za sess_A → `paymentPhaseRepository.findByStripeSessionIdOrderByDeadlineAsc("sess_A")` vraća **EMPTY LIST** (phase ima sess_B na sebi sada).
7. Code:
   ```kotlin
   if (payFullAmount != null && payFullAmount) {
       dbPaymentPhases.forEach(setPaymentMetadata(paymentIntentId))  // empty loop, no-op
   }
   ```
   No-op. Phase ostaje `paidOn = null`. **Customer je upravo platio €X Stripe-u, ali naša DB to ne reflektira.** Customer dobija na Stripe success-redirect URL (`/payment-success?session_id=sess_A`), ali `checkPaymentStatus("sess_A")` (line 204) — query također vraća empty payment phases → status `PAYMENT_PENDING` (line 209: "if any dbPaymentPhases.paidOn is null"). 

Bonus: ako oba (sess_A i sess_B) eventually complete unutar 24h, double charge customer-u. Stripe svaki naplati. Naša DB samo zna o sess_B.

**Customer-facing impact:**
- Naplaćen, naša DB ne zna → admin ručno mora reconcile (look up Stripe Dashboard, manualno označiti paidOn).
- Reservation ne promoted u BOOKING jer `promoteReservationToBooking` ne pokreće se.
- Customer ne dobija confirmation email.
- Stripe Dashboard pokazuje paid; naša admin lista pokazuje unpaid.
**Posljedica:** **direct customer money + Boat4You reputational damage.** Manje vjerojatno u praksi nego F3-022 (zahtjeva specifičan UX flow), ali bilo koji customer support ticket "platio sam, vi kažete da nisam" = bug.
**Predloženi fix:** dvije razine:
- (a) **Ne prepisuj postojeći sessionId:** prije `phase.stripeSessionId = sessionId`, ako `phase.stripeSessionId != null && phase.stripeSessionId != sessionId` → invalidate stari session preko Stripe API `Session.expire(oldSessionId)` (Stripe podržava). Onda overwrite.
- (b) **Store as collection:** phase.stripeSessionId → `stripeSessionIds: List<String>` ili 1:N tablica `payment_phase_stripe_session`. Webhook lookup po sessionId i dalje radi. Više code change-a.

(a) je manje invazivan. Plus: webhook `checkPaymentStatus` flow treba update da pita Stripe za session status, ne samo DB (već je iznad noted u F3-022).
**Riziko-procjena fixa:** dira payment flow — staging test obavezan. Edge case scenariji: što ako sess_A je expired vs cancelled vs paid u trenutku Session.expire poziva.
**Status:** OPEN — **HIGH, money-loss bug, prod-blocker scenario**

---

### [F3-024] HIGH atomicity — `handleWebhookEvent` wrapping `@Transactional` proteže oko partner API call (`confirmExternalReservation`) + email send; partner-side double-write na rollback-u
**Lokacija:** `domains/reservation/service/StripePaymentService.kt:169-201, 238-243`
**Detekcija:** statička
**Opis:**
```kotlin
@Transactional(readOnly = false)
fun handleWebhookEvent(event: Event) {
    ...
    if (paymentPhaseId == null) {
        promoteReservationToBooking(reservationId)  // calls partner + DB + email
    }
}

private fun promoteReservationToBooking(reservationId: Long) {
    val externalReservation = reservationIntegrationService.confirmExternalReservation(reservationId)  // PARTNER API
    val reservationResponse = reservationMutationService.confirmReservation(reservationId, externalReservation)  // DB
    reservationEmailService.sendConfirmationForReserved(reservationResponse, PaymentType.CARD)  // SMTP
}
```

**Sve unutar jedne `@Transactional`.** Failure modes:
1. **Partner success, DB fail:** `confirmExternalReservation` uspjeva (NauSys booking confirmed) → `confirmReservation` baca exception (CHECK constraint, FK violation, anything) → **outer TX rollback.** Partner side ima confirmed booking, naša DB ima reservation u OPTION (pre-confirm) state. **Forensics: jedva ako se nakon-fact-a uoči.**
2. **Partner success, email fail:** ako email service throws → rollback → partner ima booking, naša DB nema confirmed reservation. Plus email nije poslan.
3. **DB success, email fail:** ako email throws nakon `confirmReservation` save-a — TX commit-a tek na method exit. Throw → rollback → ali partner is confirmed. Same as #1.

Same pattern kao F3-007 (TransactionTemplate audit pattern samo u Async metodama). Ovdje je extra weight jer **partner side change je already committed when boundary thrown** — Stripe webhook handler ne može rollback partner state.

Plus combined s F3-022 (Stripe retry will fire ponovo), F3-008 (partner retry × 9), F3-022 (no event-level idempotency) — full failure-cascade:
- Stripe retries webhook → opet ulazi → confirmExternalReservation ponovo zove partner → partner vidi "već confirmed" (možda 4xx ili duplicate)
- Email service ponavlja → customer dobiva 2-3 confirmation email-a
**Posljedica:** real partner state inconsistency. Pre-prod blocker uz F3-022.
**Predloženi fix:** dva sloja:
- (a) **Email send IZVAN TX-a:** `@TransactionalEventListener(phase = AFTER_COMMIT)` pattern. Publish domain event `ReservationConfirmedEvent` unutar TX. Listener šalje email AFTER COMMIT. Email failure ne ruši DB save.
- (b) **Partner call IZVAN TX-a:** `confirmExternalReservation` se zove **prije** ulaska u TX, ili u poseban TX. Onda DB save uvjetuje partner success. Ali što ako partner success → DB save fails → kako rollback partner? Nema atomic distributed TX bez sage pattern-a. **Sage pattern**: state machine kao `confirmed-on-partner`, retry/compensate na DB save fail.

Pragmatic minimum za pre-prod: (a) — moveconfirmation email outside TX. Plus: ako partner call fail, throw — DB write se ne pokušava, customer dobija "payment received but booking pending" status. To je manje od ideal-a ali ne kreira partner-DB drift.
**Riziko-procjena fixa:** veliki refactor za (b). (a) je manji ali još uvijek touchy.
**Status:** OPEN — **HIGH, paired s F3-022**

---

### [F3-025] MED defensive — Mnogo `!!` non-null assertions na Stripe payload-u; NPE pri unexpected Stripe events
**Lokacija:** `StripePaymentService.kt:176, 180, 189, 191`
**Detekcija:** statička
**Opis:**
- `session!!.id` (line 176) — `session` može biti null ako `event.dataObjectDeserializer.object.orElse(null) as? Session` vrati null (data object missing ili krivi tip)
- `session.metadata["reservationId"]!!.toLong()` (line 180) — metadata key missing = NPE; old Stripe sessions kreirane prije meta-data dodatka mogu nedostajati
- `dbPaymentPhases.first()` (line 189) — `first()` na praznoj listi baca `NoSuchElementException`
- `dbPaymentPhases.find { it.id == paymentPhaseId }!!` (line 191) — phase obrisan između session create i webhook delivery → NPE

Plus: `event.type == "checkout.session.completed"` flow ulazi i kad je session null. Bound code path:
```kotlin
if (event.type == "checkout.session.completed") {
    logger.debug("Payment for Stripe sessionId ${session!!.id} successful")
```
NPE pri `session!!.id`. Stripe webhook receive-a od bilo kojeg client-a koji ima signature — ako neki external proces (test mode, debug tool) šalje malformed event sa type "checkout.session.completed" ali null data, **app crash** unutar webhook flow → Stripe vidi 500 → retry × 3 → još 3 NPE.
**Posljedica:** webhook handler može padati u edge case-evima — Stripe retries pile up. F3-022 multiplier.
**Predloženi fix:** zamijeniti `!!` s explicit null checkovima + return early:
```kotlin
val session = event.dataObjectDeserializer.`object`.orElse(null) as? Session
if (session == null) {
    logger.error("Stripe event ${event.id} type ${event.type} has no Session payload — ignoring")
    return  // ack 200 to Stripe so it stops retrying
}
val reservationIdStr = session.metadata["reservationId"]
if (reservationIdStr == null) {
    logger.error("Stripe session ${session.id} missing reservationId metadata — ignoring")
    return
}
val reservationId = reservationIdStr.toLong()
...
```

Plus: `dbPaymentPhases.firstOrNull()` umjesto `first()`. `find { it.id == paymentPhaseId }` umjesto `find { }!!`.
**Riziko-procjena fixa:** trivijalan, ali code review prije commit-a obavezan jer dira webhook hot path.
**Status:** WAITING-DECISION (group s F3-022/F3-024 webhook fix batch)

---

### [F3-026] MED bug-čekanju — `payFullAmount=false` flow u webhook handler-u uvijek označava `first()` (earliest deadline) phase, ne phase koju kupac stvarno plaća
**Lokacija:** `domains/reservation/service/StripePaymentService.kt:184-192`
**Detekcija:** statička
**Opis:** Webhook code:
```kotlin
val dbPaymentPhases = paymentPhaseRepository.findByStripeSessionIdOrderByDeadlineAsc(session.id)
if (payFullAmount != null && payFullAmount) {
    dbPaymentPhases.forEach(setPaymentMetadata(paymentIntentId))
} else if (payFullAmount != null && !payFullAmount) {
    dbPaymentPhases.first().apply(setPaymentMetadata(paymentIntentId))  // <-- ovdje
} else if (paymentPhaseId != null) {
    dbPaymentPhases.find { it.id == paymentPhaseId }!!.apply(setPaymentMetadata(paymentIntentId))
}
```

`payFullAmount=false` is "pay first installment" pattern (line 91 u `initiatePayment`). `setSessionIdOnPaymentPhases` postavlja `stripeSessionId` samo na `oldest()` phase u tom slučaju (line 226). Tako da `findByStripeSessionIdOrderByDeadlineAsc(session.id)` vraća single-element listu — `first()` = `oldest()` = ispravan.

**Ali:** failure case je ako customer **kasnije plaća drugu installment** (line 89 — "pay first installment — next DUE unpaid phase, not the historically-oldest one which might already be paid"). U `initiatePayment` line 92 koristi `unpaidPhases.minByOrNull { it.deadline }` — može biti druga phase, ne earliest by deadline. Tada `setSessionIdOnPaymentPhases` (line 226) `oldest()` (definirano kao `minBy { it.deadline }` line 253) **ne uzima u obzir paidOn filter.** Postavi stripeSessionId na earliest phase **bez obzira što je paid**:

```kotlin
private fun Set<ReservationPaymentPhase>.oldest(): ReservationPaymentPhase = this.minBy { it.deadline }
```

Bez `.filter { it.paidOn == null }`. Ako phase 1 je paid (paidOn != null) i customer plaća phase 2:
1. `initiatePayment` calculates dbPrice = phase 2 amount (line 92, filteruje unpaid).
2. `setSessionIdOnPaymentPhases` (line 226, payFullAmount=false branch) postavlja sessionId na **phase 1** (`oldest()` — već paid!).
3. Stripe naplaćuje correct amount (phase 2 amount).
4. Webhook handler: `findByStripeSessionIdOrderByDeadlineAsc(session.id)` vraća **phase 1** (jer phase 1 ima sessionId, ne phase 2).
5. `dbPaymentPhases.first().apply(setPaymentMetadata(paymentIntentId))` → **prepisuje paidOn + stripePaymentIntentId na phase 1**. Phase 2 ostaje unpaid.

**Money mismatch:** customer paid phase 2 amount, ali DB sad pokazuje phase 1 paid (twice — overwrite) i phase 2 unpaid. Reservation flow je broken.

Stvarna vjerojatnost: ovaj scenario zahtjeva `payFullAmount=false` *and* phase 1 already paid. Vjerojatno je da se "pay installment 2" UI uvijek šalje `paymentPhaseId` parametarom, **ne** `payFullAmount=false`. Treba verify u frontend kodu (out of scope ovog review-a).
**Posljedica:** subtilan; vjerojatno nije pogođen u happy path. Ali sad-ili-nikad za fix prije prod-a.
**Predloženi fix:** uskladiti filter u `oldest()`:
```kotlin
private fun Set<ReservationPaymentPhase>.oldest(): ReservationPaymentPhase =
    this.filter { it.paidOn == null }.minByOrNull { it.deadline }
        ?: error("No unpaid payment phases")
```

Plus: dodati integration test koji simulira "pay installment 2 with payFullAmount=false" scenario.
**Riziko-procjena fixa:** trivijalan one-liner + test. MED severity.
**Status:** OPEN — pair s F3-022 fix batch

---

### [F3-027] MED operational — Stripe webhook handler tiho ignorira sve non-`checkout.session.completed` event-e (`payment_intent.payment_failed`, `charge.refunded`, `charge.dispute.created` ...)
**Lokacija:** `domains/reservation/service/StripePaymentService.kt:174-200`
**Detekcija:** statička
**Opis:**
```kotlin
if (event.type == "checkout.session.completed") {
    // process
} else {
    logger.error("Payment for Stripe sessionId ${session?.id} failed. Stripe event id: ${event.id}")
}
```

Else branch logira **kao da je failed**, ali Stripe šalje **mnogo** event-types:
- `checkout.session.expired` — session istekla nakon 24h bez plaćanja. Treba: invalidate `stripeSessionId` na phase (oslobađa za novi attempt).
- `payment_intent.payment_failed` — kupac neuspio platiti (kartica odbijena). Treba: notify customer, možda email "možete probati ponovno".
- `charge.refunded` — admin (ili Stripe sam) je refund-irao. Treba: označi phase `paidOn = null` ili novi `refundedAt` polje, notify customer/admin.
- `charge.dispute.created` — chargeback! Mora notify admin **immediately** za reakciju (Stripe dispute window je 7-21 dana ovisno o kartici).
- `payment_intent.created`, `payment_intent.succeeded` — info-only za nas, ali ne treba ih logirati kao "failed".
- `customer.subscription.*` — nismo subscription business, treba ignored explicitly.

Plus message "Payment failed" je **netočna** — log je "error" level ali većina event-types nisu greške. Spammed log-ovi.
**Posljedica:**
- Charge disputes nečuti — Stripe automatski refund-a kupcu nakon dispute window-a, mi gubimo cijelu rezervaciju + extra Stripe dispute fee (~$15).
- Refund-evi (legit) nisu reflektirani u našoj DB-i. Admin radi manual reconciliation.
- Log noise — pravi failures se gube u "everything is logged as error" mass-u.
**Predloženi fix:** explicit event-type handler routing:
```kotlin
when (event.type) {
    "checkout.session.completed" -> handleCheckoutCompleted(event, session)
    "checkout.session.expired" -> handleSessionExpired(event, session)  // clear stripeSessionId on phase
    "payment_intent.payment_failed" -> handlePaymentFailed(event, session)  // notify customer
    "charge.refunded" -> handleRefund(event)  // notify admin, mark phase
    "charge.dispute.created" -> handleDispute(event)  // ALERT admin
    "payment_intent.created", "payment_intent.succeeded", "checkout.session.async_payment_succeeded" -> {
        logger.debug("Stripe info event ${event.type}: ${event.id}")  // info-only
    }
    else -> logger.warn("Unhandled Stripe event type ${event.type}: ${event.id}")
}
```

Plus: dodati `processed_stripe_events` tabelu (F3-022) i log "duplicate event ignored" za retries — sad svaki event tip može provesti svoj idempotency check.

**Posebno za `charge.dispute.created`:** mora alert-irati admin u real-time (admin email + Slack ako postoji). Phase 5 cross-cutting alerting.
**Riziko-procjena fixa:** velik (mnogi novi handler-i). Faza 5 / Faza 7 work.
**Status:** OPEN — eskalacija (Stripe event handling roadmap)

---

### [F3-028] LOW pricing — `toCentsLong()` koristi `RoundingMode.UP` (uvijek round-up); slight overcharge €0.01 po payment-u
**Lokacija:** `domains/reservation/service/StripePaymentService.kt:245`
**Detekcija:** statička
**Opis:**
```kotlin
private fun BigDecimal.toCentsLong(): Long = this.setScale(2, RoundingMode.UP).times(100.toBigDecimal()).toLong()
```

`RoundingMode.UP` always rounds away from zero. Za price 12.341 → 12.35 → 1235 centi. Za 12.349 → 12.35 → 1235 centi.

Industry konvencija za billing:
- **HALF_UP** — 12.345 → 12.35, 12.344 → 12.34 (banker-friendly)
- **HALF_EVEN** (banker's rounding) — 12.345 → 12.34, 12.355 → 12.36 (statistically unbiased)
- **UP** — uvijek u korist seller-a (uvijek malo skuplja cijena za customer-a)

Razlika je u tisuću dijelovima na pojedinačnom payment-u, ali per-installment-per-reservation × tisuće reservation-a kroz vrijeme se akumulira. Plus: GDPR / consumer protection regulations u nekim jurisdikcijama traže `HALF_EVEN` za fairness.

Plus: `dbPrice` calculation prije ovog (line 100: `dbPrice + cardSurchargeAmount`) — surcharge percentage može imati > 2 decimale precision-a. UP rounding compounds drift.
**Posljedica:** customer prijavljuje "platio sam €.01 više nego što piše". Pre-prod nije blocker, ali brz fix.
**Predloženi fix:**
```kotlin
private fun BigDecimal.toCentsLong(): Long = this.setScale(2, RoundingMode.HALF_UP).times(100.toBigDecimal()).toLong()
```

Verify: business decision — preferira li boat4you uvijek round-up (extra revenue) ili neutral half-up? Tipično je business choice. Konsultiraj Mario.
**Riziko-procjena fixa:** trivijalan one-line.
**Status:** WAITING-DECISION (Mario business choice: UP vs HALF_UP)

---

### [F3-029] INFO positive — Stripe integration pokazuje 3 best-practice patterna kojih trebaju adoptirati NauSys/MMK i drugi sync flow-evi
**Lokacija:**
- `StripeConfig.kt:17-21` — explicit `setConnectTimeout(5_000)`, `setReadTimeout(15_000)`, `setMaxNetworkRetries(2)` (na Stripe SDK level-u)
- `StripePaymentService.kt:153-162` — idempotency-key support u `Session.create(params, idempotencyKey)`
- `StripeWebhookController.kt:36-48` — proper `Webhook.constructEvent` sa signature verification, distinct catch blocks za `SignatureVerificationException` (400 "Invalid signature") i `Exception` (400 "Webhook processing error") — F1-031 fix already applied here

**Opis:** Stripe integration je **funkcionalno** best-in-class u code base-u. Ne F3-022/F3-023/F3-024 govore "kod je loš" — govore "edge case handling treba dotjerati". Foundational layer (config, idempotency-on-create, signature verification) je dobro postavljen.

Što vrijedi standardize-irati za druge integration-e:
- **Timeouts at SDK level** — pattern koji NauSys/MMK RestClient layer može copy-paste-ati (F3-001 fix priority).
- **Idempotency-key na write side** — koncept koji NauSys/MMK rezervacije također trebaju (F3-002 fix priority).
- **Signature/auth verification na webhook ingress** — Stripe je jedini partner s webhook-om, ali ako se ikad doda npr. MMK webhook → kopirati ovaj pattern.

Plus: `application.stripe.enabled` flag (line 247) omogućuje toggle integration-a per environment. **Anti-pattern**: `throw RuntimeException("Stripe is not enabled")` (F1-027 LOW already filed) — ali sam toggle je dobar.

**Riziko-pre-prod:** F3-022/F3-023/F3-024 idempotency fix-evi prije prod-a su prioritet **iznad** F1-019 (koji je već flag-iran kao prod-blocker). Sve ostale F3-025/F3-026/F3-027 mogu pratiti.
**Status:** INFO

---

### Sažetak Batch 4

- **CRIT (1):** F3-022 (webhook handler non-idempotent — F1-019 konkretizacija)
- **HIGH (2):** F3-023 (stripeSessionId overwrite → orphan payment / money-loss scenarij), F3-024 (`@Transactional` wraps partner API + DB + email — atomicity violation)
- **MED (3):** F3-025 (mnogo `!!` u webhook payload handlingu), F3-026 (`oldest()` ne filtrira unpaid; bug-u-čekanju za installment 2 path), F3-027 (event-type filter ignorira refund / dispute / expired)
- **LOW (1):** F3-028 (RoundingMode.UP overcharge)
- **INFO (1):** F3-029 (Stripe foundational patterns za standardize)

**Najkritičniji nalaz batch-a:** **F3-022 + F3-023 + F3-024 zajedno čine payment integrity prod-blocker batch.** F1-019 CRIT iz Phase 1 ovdje dobiva konkretne fix-points. **Sve troje treba ići u single Stripe-hardening commit prije prod-a.**

Fix sequencing:
1. F3-022 — event-level idempotency (Flyway migracija + table + first-step check)
2. F3-024 — email out-of-TX (`@TransactionalEventListener AFTER_COMMIT`); partner call ostaje (vidi F3-024 fix predlog)
3. F3-023 — Session.expire na old sessionId prije overwrite
4. F3-025 — defensive null checks
5. F3-026 — `oldest()` filter za unpaid
6. F3-027 — Faza 5/6, ne pre-prod blocker
7. F3-028 — Mario decision

**Plus combined s Phase 2:**
- F2-026 + F2-036 (mutable equals/hashCode na ReservationPaymentPhase + OfferPaymentPlan u Set-u) ide u istu Stripe-hardening fix batch. Sve troje povezano s payment phase lifecycle.

**Batch 4 završen. Batch 5 next:** Mail — `EmailService`, `ReservationEmailService`, `InquiryEmailService`, `PaymentPendingNotificationService`, `BirthdayEmailJob`. PII u email-u, SMTP error handling, async retry.

---

## Batch 5 — Mail integration (EmailService + downstream) (2026-05-11)

### [F3-030] MED security — User-controlled email/name fields (`inquiry.email`, `user.name`/`surname`) putuju u `setReplyTo`/`setTo` headers bez explicit CRLF/comma sanitizacije; potencijal header injection
**Lokacija:**
- `InquiryEmailService.kt:99` — `replyTo = inquiry.email` (public-form input, F1-068 vector)
- `BirthdayEmailJob.kt:69` — `recipientAddress = "$fullName <${user.email}>"` (user-profile input)
- `ReservationEmailService.kt` — slični pattern-i (verify u Batch follow-up)
- `EmailService.kt:207-216` — `helper.setTo(recipients.toTypedArray())`, `helper.setReplyTo(effectiveReplyTo)`

**Detekcija:** statička
**Opis:** JavaMail's `MimeMessageHelper.setReplyTo(String)` interno poziva `InternetAddress.parse(...)` koji **splita na komu** (jer email field može biti list). Ako attacker uspješno submit-ne inquiry s emailom poput `"victim@target.com,attacker@evil.com"`, JavaMail parse-a **dvije adrese** i obje stavi u Reply-To header.

CRLF u stringu — moderni JavaMail (Jakarta Mail 2.x) reject-a; throw-uje `AddressException`. Plus: `MimeUtility.unfold(String)` strip-a CRLF u header value-u. **Tako da CRLF injection sam po sebi je teško eksploatibilan** s aktualnim JavaMail-om. Ali **comma-injection** za multi-recipient cilja je vjerojatno otvoren — InternetAddress smatra to legitimnim multi-address syntax-om.

Concrete attack scenariji:
1. **Reply-To list injection:** anonimni napadač submit-ne inquiry s `email = "lead@target.com,attacker@evil.com"`. Mario reply (admin clicks Reply u Outlook-u) ide na **oba** adresarima. Attacker sazna inquiry sadržaj + Mario follow-up message.
2. **Recipient injection u birthday/transactional flow:** user registers s `email = "victim@me.com,attacker@evil.com"` (ako registration validacija pusti). Svaki transactional email going to user (booking confirmation, password reset, birthday) BCC-a attacker-a implicit-no preko To: field-a. **Severity gore — attacker dobiva password reset linkove.**

Mitigation zavisi od:
- **Input validation u InquiryController, RegistrationController, UserMutationService** — jesu li `email` field validacije strict (single address, no comma, no whitespace)? Treba verificirati via Phase 5 cross-cutting (input validation sweep).
- **JavaMail behavior verification** — runtime test s malformed addresses za potvrdu da neki path nije bypass-an.

`fullName` (line 69 BirthdayEmailJob) takav je `${user.name} ${user.surname}`. Ako name/surname imaju RFC-2822 special chars (komu, angle bracket), JavaMail's `InternetAddress` constructor (s `personal` parametrom) trebao bi izboriti, ali `recipientAddress = "$fullName <${user.email}>"` ide kao **plain string** u `helper.setTo(arrayOf(recipientAddress))` → parse-an as raw "Personal <email>" string. Ako user.name ima nezaštićen `>` ili komu, parse fail → throws → BirthdayEmailJob catch-suit (line 86-89) increments `skipped`, log error, continue. Ne immediate exploit, ali tihi delivery skip.
**Posljedica:** depending on input layer strictness — MED if input validation u Phase 5 nije bullet-proof. Anonymous public inquiry endpoint je primary risk vektor.
**Predloženi fix:** dva sloja:
- (a) **Explicit sanitization u EmailService:** prije `helper.setTo`/`setReplyTo`, verify:
  ```kotlin
  private fun assertSingleAddress(value: String, field: String) {
      val parsed = jakarta.mail.internet.InternetAddress.parse(value, true)  // strict
      require(parsed.size == 1) { "$field must be a single address, got ${parsed.size}: $value" }
  }
  ```
  Pozvati prije `setReplyTo(replyTo)`. Plus: dump arg na log za audit.
- (b) **Input boundary validation:** u Inquiry controller, User registration / mutation — `@Email` (Bean Validation) annotation je već vjerojatno tu, ali ne reject-a multi-address. Custom validator `@StrictEmail` koji uses `InternetAddress.parse(..., true)` + asserts size==1.

(b) je proper fix. (a) je defense-in-depth.
**Riziko-procjena fixa:** trivijalan. Phase 5 input validation sweep tema.
**Status:** OPEN — Faza 5 (cross-cutting input validation + email sanitization)

---

### [F3-031] MED operational — SMTP failures swallowed bez retry-ja, bez audit-a; transactional email loss (booking confirmation, password reset) silent
**Lokacija:** `domains/catalouge/services/EmailService.kt:253-263`
**Detekcija:** statička
**Opis:**
```kotlin
executor.submit {
    try {
        mailSender.send(message)
    } catch (e: MailException) {
        logger.error("Could not send email to '$recipients' because '${e.message}'")
    }
}
```

Email send je deferred preko `afterCommit` synchronization (vidi F3-033 INFO — well-designed). Ali ako SMTP send fail-a:
- Exception caught, logged at error level
- **No retry** — ali transient SMTP errors (rate-limit, temporary DNS, mailbox quota) su uobičajeni
- **No audit trail** — nigdje DB-side zapis "we owe customer email X for reservation Y"
- **No alerting** — admin nema notification da je booking-confirmation email failed za rezervaciju X

Customer impact scenariji:
- Customer plaća → booking confirmed u DB → confirmation email SMTP fails (npr. Gmail temporarily rate-limits us) → customer čeka email koji nikad ne dođe → support ticket "platio sam, nisam dobio potvrdu"
- Password reset → user requests reset → SMTP fail → user nikad ne dobije link → user retry-a 5 minuta kasnije → opet fail → user frustration
- Inquiry notification → Mario nikad ne sazna da je novi customer poslao inquiry → izgubljeni lead

Combined s F1-074 (test divergence) — nema test coverage-a za SMTP failure paths. Plus: F2-038 (ReservationDocument audit gap) family — ovo je email-side audit gap.

Plus: logger error level fires svaki put → log noise ako SMTP ima transient issues.
**Posljedica:** customer email reliability problem. Pre-prod nije akutni blocker jer SMTP je rijetko fail-a, ali pri partner-side (Gmail/Outlook) rate-limit episodes može cascadirati.
**Predloženi fix:** dva sloja:
- (a) **Outbox pattern (pre-prod minimum):** `email_outbox` tablica (Flyway migracija) — INSERT row na svaki `sendEmail` call: `(id, recipient, subject, template, variables_json, status='PENDING', created_at, attempts=0)`. `mailSender.send(message)` UPDATE row to status='SENT' + sent_at. SMTP failure UPDATE attempts++, status='FAILED' if attempts >= max. Scheduled retry job re-pickup-a 'FAILED' rows starije od X min. Audit trail garantovan.
- (b) **Resilience4j retry on `mailSender.send`:** 3 attempts with exponential backoff. Simpler ali ne audit-rješava.

(a) je standard Spring/JPA pattern; ~50 linija code-a + 1 migracija. Preporučljivo.
**Riziko-procjena fixa:** dira hot send path. Staging test obavezan.
**Status:** OPEN — Faza 5 (resilience) ili pre-prod minimum

---

### [F3-032] LOW privacy — `helper.setTo(recipients.toTypedArray())` showuje sve recipient adrese svakoj recipient-u (admin notifications, batch sends)
**Lokacija:** `domains/catalouge/services/EmailService.kt:207`
**Detekcija:** statička
**Opis:** Standard SMTP behavior: ako `setTo(recipient1, recipient2, recipient3)` setuje 3 adresa, **svaki primatelj vidi sve 3 u To: header-u**. To je legitimna behavior, ali za batch-send admin notifications (F2-011 `findAllAdminEmailAddresses`) ovo izlaže admin team-ove email adrese međusobno:
- Mario interpres-uje recipient list kao bcc, šalje sa svim admin emails u To
- Admin1 dobija email, vidi Admin2 + Admin3 email adrese u headeru
- Marginalan privacy issue unutar team-a

Plus: ako negdje `recipients = listOf(customer1.email, customer2.email)` se ikad pojavi (cross-customer marketing? unlikely per Mario rule "newsletter smo makli", ali grep da provjerimo), to bi bio veći leak.

Trenutni audit: BirthdayEmailJob, InquiryEmailService, ReservationEmailService svi šalju single-recipient (single user) → no current exposure. Tehnički-LOW concern dok god se ne doda multi-recipient flow.
**Posljedica:** pre-prod nije problem. Future-flag za batch sends.
**Predloženi fix:** dva pattern-a:
- (a) **BCC za batch:** kad `recipients.size > 1`, use `helper.setBcc(...)` umjesto `setTo(...)`, ide `helper.setTo("noreply@boat4you.com")` (placeholder). Standard pattern.
- (b) **Per-recipient send:** loop `recipients.forEach { helper.setTo(arrayOf(it)); mailSender.send(...) }` — više SMTP traffic-a ali zero leakage.

(a) je pragmatic.

Plus: dodati assert u EmailService — ako `recipients.size > 1` log warning ili throw u dev profile.
**Status:** OPEN — Faza 5 (defensive design)

---

### [F3-033] INFO positive — `EmailService` foundation je best-engineered file u code base-u; obrazac za druge transactional integration-e
**Lokacija:** `domains/catalouge/services/EmailService.kt` (entire file)
**Detekcija:** statička
**Opis:** Pattern-i koji vrijede standardize-irati / dokumentirati:
- **`@TransactionSynchronization.afterCommit` defer** (line 264-272) — SMTP send se izvršava tek **AFTER** outer JPA TX commits. Email failure ne može triggerirati TX rollback. Email never sent if outer TX rolls back. **Mitigira F3-024** (email-side concerns); čisto rješenje za async outgoing side-effects.
- **Virtual thread executor** (`Executors.newVirtualThreadPerTaskExecutor()`, line 56) — Project Loom (Java 21) — clean concurrency bez ThreadPool-a, perfect za blocking I/O.
- **Dev-log-to-file escape hatch** (line 41) — dev profile renders template to disk, no SMTP required. Tests email content lokalno.
- **`force` parameter** za one-off real-SMTP tests u dev-u. Razdvaja debug-flow od prod-flow.
- **`extraInlineImages` + `fromOverride`** — clean multi-brand support bez baking-into-config.
- **`locale` parameter + i18n message bundles** — i18n built-in.
- **`dynamicAttachments`** za per-send generated PDFs.
- **Detailed historical comments** — `templatesWithoutSharedFooter`, `redesignedHeaderTemplates`, `templatesWithBookingVector` — sve sa contextom zašto/kad (line 67-138). Senior-level documentation.

Plus: `writeEmailToDevLog` sanitizira filename input (line 301-307) — defensive code style.

**Najvažnije za drugu reportažu:**
- **F3-024 fix predlog "email out-of-TX preko `@TransactionalEventListener AFTER_COMMIT`"** — već implementirano kroz `afterCommit` pattern u EmailService. F3-024 zaista treba ažurirati: email-side concerns u F3-024 ne postoje. Realna F3-024 priča je **partner API call unutar TX-a + DB save fail = partner-confirmed-DB-rolled-back drift**, ne email. Email je safe.

Update F3-024 status field u sljedećem REGISTER pass-u da reflektira scope clarification.
**Status:** INFO

---

### [F3-034] INFO positive — `BirthdayEmailJob` GDPR-aware cron design
**Lokacija:** `domains/users/job/BirthdayEmailJob.kt:1-93`
**Detekcija:** statička
**Opis:** Cron implementacija pokazuje GDPR awareness:
- **Header comment objašnjava legal basis** (line 16-31) — "legitimate interest" justification, soft-deleted user skip, opt-out path documented.
- **Soft-delete check** (line 56) — `user.deletedAt != null` → skip + skipped counter increment. Komentar (line 28-30) objašnjava zašto: anonymized email je undeliverable, ne želimo GDPR audit noise.
- **Per-user try/catch** (line 55-89) — jedna user-side failure ne ruši cron za ostale. Sent/skipped counters logged.
- **Locale resolution sa fallback** (line 63) — `user.language?.toLocale() ?: Locale.ENGLISH` — defensive.
- **`getFullName().trim().takeIf { it.isNotBlank() } ?: "there"`** (line 64) — graceful fallback ako name fields prazni.

Combined s F2-012 (birthday index defer to Phase 6), full birthday flow je solidan. Pattern za druge per-user cron-eve.
**Status:** INFO

---

### Sažetak Batch 5

- **MED (2):** F3-030 (CRLF/comma injection u email headers), F3-031 (SMTP failure swallowed bez retry / audit)
- **LOW (1):** F3-032 (`setTo` multi-recipient privacy leak — currently no exposed flow)
- **INFO (2):** F3-033 (EmailService best-engineered foundation), F3-034 (BirthdayEmailJob GDPR-aware)

**F3-024 scope clarification (note-only):** Realna F3-024 priča je **partner API call unutar webhook TX-a → DB save fail = partner-confirmed-DB-rolled-back drift**. Email side je SAFE — `EmailService.sendEmail` koristi `afterCommit` synchronization, email never fires ako TX rollback-a. F3-024 fix se svodi na partner-call placement, ne email.

**Mail foundation je best-in-class.** Glavni concerns su:
1. Input validation na boundary (F3-030 — Faza 5)
2. Outbox pattern za reliable transactional email (F3-031 — Faza 5 ili pre-prod)
3. Trivial defensive checks (F3-032)

**Batch 5 završen. Batch 6 next:** Sync orchestration + admin controllers (`ExternalSyncService`, `ServiceCallCacheService`, `ServiceCallAuditService`, `ExternalMappingService`, `MmkSyncController`, `NausysSyncController`, `DevEquipmentSyncController`) — F1-064 pairing (public yacht search sync trigger), admin sync trigger auth.

---

## Batch 6 — Sync orchestration + admin controllers (2026-05-11)

### [F3-035] HIGH security — `DevEquipmentSyncController` na `/public/dev/...` ima `@Profile("dev")` ali **bez** `@PreAuthorize`, sve `@GetMapping` (no F1-042), eksponira NauSys credentials kao diagnostic; F1-041 produbljen
**Lokacija:** `domains/external/dev/DevEquipmentSyncController.kt:30-170`
**Detekcija:** statička
**Opis:** F1-041 zabilježio: "DevEquipmentSyncController na `/public/dev` + samo profile gating (no auth)". Ovaj batch otkriva **višestruke layers defense missing-a:**

1. **`@RequestMapping("/public/dev")`** — `/public/**` prefix u Spring Security konfiguraciji vjerojatno znači `permitAll()` (verify u SecurityConfiguration). Plus `@Profile("dev")` — only enabled u dev profile-u. **Ali ako dev profile se ikad enabled na staging/prod (accidental config, ENV override),** controller je live + anonymous.

2. **Sve metode `@GetMapping`** (lines 53, 99, 122, 129, 149, 164) — F1-042 fix za state-changing sync triggers već primijenjen u MmkSyncController/NausysSyncController (POST). Ovdje su:
   - `GET /clear-caches` — wipes ALL Spring caches
   - `GET /sync-equipment-catalog` — triggers MMK + NauSys equipment sync (~minutes)
   - `GET /sync-catalogue` — triggers full NauSys catalogue sync
   - `GET /resync-yachts` — triggers full yacht sync (~minutes, 12k yachts)
   - `GET /nausys-yacht-equipment/{companyId}/{yachtId}` — diagnostic, exposes catalog
   - `GET /mmk-yacht-equipment/{companyId}/{yachtId}` — same for MMK

   Browser preflight, link previews, proxy retries → all trigger these. Plus easy to enumerate via curl.

3. **`auth.username!!`, `auth.password!!`** (line 61-62 in `nauSysYachtEquipment`) — direct access to NauSys credentials, passed to AllYachtsRequest. **Even though the request body goes to NauSys (not response to user)**, the surrounding context exposes that this endpoint has access to creds.

4. **Diagnostic endpoints return raw partner data** — `nauSysYachtEquipment` returns equipment catalog + quantities + names. If accessible, **information disclosure** about competitor partner data structure.

5. **No rate-limit** on dev endpoints — anonymous attacker can `for i in {1..10000}; do curl /public/dev/resync-yachts; done` → DoS to Boat4You + amplified DoS to partners (each call fires sync).

**Combined risks:**
- F1-041 (this controller exists at `/public/dev`)
- F1-003 (no nginx rate limit on auth endpoints; presumably same on `/public/**`)
- F3-001 (no HTTP timeouts)
- F3-002 (state-change retry)

**Defense in depth missing.** Per dev/staging promotion script, if `SPRING_PROFILES_ACTIVE=dev` is ever set on staging/prod (typo in deploy yml, dev .env file leaked into VM4) → instant full compromise.
**Posljedica:** prod safe only because `@Profile("dev")` strict. Single-point-of-failure security model. F1-041 should be HIGH not LOW (Phase 1 listed as HIGH already).
**Predloženi fix:** trojni:
- (a) **Move `/public/dev` → `/admin/dev`** + add `@PreAuthorize("hasRole('SYSTEM_ADMIN')")`. Removes public path semantic, adds auth layer.
- (b) **Migrate state-changing endpoints to `@PostMapping`** — `clear-caches`, `sync-*`, `resync-yachts` all become POST. Diagnostic GETs (`nausys-yacht-equipment`, `mmk-yacht-equipment`) stay GET (read-only).
- (c) **Multi-profile check:** in `SecurityConfiguration` enforce `if ("dev" in activeProfiles && "prod" in activeProfiles) throw IllegalStateException`. Prevents accidental dev-on-prod activation.

Plus: rate-limit per IP (5 req/min) at nginx (per F1-003 phase 7 deploy window).

(a) + (b) for ~30 min work. (c) ~10 min.
**Riziko-procjena fixa:** trivijalan. Should pair with F1-041 close-out.
**Status:** OPEN — **HIGH, paired s F1-041 closeout**

---

### [F3-036] MED data integrity — `ServiceCallCacheService` koristi `Objects.hash(...).toLong()` (Int range cast) za cache ključ; hash collisions na high-volume yacht offer cache → silent sync skips
**Lokacija:** `domains/external/service/ServiceCallCacheService.kt:77-83, 85-92`
**Detekcija:** statička
**Opis:**
```kotlin
private fun calculateHash(id: Long, dateFrom: LocalDate?, dateTo: LocalDate?): Long {
    return Objects.hash(id, dateFrom, dateTo).toLong()
}

fun createSyncYachtOffersHashSorted(startDate: LocalDate, endDate: LocalDate, locations: List<String>): Long {
    val sortedLocations = locations.sorted()
    return Objects.hash(startDate, endDate, sortedLocations).toLong()
}
```

`Objects.hash(vararg)` returns Java `Int` (~4 billion possible values). `.toLong()` widens but **doesn't increase entropy** — still only 2^32 distinct values. Cast to Long for DB column but underlying value remains Int range.

**Volume math:**
- yachtId × dateFrom × dateTo cache: for ~100 yachts × ~365 days × ~365 days = **~13M combinations**.
- Birthday paradox: collision probability ~30% by 2^16 = 65K entries (sqrt of 2^32). For 13M entries, collisions are guaranteed.
- For yacht-search cache (startDate × endDate × locations): location list combinatorial explosion — collisions even more likely.

**Concrete failure:**
1. User search for yacht X, date range A → cache miss → sync fires → cache entry saved with hash H1.
2. User search for yacht Y, date range B → hash collision with H1 → cache hit (false) → sync skipped.
3. User sees stale offers for yacht Y because sync never happened.

`shouldCallOffer` (line 19) returns `false` on collision → sync skipped → user sees offers from previous day's sync (or empty).

**Cache TTL is 1 hour** (line 29-31) → mitigates somewhat (stale ≤ 1h). But **cumulative collision rate over 1 week × all users** = significant percentage of stale searches.

Plus: cache table `service_call_cache` grows unbounded (F2-002 sibling for ServiceCallCache vs Token table).
**Posljedica:** false cache hits → stale yacht availability → customer sees offers koje ne postoje more / vice versa. Sales-impacting bug ali tihi (no error). Combined s F3-022 (Stripe webhook idempotency) i payment double-charge scenarios, ovo dodaje "wrong offer charged" loop.
**Predloženi fix:** dvije opcije:
- (a) **SHA-256 ili MurmurHash3 (Guava)** — proper hash with 64-bit (Long) or 128-bit space:
  ```kotlin
  import com.google.common.hash.Hashing
  private fun calculateHash(id: Long, dateFrom: LocalDate?, dateTo: LocalDate?): Long {
      return Hashing.murmur3_128()
          .newHasher()
          .putLong(id)
          .putString(dateFrom?.toString() ?: "", Charsets.UTF_8)
          .putString(dateTo?.toString() ?: "", Charsets.UTF_8)
          .hash()
          .asLong()
  }
  ```
  Collision prob → astronomically low.
- (b) **Composite primary key** — change `service_call_cache.hash_code` from BIGINT to `(method, yachtId, dateFrom, dateTo)` tuple. Schema change. Cleaner but more migration work.

(a) is pre-prop minimum.

Plus: add retention policy for `service_call_cache` rows older than 30 days.
**Riziko-procjena fixa:** dira hot search path. Staging test obavezan — verify shouldCallOffer behavior under load.
**Status:** OPEN — MED, real bug; pair s F2-002 retention

---

### [F3-037] MED concurrency — `ExternalSyncService.yachtSyncsInProgress` lock je VM-local `ConcurrentHashMap.newKeySet()`; VM2 + VM3 ne dijele state → dupli concurrent sync moguć
**Lokacija:** `domains/external/service/ExternalSyncService.kt:36, 94-95, 138-140`
**Detekcija:** statička
**Opis:**
```kotlin
private val yachtSyncsInProgress: MutableSet<Long> = ConcurrentHashMap.newKeySet()
...
if (!yachtSyncsInProgress.add(yachtId)) {
    return  // already syncing, skip
}
try { ... } finally { yachtSyncsInProgress.remove(yachtId) }
```

**Singleton bean → JVM-singleton state.** Boat4You deployment per memory: VM2 (API) + VM3 (sync). Different JVM instances → different `yachtSyncsInProgress` instances. **Mutex bypass:**
1. VM2 receives user search for yacht X → triggers `syncYachtOffers(yachtId=X)`.
2. VM3 scheduled cron fires `MmkSyncJob` → also touches yacht X via different code path.
3. Both instances pass `yachtSyncsInProgress.add(X)` → both proceed → 2× partner API calls in parallel for same (yachtId, dateRange).

Partial scope: only `syncYachtOffers(yachtId, dateFrom, dateTo)` overload uses this mutex (line 94). `syncYachtOffers(startDate, endDate, locations)` overload doesn't have similar mutex — wider concurrent fire still possible.

Plus: combined s **F3-005** (no jitter on retry backoff) — double-fire at same partner-side burst.

`shouldCallOffer` cache check (line 89) provides partial mitigation — cache hit returns false. But race window between cache check and cache write:
- T0: VM2 calls `shouldCallOffer(X)` → cache miss → returns true → starts sync
- T1: VM3 calls `shouldCallOffer(X)` → cache miss (still) → returns true → starts sync
- T2: Both call partners
- T3: Both save cache → second save is overwrite, no error
- Result: 2× partner work, 1 cache entry

**Severity:** depending on partner rate-limit budget. NauSys rate-limit unknown; MMK is generally permissive. Pre-prod nije akutni, ali se akumulira.
**Posljedica:** wasted partner calls + amplified pressure on partner API during peak times. Combined s F3-001 (no timeouts) + F3-005 (no jitter) = real DoS risk to partner during outage.
**Predloženi fix:** distributed lock. Optionsi:
- (a) **ShedLock** (jedna od planiranih za Faza 4 — locking VM2 vs VM3) — annotate with `@SchedulerLock` or use `LockProvider` API:
  ```kotlin
  lockProvider.lock("syncYachtOffers-$yachtId", duration).use {
      ...
  }
  ```
- (b) **Redis SETNX** — simpler if Redis already in stack (verify; per yml not visible).
- (c) **Postgres advisory lock** — `pg_try_advisory_lock(hash(yachtId))` — no extra infrastructure.

Phase 4 (jobs + heavy native) will tackle locking holistically per spec. **Mark F3-037 paired-with-Phase-4 architectural decision.**
**Riziko-procjena fixa:** infrastructure decision; >30 minutes refactor.
**Status:** OPEN — pair s Faza 4 jobs locking decision (covered F2-031 family for EAGER too)

---

### [F3-038] LOW fragility — `ExternalSyncService.syncYachtOffers(yachtId)` chained `!!` on entity associations
**Lokacija:** `domains/external/service/ExternalSyncService.kt:102` (`yacht.agency!!.primarySource!!.externalSystem!!`)
**Detekcija:** statička
**Opis:** Chained non-null assertions on lazy-loaded JPA associations. Failure modes:
- `yacht.agency = null` (data: yacht without agency — should be rejected at schema level, but legacy data may have)
- `agency.primarySource = null` (line 110-112 u Agency entity uses `agencySources.find { it.primary }` — null ako nijedan `primary = true`)
- `primarySource.externalSystem = null` (FK nullable verify u AgencySource entity)

Any null → NPE. NPE caught by outer `try/catch (Exception)` (line 136) → log error + return. Silent fail.

Plus: F1-026 already filed: "Multiple `!!` non-null assertions u initiatePayment putu". Same family.
**Posljedica:** silent sync skip for yachts with malformed agency data. Pre-prod nije problem ako data validation upstream je correct.
**Predloženi fix:** explicit null checks + log specific reason:
```kotlin
val agency = yacht.agency ?: run {
    log.warn("Yacht {} has no agency — skipping sync", yachtId); return
}
val primarySource = agency.primarySource ?: run {
    log.warn("Agency {} has no primary source — skipping sync", agency.id); return
}
val externalSystem = primarySource.externalSystem ?: run {
    log.warn("Primary source for agency {} has no external system — skipping sync", agency.id); return
}
```
**Status:** WAITING-DECISION (trivijalan, group s F1-026)

---

### [F3-039] INFO positive — Admin sync controllers (`MmkSyncController`, `NausysSyncController`) properly @PreAuthorize + @Profile + @PostMapping; F1-042/F1-043/F1-044 fixes applied
**Lokacija:**
- `domains/external/mmk/controller/MmkSyncController.kt:16-19` — `@RestController`, `@Profile("data-sync")`, `@PreAuthorize("hasRole('SYSTEM_ADMIN')")`, `@RequestMapping("/admin/mmk")`
- `domains/external/nausys/controller/NausysSyncController.kt:11-14` — analognog

**Detekcija:** statička
**Opis:** Admin sync triggers su properly gated:
- **Method-level POST** (F1-042 fix): all `/sync`, `/yachts`, `/offer`, `/availability` endpoints — `@PostMapping`, ne `@GetMapping`. Comment u kontroleru (line 27-30 MmkSync, line 19-21 NauSysSync) explicitno spominje F1-042 razlog.
- **Role gating** (`SYSTEM_ADMIN`) prevents non-admin user trigger.
- **Profile gating** (`data-sync`) prevents non-sync-VM (VM2) from exposing these endpoints — only VM3 has data-sync profile.
- **Spring Security `@PreAuthorize`** drives gate; ne ad-hoc auth check.
- **Defense in depth:** triple-layered (network: nginx routing /admin/* + Spring profile + Spring Security role).

Plus: `MmkSyncController.offer2` (line 67) uses rolling LocalDate.now() — F1-043 fix applied. Comment NOT preserved but functionality confirmed.

**Pattern za standardizirati:** F3-035 (DevEquipmentSyncController) should match this exact set of decorators.
**Status:** INFO

---

### [F3-040] INFO positive — `ServiceCallAuditService` responseBody audit toggle off by default + REQUIRES_NEW + ExternalMappingService cached lookups
**Lokacija:**
- `ServiceCallAuditService.kt:17-21` — `responseBodyAudit` flag, off by default per application.yml line 138 (vidi Batch 1 grep)
- `ServiceCallAuditService.kt:24` — `@Transactional(propagation = REQUIRES_NEW)` na audit save (isolated TX)
- `ServiceCallCacheService.kt:51, 64, 106` — REQUIRES_NEW na save methods
- `ExternalMappingService.kt:35, 43` — `@Cacheable("externalMappingCache")` na hot lookup paths

**Detekcija:** statička
**Opis:** Pozitivni patterni:
- **PII opt-in:** response body audit explicit-toggle (off by default). Combined s F2-002 (Envers `_revisions` rast) — by-default off ne kontaminira `service_call` tablicu PII-jem.
- **REQUIRES_NEW for audit/cache:** isolates audit + cache writes od outer TX-a. F3-007 govori da neki state-change calls ne koriste ovo (audit-loss); ovdje ServiceCallAuditService ga koristi konzistentno. **Pattern model**.
- **Cached entity-mapping lookups:** `externalMappingCache` redukuje DB pressure za hot sync paths. Combined s F2-005 (cache profile gating) — workload split-an na VM3 only.

Plus: ServiceCallCacheService implementacija s `Propagation.REQUIRES_NEW` ([line 51](src/main/kotlin/hr/workspace/boat4you/domains/external/service/ServiceCallCacheService.kt#L51)) for cache writes — cache entry committed independently of outer offer-sync TX, tako da partial sync still saves cache marker (gating future sync attempts).

Wait — actually line 77 of ExternalSyncService: `serviceCallCacheService.saveYachtSearch(...)` is called **inside** outer TX (the @Async wrapping doesn't open new TX). If async outer TX rollback, REQUIRES_NEW saves cache anyway — good intent. Then next user search hits cache marker, returns 'no sync needed' — but the partner data wasn't actually saved? Verify outer TX persistence semantics.

Actually `@Async` methods don't inherit outer TX — each `@Async` invocation runs in its own thread without TX. So `nauSysYachtOfferIntegrationServiceAsync.syncOffersForDateRange` may have its own TX (verify). Anyway, REQUIRES_NEW for cache ensures cache marker is durable regardless.

**Status:** INFO

---

### Sažetak Batch 6

- **HIGH (1):** F3-035 (DevEquipmentSyncController triple-layered defense missing — F1-041 produbljen)
- **MED (2):** F3-036 (`Objects.hash` Int-range collision na yacht offer cache), F3-037 (`yachtSyncsInProgress` VM-local mutex)
- **LOW (1):** F3-038 (`!!` chained on entity associations)
- **INFO (2):** F3-039 (admin sync controllers properly gated, F1-042/043/044 fixes applied), F3-040 (audit + cache REQUIRES_NEW pattern + responseBody opt-in)

**Najkritičniji nalaz batch-a:** F3-035 — DevEquipmentSyncController je single-profile-flag-away od full anonymous compromise. Move to `/admin/dev` + add `@PreAuthorize` + convert to `@PostMapping` (~30 minuta total).

**F3-036** je tihi data integrity bug — hash collisions na yacht offer cache mogu stvoriti silent stale-offers situaciju. Switch to MurmurHash/SHA-256.

**Batch 6 i Phase 3 read-pass završen!** Total Phase 3: F3-001..F3-040, 6 batch-eva.

Phase 3 next step: **closure + phase gate** (analogno Phase 2 `0af3478`+`f31f2e8` flow).

---
