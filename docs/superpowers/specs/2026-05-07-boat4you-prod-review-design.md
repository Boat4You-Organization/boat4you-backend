da# Boat4You — Pre-production review design

**Datum:** 2026-05-07
**Repo:** `Boat4You-Organization/boat4you-backend`
**Cilj:** sustavni review backend-a prije produkcijskog deploya — security, memory, performance, bugovi, code quality, exception poruke.

---

## 1. Kontekst

Aplikacija je Kotlin 2.3 + Spring Boot 3.5.10 backend (Java 21) za booking jahta. Domena pokriva rezervacije, fakture, plaćanja (Stripe + Viva), sinkronizaciju s vanjskim API-jima (NauSYS, MMK), GDPR, role (USER / MANAGER / SYSTEM_ADMIN), email i PDF ugovore (openhtmltopdf + PDFBox), procesiranje slika (OpenCV / webp), te scheduled jobove.

Produkcijska arhitektura: 4 VM-a (frontend, backend API, backend scheduler, PostgreSQL 18), s NFS dijeljenim diskom za slike i deploymentom kao `systemd` service. Ovaj review pokriva **samo backend repo**.

## 2. Režim rada

### 2.1 Mod testiranja
- **Statička analiza primarno**, ad-hoc dinamička verifikacija kad statika nije dovoljna.
- Lokalno pokretanje aplikacije (`docker compose up`) samo za smoke testove i točkaste runtime provjere.
- Bez aktivnog pen-testa, fuzzanja, ili load-testa — to je izvan scope-a.

### 2.2 Politika fixanja (hibridna)
| Tip nalaza | Fixa li se odmah? |
|---|---|
| LOW lint/style/dead code, leaked artefakt, default secret, dokumentacija | DA, isti fix-commit |
| MED u izoliranoj funkciji bez business effecta | DA, zaseban commit s `[F<n>-NNN]` u poruci |
| HIGH/CRIT, sve što dira auth, payment, migracije, perzistenciju, scheduled jobove | NE — fix se piše kao prijedlog, čeka eksplicitni OK |
| Sve što zahtijeva arhitekturnu odluku | NE — eskalacija s 2-3 opcije |

### 2.3 Push politika
- Sve commitove radim lokalno na grani `main` (ili namjenskoj review-grani ako se odlučimo prebaciti).
- **Ja ne pushuje na remote bez izričitog odobrenja po push-u.**
- Force push: nikad bez izričitog "force OK" za konkretnu situaciju.

## 3. Scope

### 3.1 Unutar scope-a
- `src/main/kotlin/...` — sav aplikacijski Kotlin kod
- `src/main/resources/db/migration/` — Flyway migracije
- `src/main/resources/templates/` — Thymeleaf email + PDF kontrakt template-i
- `src/main/resources/static/*.openapi.yaml` — OpenAPI specs
- `src/test/...` — postojeći testovi (kao baseline)
- `Dockerfile`, `docker-compose.yml`, `build.gradle.kts`, `gradle/`
- `scripts/` — operativne skripte
- `.env.example`, `.gitignore`, `README.md`, `README_PROD.md`
- systemd service definicija iz `README_PROD.md`
- Repo hygiene: leaked artefakti (`__MACOSX/`, `.DS_Store`, tar.gz), keystore u src

### 3.2 Eksplicitno **NE pokrivamo**
- Frontend repo (na drugom VM-u, drugi repozitorij)
- Infrastruktura ispod aplikacije: OS hardening, firewall, SSH, IAM, network segmentacija, NFS export ACL
- PostgreSQL config (pg_hba, role privileges) — komentari iz `model/init.sql` mogući, ali ne full DB hardening
- DDoS / WAF
- Realan load test (JMeter / k6)
- Long-running profiling preko sati
- External-perspective penetration testing (Burp kroz proxy)
- Compliance certifikacije (PCI-DSS, GDPR audit)
- Frontend ↔ backend ugovor (testiramo samo backend stranu)

## 4. Faze (po riziku za produkciju)

Faze idu od **najveće produkcijske rupe prema najmanjoj**. Ako ostanemo bez vremena na pola, već smo pokrili ono najvažnije za live deploy.

### Faza 1 — Boundary / attack surface
**Što:** sve što izvana može doći do aplikacije.
**Pokriva:**
- `security/config/*` (SecurityConfig, JWT filter, CORS, security headers)
- `security/services/*` (JwtService, AuthService, password hashing, password reset)
- `security/controllers/*`
- Svaki `*Controller.kt` u `domains/**/`
- Payment webhook handleri (Stripe + Viva — verifikacija potpisa, idempotencija)
- File upload endpointi (image upload — content-type, magic bytes, ekstenzija, path)
- Swagger gating (`SWAGGER_ENDPOINT_ENABLED`, prod profil)
- Dev sync endpointi (`/nausys/sync`, `/mmk/sync`)
- `common/ratelimit/`

**Pitanja kodu:** JWT algoritam i validacija (`iss`/`aud`/`exp`/`nbf`), login throttling, IDOR/BOLA, webhook potpis, file upload sigurnost, CORS, security headers, error mapping (vraća li 500 stack-trace klijentu).

**Izlaz:** `docs/superpowers/findings/phase-1-boundary.md`.

### Faza 2 — Data layer
**Što:** perzistencija, migracije, cache.
**Pokriva:**
- Sve `*Repository.kt` (`@Query`, native queryji, JPQL)
- Sve entitete (lazy/eager, kaskade, collection types)
- Flyway migracije u `db/migration/V*__*.sql`
- `common/jpa/`, `common/cache/`
- Svaki `@Transactional`
- Hibernate Envers konfiguracija
- HikariCP konfiguracija

**Pitanja kodu:** N+1, SQL injection (string konkatenacija u native queryjima), FK indexi, kompozitni indexi za hot pathove, migracijski rizici (NOT NULL bez backfill, dugi ALTER), transakcijske granice, dugačka transakcija oko HTTP poziva, Envers cijena u petljama, cache invalidacija/stampedo, connection pool sizing.

**Izlaz:** `docs/superpowers/findings/phase-2-data.md`.

### Faza 3 — Vanjske integracije
**Što:** sve što ide preko mreže prema vanjskim sustavima.
**Pokriva:**
- `domains/external/nausys/*`
- `domains/external/mmk/*`
- Stripe integracija (PaymentService, webhook handler, refunds)
- Viva integracija
- Mail service
- Sve gdje se koristi `RestTemplate` / `RestClient` / `WebClient` / `HttpClient`
- Generated klijenti iz OpenAPI

**Pitanja kodu:** SSRF / URL validacija, HTTP timeouts (connect/read/write), retry policy + idempotencija, secret handling u logovima, error mapping vanjski → naš, TLS (NauSYS po defaultu `http://`), idempotency Stripe/Viva webhook eventova, mail PII u tijelu, rate-limit prema vanjskim API-jima.

**Izlaz:** `docs/superpowers/findings/phase-3-integrations.md`.

### Faza 4 — Scheduled jobovi + heavy native
**Što:** sve što radi van zahtjeva, i sve što koristi native code (JNI).
**Pokriva:**
- Svi `*Job.kt`: NausysSyncJob, MmkSyncJob, ImageDownloadJob, GenerateInvoiceJob, ExchangeRateSyncJob, PaymentPendingNotificationJob, OptionExpiryJob, DeleteExpiredReservationsAndOffersJob
- Svaka `@Scheduled` metoda
- OpenCV korištenja
- openhtmltopdf + PDFBox
- ImageDownload na NFS putanju

**Pitanja kodu:** locking VM2 vs VM3 (kako se zna da samo VM3 pokreće? ShedLock / Quartz cluster?), idempotencija job re-runa, overlapping run protection, OpenCV native memory (`Mat.release()`, `use{}`), PDF gen OOM i XXE, ImageDownload path traversal, NFS timeout, dugotrajne transakcije unutar jobova, batch vs single-message u email petljama.

**Izlaz:** `docs/superpowers/findings/phase-4-jobs-native.md` + assessment safety/idempotency/locking po job-u.

### Faza 5 — Cross-cutting
**Što:** ono što presijeca cijelu aplikaciju.
**Pokriva:**
- `common/errorhandling/`, `common/exceptions/`
- Sve `log.*(...)` linije (grep)
- `application.yml` i svi profili
- `messages/*.properties`
- `common/services/`
- `events/`

**Pitanja kodu:** exception poruka useru (leak interne info?), exception poruka u logu (govori li programeru dovoljno: correlation id, user id, ulazni parametri sažeto), log line s PII / JWT / password reset linkom / kartice, log levele i rotacija, Spring profile defaults koji moraju biti override u prod, i18n missing keys, sinkroni vs asinkroni eventi.

**Izlaz:** `docs/superpowers/findings/phase-5-cross-cutting.md`.

### Faza 6 — Repo hygiene + deploy artefakti
**Što:** sve van src koda što ide u prod ili kompromitira repo.
**Pokriva:**
- `Dockerfile` (root user, multi-stage, image size, health check)
- `docker-compose.yml` (default secreti, exposed portovi)
- `.env`, `.env.example`, `.gitignore`
- `__MACOSX/`, `.DS_Store`, `boat4you-ws-perf-update.tar.gz`
- `scripts/*`
- `README.md` — hardkodirani test useri "123456" — gdje su seedani?
- `README_PROD.md` — deployment proces, secret handling
- systemd service (heap, GC, OOM dump, log rotation, user privileges)
- `build.gradle.kts` — `ignoreFailures = true` na ktlint
- `detekt_config.yml`
- `boat4you_selfsigned.p12` u `src/main/resources/` — privatni ključ u Git-u

**Pitanja:** što sadrži tar.gz, koristi li se selfsigned p12 na prod-u, postoji li seed file za test usere, lint regress (`ignoreFailures`), Dockerfile non-root user, JVM flags u systemd-u (HeapDumpOnOutOfMemoryError, log rotation).

**Izlaz:** `docs/superpowers/findings/phase-6-repo-hygiene.md` + niz trivijalnih fixova.

### Faza 7 — Završni pass i dinamička verifikacija
**Što radim:**
1. `./gradlew compileKotlin detekt test` — sve mora biti zeleno (ili dokumentirano otklon)
2. `docker compose up -d` lokalno — app + DB
3. Smoke-test po listi nalaza koji traže runtime potvrdu (Swagger gating, dev sync zaštita, login throttling, webhook potpis na invalidnom payloadu, itd.)
4. Re-read svih izmijenjenih fileova kroz sve faze (sanity sweep)
5. Generiranje `docs/superpowers/findings/SUMMARY.md` s ukupnim brojem CRIT/HIGH/MED/LOW/INFO, listom popravljenih commit-ova, listom OPEN nalaza s preporukom (popraviti prije deploya / nakon / accept risk), te zaključkom "deploy-spremno / nije zbog X".

**Izlaz:** `docs/superpowers/findings/phase-7-verification.md` + `SUMMARY.md`.

## 5. Workflow unutar faze

Svaka faza ide istim 5-stupanjskim ciklusom:

1. **Inventory** — popis konkretnih fajlova/lokacija u scope-u faze.
2. **Read pass** — čitanje + bilježenje uživo svake sumnje u Findings register.
3. **Triage** — nakon read passa, dodjeljuje se severity i kategorija.
4. **Hibridni fix** — popravlja se samo trivijalno + LOW (po politici 2.2). Sve ostalo ostaje kao OPEN finding.
5. **Phase gate** — gradle compile/test prošao, kratki summary faze, eksplicitni user OK prije nastavka.

**Stop-condition:** ako se nađe CRIT (npr. RCE, plaintext password u logu, otvoren admin endpoint), faza se prekida i odmah eskalira.

## 6. Format nalaza

### 6.1 Lokacija
```
docs/superpowers/specs/                 # ovaj plan + eventualne kasnije promjene
docs/superpowers/findings/
  phase-1-boundary.md
  phase-2-data.md
  phase-3-integrations.md
  phase-4-jobs-native.md
  phase-5-cross-cutting.md
  phase-6-repo-hygiene.md
  phase-7-verification.md
  REGISTER.md                           # master indeks svih nalaza
  SUMMARY.md                            # generira se u Fazi 7
```

`REGISTER.md` je tablica: `ID | Faza | Severity | Kategorija | Status | Lokacija | Naslov`.

### 6.2 Severity
| Razina | Značenje | Reakcija |
|---|---|---|
| CRIT | Ne smije ići na prod (RCE, gubitak podataka, otkriveni secret). | Prekid faze, eskalacija. |
| HIGH | Mora biti popravljeno prije deploya (IDOR/BOLA, kritični N+1). | Bilježi, fix s odobrenjem. |
| MED | Operativni rizik, prvi sprint nakon deploya (rijetki memory leak, nepotpuna exception poruka). | Bilježi, follow-up. |
| LOW | Polish / code health (lint, dead code, unused warning). | Bilježi, batch fix. |
| INFO | Opažanje za tim, nije bug. | Bilježi. |

### 6.3 Format pojedinog nalaza
```markdown
### [F1-007] HIGH security — JWT bez `aud`/`iss` validacije
**Lokacija:** `security/services/JwtService.kt:84`
**Detekcija:** statička, čitanjem koda
**Opis:** ...
**Reprodukcija:** [ako dinamički potvrđeno]
**Posljedica:** ...
**Predloženi fix:** ...
**Riziko-procjena fixa:** ...
**Status:** OPEN | FIXED-COMMIT(<hash>) | WAITING-DECISION | DEFERRED
```

ID format: `F<broj-faze>-<NNN>`.

### 6.4 Commit poruke fixova
```
[F1-003] Remove default 'changeme' for SSL_KEYSTORE_PASSWORD in docker-compose.yml

Replace silent default with required env var. App now refuses to start
without SSL_KEYSTORE_PASSWORD set, matching JWT_SECRET_KEY behavior.

Finding: docs/superpowers/findings/phase-1-boundary.md#f1-003
```

## 7. Tooling

| Alat | Faza | Što gleda |
|---|---|---|
| `./gradlew detekt` | 5, 6, 7 | Kotlin code smells, complexity, ComplexCondition, LongMethod, TooGenericExceptionCaught |
| `./gradlew ktlintCheck` | 6 | Style; trenutno `ignoreFailures=true`, dokumentirat ćemo broj violacija |
| OWASP dependency-check | 1 | CVE u Stripe SDK 31.x, jjwt 0.12.7, BouncyCastle 1.83, OpenCV 4.9, openhtmltopdf, PDFBox |
| `./gradlew dependencies` | 6 | Verzije, transitive duplikati |
| Grep paterna | sve | secret/password/token u logovima; `Runtime.exec`/`ProcessBuilder`; `new File(.*request\.|params\.)`; `${...}` u native query stringovima |
| `./gradlew test` | sve nakon koda | Regresije |
| `docker compose up` | 7 + ad-hoc | Smoke testovi |

Ako neki alat (npr. dependency-check plugin) nije instaliran, prvi se traži OK za dodavanje u `build.gradle.kts`.

## 8. Edge case-ovi

- **Nalaz iz Faze 2 onesposobi fix iz Faze 1** → fix se odgađa, nalaz se označi `blocked by F2-XXX`.
- **Nalaz koji briše podatke a podaci moraju biti migrirani** → eskalacija odmah, ne čeka kraj faze.
- **Prijašnji fix-commit pokvari nešto kasnije** → revert + novi nalaz, bez prikrivanja.
- **Statička sumnja koju ne mogu potvrditi** → nalaz se označi "treba runtime test" ili "treba review s biz contextom", ne lažna sigurnost.
- **Out-of-scope nalaz** (npr. perf u Fazi 3 koji spada u Fazu 4) → zapis u future-fazu register, ne fixa se izvan svoje faze.

## 9. Procjena truda

Gruba procjena, jedna sjednica = 60–90 min fokusiranog rada.

| Faza | Sjednica |
|---|---|
| 1 — Boundary | 1.5–2 |
| 2 — Data layer | 1–1.5 |
| 3 — Integracije | 1–1.5 |
| 4 — Jobs + native | 1 |
| 5 — Cross-cutting | 0.5–1 |
| 6 — Repo hygiene | 0.5 |
| 7 — Verifikacija | 0.5–1 |
| **Ukupno** | **~6–8 sjednica** |

Ako neka faza ozbiljno premaši procjenu, eskalira se prije nego se rasprasi plan.

## 10. Definition of done

Cijeli review je završen kad:
1. Sve faze imaju zatvoren `phase-N-*.md` s findings-ima i statusima.
2. `REGISTER.md` ima zapise za sve nalaze.
3. `SUMMARY.md` ima:
   - broj nalaza po severity
   - listu fix-commit-ova
   - listu OPEN nalaza s preporukom (fix prije deploya / nakon / accept risk)
   - zaključak "deploy-spremno" ili eksplicitnu listu blokera
4. Svi automatski testovi prolaze (`./gradlew test`).
5. Svi smoke testovi iz Faze 7 prolaze.
