# Faza 1 — Boundary / attack surface

**Status:** CLOSED (read pass + batch-1 + batch-2 + closure)
**Datum starta:** 2026-05-07
**Datum closure:** 2026-05-08
**Scope:** security/, *Controller.kt u domains/, payment webhooks, file upload, swagger, dev sync, rate-limit, application yml profile config.

---

## Findings

### [F1-001] MED security — CORS default u SecurityConfiguration je `*`, ali yml override mitiga; rizik ostaje ako env override misconfigured
**Lokacija:** `security/config/SecurityConfiguration.kt:25-27, 70-84`, `application.yml:107-113`
**Detekcija:** statička
**Opis:** `@Value("\${application.cors.allowed-origins:*}")` u kodu defaulta na `*`. Ali `application.yml` postavlja `application.cors.allowed-origins: ${APPLICATION_CORS_ALLOWED_ORIGINS:http://localhost:3000,http://localhost:5173}` — yml override pokriva default kad app starta s yml-om. **Problem ostaje** ako:
- (a) `APPLICATION_CORS_ALLOWED_ORIGINS` env var bude eksplicitno postavljen na `*` (operativna greška),
- (b) yml se ne učita iz nekog razloga (klasifikacijski edge case),
- (c) defaultne vrijednosti (`http://localhost:3000`/`5173`) su **lokalhost-only** — u prod-u **ne radi nijedan stvarni frontend** dok env var ne postavi prave host-ove. Prošlo se neopazice u prošlosti zbog "browser šalje preflight i sve okej dok god backend daje origin".

Komentar u yml-u tvrdi "Wildcard `*` is intentionally NOT supported with allowCredentials=true — Spring will throw at startup" — to vrijedi za **`allowedOrigins`** ali NE za **`allowedOriginPatterns`** koji se ovdje koristi. Spring 6 dopušta `allowedOriginPatterns="*"` UZ credentials. Komentar je netočan.

**Posljedica:** mitigirana standardnim deployom, ali (a) greška u env var setupu otvori sve, (b) yml komentar daje lažni osjećaj sigurnosti.
**Predloženi fix:** zamijeniti default u `@Value` u `""` ili eksplicitno baciti grešku ako resolved vrijednost sadrži samo `*`. Ažurirati yml komentar na točan opis. Dodati startup validation da `prod` profil ima ne-localhost origins.
**Riziko-procjena fixa:** dira security policy → ne mijenjam dok ne odobriš.
**Status:** OPEN

---

### [F1-002] HIGH security — Swagger pathovi permitAll u Spring Security, gating samo preko springdoc property
**Lokacija:** `security/config/SecurityConfiguration.kt:46-53`
**Detekcija:** statička
**Opis:** `/swagger/**`, `/swagger-ui/**`, `/v3/api-docs/**`, `/boat4you_ws_*.openapi.yaml`, `/nausys_v6.openapi.yaml`, `/mmk_api_2_1_3.json` su `permitAll()` u Spring Security configu. Gating na produkciji ide preko `SWAGGER_ENDPOINT_ENABLED` env varijable (vidi .env.example), ali to controlira springdoc bean — ne diže Spring Security pravila. Statične OpenAPI YAML/JSON datoteke u `resources/static/` ostaju javno dohvatljive čak i kad je springdoc UI ugašen.
**Posljedica:** napadač u produkciji može povući kompletni API katalog (backend + NauSys + MMK specifikacije) preko direktnog URL-a, dobivajući mapu svih internih endpointa.
**Predloženi fix:** Spring Security treba zahtijevati autentikaciju za sve `/swagger*`, `/v3/api-docs/**` i `/*.openapi.yaml` rute u prod profilu (npr. preko profile-gated SecurityFilterChain, ili `@ConditionalOnProperty` filter), ne samo na razini springdoc UI-a.
**Riziko-procjena fixa:** dira security policy → ne mijenjam dok ne odobriš.
**Status:** OPEN

---

### [F1-003] HIGH security — Brute-force defence samo per-account, nema rate-limita po IP-u na login/register/reset
**Lokacija:** `security/services/UserAuthService.kt:46-103`, `security/controllers/AuthController.kt:29, 55-71, 78-89`, `security/config/SecurityConfiguration.kt:38-60`, `common/ratelimit/`
**Detekcija:** statička
**Opis:** `/auth/login`, `/auth/register/**`, `/auth/requestPasswordReset`, `/auth/resetPassword`, `/users/invite` su permitAll. Per-account lockout (`MAX_LOGIN_ATTEMPTS_COUNT = 5`, 15-min lock) postoji, ali rate-limit po IP-u/per-endpointu **ne postoji za auth** — jedini rate-limiter u kodu je `PublicReservationRateLimiter`. Implikacije:
- **Account enumeration** preko `/auth/register` (trivijalno generiranje hiljade zahtjeva za probu emailova).
- **Resource exhaustion** preko `/auth/resetPassword` (svaki poziv pokreće bcrypt na novom passwordu — CPU-intenzivno).
- **Slow brute force po accountu**: 5 pokušaja svakih 15 min = 480 pokušaja/dan po accountu, paralelno na 100 accounta = 48k pokušaja/dan.
- **DDoS na requestPasswordReset** (svaki poziv generira email ako user postoji + bcrypt).
**Posljedica:** brute force, account discovery, email-spam DDoS, CPU exhaustion bez ikakvog automatskog throttlinga.
**Predloženi fix:** generički rate-limit filter (per IP, per IP+endpoint) za sve auth endpointe i sve permitAll rute. Bucket4j ili Spring filter s tokenima.
**Riziko-procjena fixa:** novi cross-cutting filter; treba se planirati zajedno s monitoring-om → ne mijenjam dok ne odobriš.
**Status:** OPEN

---

### [F1-004] HIGH security — Slabi password requirementi (min 6 znakova, bez kompleksnosti, bez breach checka)
**Lokacija:** `security/services/UserAuthService.kt:188, 335`
**Detekcija:** statička
**Opis:** Validacija novog password-a: jedino `if (body.newPassword.length < 6) throw …`. Šest znakova je 2026 značajno ispod NIST 800-63B (preporuka 12+) i OWASP ASVS V2.1 (8+ minimum, 12+ za visoke privilegije). Nema provjere kompleksnosti, nema provjere protiv breach databaze (HaveIBeenPwned k-anonymity API), nema black-list-a najčešćih lozinki.
**Posljedica:** korisnici (uključujući SYSTEM_ADMIN i MANAGER) mogu postaviti lozinke "boat4you", "123456", "qwerty". Login lockout (F1-003 commentary) je per-IP nezaštićen, što amplificira rizik.
**Predloženi fix:** povisiti minimum na 12 znakova, dodati provjeru protiv top-1k lista (commons-validator ima dictionary). Razmotriti async HaveIBeenPwned check.
**Riziko-procjena fixa:** dira product behavior + UX (postojeći useri mogu imati slabe passwordove); treba migracijska strategija → ne mijenjam dok ne odobriš.
**Status:** OPEN

---

### [F1-005] HIGH security — JWT bez `iss`/`aud` claim-a, no validation; rolu se ne re-fetcha
**Lokacija:** `security/services/JwtService.kt:32-37, 88-103, 105-111`, `security/JwtAuthenticationFilter.kt:59-91`
**Detekcija:** statička
**Opis:** `JwtParser` se gradi sa `verifyWith(signingKey).build()` — bez `requireIssuer()` ni `requireAudience()`. Generirani tokeni nemaju ni `iss` ni `aud`. Implikacije:
- ako je isti `JWT_SECRET_KEY` ikad bio dijeljen s drugim sustavom (osobito kroz dev/stage/prod), token tog sustava prolazi auth ovdje.
- ako se infrastruktura proširi na više servisa, ovo postaje cross-tenant impersonacija.
Dodatno, `USER_ROLES` je u JWT claimu i koristi se iz tokena (ne ponovno povlači iz DB) — promjena rola usera nema učinka dok token ne istekne.
**Posljedica:** loše izolacija JWT-a; rola privilege escalation moguća do isteka tokena (default-no je `application.security.jwt.expiration` env, treba provjeriti vrijednost u Fazi 6).
**Predloženi fix:** dodati `iss`/`aud` u tokene i u parser; razmotriti ne-stavljanje `USER_ROLES` u claim, ili re-fetchanje rola iz DB-a u filter-u.
**Riziko-procjena fixa:** dira auth policy → ne mijenjam dok ne odobriš.
**Status:** OPEN

---

### [F1-006] MED security — JWT secret format mismatch: `.env.example` instruira `openssl rand -hex 32`, kod radi `Decoders.BASE64.decode`
**Lokacija:** `security/services/JwtService.kt:27-30`, `.env.example:4`
**Detekcija:** statička
**Opis:** `.env.example` kaže `JWT_SECRET_KEY=  # generate: openssl rand -hex 32` (hex output). U `JwtService.signingKey` se zove `Decoders.BASE64.decode(secretKey)`. Hex string nije validan Base64 osim u nekim slučajnim podudaranjima (Base64 alfabet uključuje 0-9 a-f ali interpretira drugačije). Ili će padati pri parse-u, ili će pretvoriti hex u krivi byte array i koristiti tih ~24 bytes umjesto očekivanih 32.
**Posljedica:** ili app ne starta, ili koristi slabiji ključ od očekivanog. Operativna konfuzija pri deployu.
**Predloženi fix:** uskladiti dokumentaciju i kod. Alternativa A: `.env.example` instruira `openssl rand -base64 32`. Alternativa B: kod radi `keyBytes = secretKey.toByteArray(Charsets.UTF_8)` (i tada je secret raw string ≥32 znakova).
**Riziko-procjena fixa:** mali; sad zapisujem kao predloženi fix.
**Status:** OPEN

---

### [F1-007] MED security — `removePrefix("Bearer")` case-sensitive; ne striktno provjerava format zaglavlja
**Lokacija:** `security/JwtAuthenticationFilter.kt:43-44`, `security/services/UserAuthService.kt:155-158, 199-202`
**Detekcija:** statička
**Opis:** `request.getHeader(AUTHORIZATION)?.removePrefix("Bearer")?.trim()` — `removePrefix` je case-sensitive. Klijent koji šalje `bearer eyJ…` (lowercase) ili `BEARER eyJ…` rezultira tokenom oblika `bearer eyJ…` koji JWT parser ne može parsirati. Operativni problem (klijenti dobiju 401 umjesto da se shvate). Također ne provjerava postoji li **točno** prefix "Bearer " (s razmakom) — `removePrefix("Bearer")` skida samo riječ, a tokens bez razmaka prolaze (mada je rijetko da klijenti šalju takav format).
**Posljedica:** false-negative auth zbog case mismatch.
**Predloženi fix:** koristiti `request.getHeader(AUTHORIZATION)?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }?.removeRange(0, 7)?.trim()`.
**Riziko-procjena fixa:** mali, no auth flow je critical path → ne radim u trivijalnim fixovima, čeka odluku.
**Status:** OPEN

---

### [F1-008] MED security — `JwtException` swallowed u filteru; nepostojeći user u JWT-u throw-a `InternalLoginException` van try/catch-a
**Lokacija:** `security/JwtAuthenticationFilter.kt:48-53, 71-75`
**Detekcija:** statička
**Opis:** `try { authorize(...) } catch (e: JwtException) { log.warn(...) }` — uhvaćen je samo `JwtException`. `authorize()` može baciti `InternalLoginException` (line 75 ako user nije nađen u DB-u), koji nije RuntimeException ali je iz Java perspektive checked Exception bez throws klauzule. Kotlin baca svejedno. Filter-chain ovo ne hvata, pa exception propagira do Tomcat-a → 500 IOException ili sl. Klijent vidi 500 umjesto 401.
**Posljedica:** auth fail vidljiv kao 500 → zbunjuje klijente, leak-a stack trace ako je prod profile loše konfiguriran.
**Predloženi fix:** uhvatiti i `InternalLoginException` (i sve provjere) u filteru i mapirati u nečinjenje (anonymous context).
**Riziko-procjena fixa:** mali, ali na critical path.
**Status:** OPEN

---

### [F1-009] MED security — Exception klase bez poruke; logiranje gubi context
**Lokacija:** `security/exceptions/InternalLoginException.kt:6`, `security/exceptions/PasswordException.kt:5`
**Detekcija:** statička
**Opis:** `class InternalLoginException(val type: Type, val email: String) : Exception()` — `Exception()` se zove bez `message`. `e.message` je null, `e.toString()` daje samo class name. Standardni logger.error("login failed", e) ne sadrži ni `type` ni `email`. Programer u logu vidi samo "InternalLoginException at …" bez konteksta.
**Posljedica:** debug login problemi su teški; ne zna se je li to BAD_CREDENTIALS, USER_DOES_NOT_EXIST, MAX_ATTEMPTS_EXCEEDED ili USER_INVITE_NOT_ACCEPTED bez instrumentacije callera. Tvoj zahtjev "exceptioni koji nam ne govore dovoljno" se materijalizira ovdje.
**Predloženi fix:** `super("type=$type, email=$email")` u konstruktoru. Isto za PasswordException. Eventualno extra polja za structured logging.
**Riziko-procjena fixa:** trivijalno → mogu fixati u trivijalnom batchu.
**Status:** WAITING-DECISION (želiš li trivijalni fix sad, ili u Fazi 5 cross-cutting batchu)

---

### [F1-010] MED security — Email enumeration vector kroz različite InternalLoginException tipove
**Lokacija:** `security/services/UserAuthService.kt:74-82`, `common/errorhandling/ApiErrorHandler.kt` (treba verificirati u Fazi 5)
**Detekcija:** statička, treba runtime potvrdu
**Opis:** `login()` baca `BAD_CREDENTIALS` za nepoznat email **i** za pogrešan password (dobro), ali zatim baca **različite** tipove za:
- `BAD_CREDENTIALS` ako registrationStatus != REGISTERED (line 76-77)
- `USER_INVITE_NOT_ACCEPTED` ako invite nije prihvaćen (line 80-82)
- `MAX_ATTEMPTS_EXCEEDED` ako user lockout (line 84-90)
Ako error handler mapira ove tipove u različite HTTP statuse ili poruke, napadač razlikuje "user postoji ali nije aktiviran" od "user ne postoji". Treba verificirati ApiErrorHandler u Fazi 5.
**Posljedica:** account enumeration, ako se manifestira u response-u.
**Predloženi fix:** sve InternalLoginException tipove mapirati u **isti** vanjski response (HTTP 401, ista generic poruka). Logirati razlog interno.
**Riziko-procjena fixa:** dira error mapping → ne mijenjam dok ne odobriš.
**Status:** OPEN — verificirati u Fazi 5 mapiranje
**Blocked by:** None (analysis), but fix depends on F5 review

---

### [F1-011] MED bug — Login lockout NPE risk: `lastUnsuccessfulLogin!!` non-null assert kad može biti null
**Lokacija:** `security/services/UserAuthService.kt:84-90`
**Detekcija:** statička
**Opis:** `if (dbUser.loginAttempts >= MAX_LOGIN_ATTEMPTS_COUNT && dbUser.lastUnsuccessfulLogin!!.plusSeconds(...).isAfter(Instant.now()))` — koristi `!!`. Ako je `loginAttempts >= 5` ali `lastUnsuccessfulLogin` je null (npr. partial DB write iz prethodne verzije, ručna intervencija u DB-u), ovo throw-a NullPointerException. Filter ovo ne hvata kao InternalLoginException → 500 odgovor.
**Posljedica:** rare-edge 500 na login. Slabo eksploatabilno, ali iznenađenje za userske support tickete.
**Predloženi fix:** `dbUser.lastUnsuccessfulLogin?.plusSeconds(...)?.isAfter(Instant.now()) == true`.
**Riziko-procjena fixa:** mali; mogu u trivijalni batch.
**Status:** WAITING-DECISION

---

### [F1-012] LOW bug — `findAllValidTokenByUserId` naziv obmanjuje + `findActiveByValue` dead code
**Lokacija:** `security/jpa/TokenRepository.kt:9-15, 19-25`
**Detekcija:** statička, grep verifikacija
**Opis:** Query: `WHERE t.user.id = :id AND (t.isExpired = false OR t.isRevoked = false)`. Naziv metode kaže "valid token" — ali query vraća tokene gdje **je bar jedna** zastavica false. Behaviour je correct za revoke flow (jedini caller), ali naziv obmanjuje. Verifikacija (`grep findActiveByValue`): **`findActiveByValue` je dead code** — nigdje se ne poziva u repu. Demoting na LOW.
**Posljedica:** code clarity / future-bug rizik ako se `findActiveByValue` ikad iskoristi za auth (semantika OR ne validira pravo "active").
**Predloženi fix:** obrisati `findActiveByValue`, preimenovati `findAllValidTokenByUserId` u `findAllNotFullyRevokedByUserId`, ili promijeniti query u AND.
**Riziko-procjena fixa:** trivijalno → mogu u trivijalni batch.
**Status:** WAITING-DECISION (trivijalni batch)

---

### [F1-013] MED bug — `SecurityUtils.checkAccessForAdminOrSelf` non-null cast `as UserDomainEntity` može throw-ati ClassCastException
**Lokacija:** `security/SecurityUtils.kt:12, 27`
**Detekcija:** statička
**Opis:** `val user = SecurityContextHolder.getContext().authentication.principal as UserDomainEntity` — direktni cast bez provjere. Anonymous context ima principal kao `String("anonymousUser")` (vidi `JwtAuthenticationFilter.kt:46`). Ako se ova helper funkcija pozove iz endpointa koji je permitAll, anonimni user koji je dohvatio endpoint i koji onda zove u kodu `checkAccessForAdminOrSelf` rezultira ClassCastException → 500.
**Posljedica:** 500 odgovor umjesto 403 Forbidden u edge slučajevima. `checkAccessForAdminOrManagerOrSelf` ima isti problem.
**Predloženi fix:** isti pattern kao `getAuthenticatedUserId()` (line 41-51) koji već handlea anonymous: `if (principal is String) throw AccessDeniedException("anonymous")`.
**Riziko-procjena fixa:** mali, ali dira auth helpers.
**Status:** OPEN

---

### [F1-014] LOW perf — Token table grows per login; `findByValue` na VARCHAR(1024) UNIQUE indeksu
**Lokacija:** `security/jpa/TokenEntity.kt:15`, `security/JwtAuthenticationFilter.kt:77`, `security/services/UserAuthService.kt:120-121, 142-143`
**Detekcija:** statička
**Opis:** Svaki login + svaki refresh + svaki issueTokenAtRegistration sprema 2 retka u `tokens` tablicu (access + refresh). Nema cleanup mehanizma osim `revokeAllUserTokens` (poziva se samo iz updatePassword). `findByValue(token)` se zove na svakom autentificiranom request-u → indexed lookup po VARCHAR(1024) UNIQUE indeksu. Tablica raste linearno s prometom, indeks raste s njom. Bez TTL cleanup joba, expired tokeni ostaju zauvijek.
**Posljedica:** dugoročno performance i storage degradation. Mjesec-dva production prometa može značiti milijune redaka.
**Predloženi fix:** scheduled job koji briše tokene gdje `expiresAt < now() - retention_period` (npr. 30 dana). Ili promjena dizajna da koristi JTI claim + Redis TTL umjesto DB store.
**Riziko-procjena fixa:** dizajn promjena; ostaje OPEN za Fazu 4 (jobovi) i Fazu 2 (data layer indexing analiza).
**Status:** OPEN — defer to Phase 2/4

---

### [F1-015] LOW security — BCrypt default cost (10 rounds), no explicit configuration
**Lokacija:** `security/services/PasswordService.kt:9`
**Detekcija:** statička
**Opis:** `BCryptPasswordEncoder()` uses default strength 10. 2026 OWASP / NIST preporučuju 12 (cca 200ms na modernom CPU-u). 10 rounds je 50-80ms — dobro za UX, manje dobro za otpornost na offline brute force ako baza procuri.
**Posljedica:** u slučaju DB leak-a, password hashes se brže crackaju.
**Predloženi fix:** `BCryptPasswordEncoder(12)`. Trebamo benchmark u prod hardware-u (potencijalno login latency može porasti za 100ms).
**Riziko-procjena fixa:** mali, ali utječe na login UX → želim tvoju potvrdu.
**Status:** WAITING-DECISION

---

### [F1-016] LOW security — Hardkodirani server hostovi u OpenAPI configu
**Lokacija:** `security/config/OpenApiSecurityConfig.kt:23-24`
**Detekcija:** statička
**Opis:** `Server().description("LOCAL").url("https://localhost:8443")` i `Server().description("AWS DEV").url("https://boat4you-dev.workspace.hr/api")` — hardkodirano u kodu. Production swagger UI (ako se ikad sluči da je uključen u prod) nudi "AWS DEV" kao server target. Korisnik testirajući API kroz swagger zapravo gađa dev environment.
**Posljedica:** zbunjivanje, propagacija test podataka, nehotično piše u dev iz prod swagger UI-a (više teorijski).
**Predloženi fix:** servere konfigurirati preko `application.yml` (springdoc.servers.[*]), ili profile-gated: `@ConditionalOnProperty(prefix="springdoc")`. Linkano s F1-002.
**Riziko-procjena fixa:** trivijalno; mogu u trivijalni batch ako odobriš.
**Status:** WAITING-DECISION

---

### [F1-017] LOW security — Generic OpenAPI title i description placeholder
**Lokacija:** `security/config/OpenApiSecurityConfig.kt:33-34`
**Detekcija:** statička
**Opis:** `.title("My REST API").description("Some custom description of API.")` — placeholder copy-paste iz Spring tutorial-a. Profesionalna javna API dokumentacija (čak i samo interna) trebala bi imati pravo ime "Boat4You Web Service API" + smislen opis.
**Posljedica:** loš dojam ako ovo procuri u svaku verziju OpenAPI specsova koji se exportaju.
**Predloženi fix:** popraviti title i description.
**Riziko-procjena fixa:** trivijalno; predlažem batch.
**Status:** WAITING-DECISION

---

### [F1-018] LOW exception clarity — `// TODO Catch all exceptions...` u refreshToken metodi
**Lokacija:** `security/services/UserAuthService.kt:208-209`
**Detekcija:** statička
**Opis:** Komentar u kodu: `// TODO Catch all exceptions possibly thrown by this method and handle them`. Originalni autor svjestan je da `jwtService.extractEmail(token)` (line 209) može throw-ati `JwtException` (invalid token), `IllegalArgumentException`, itd. Bez try/catch, ti se propagiraju kao 500.
**Posljedica:** klijent dobiva 500 (ne 401) na refreshu s pokvarenim/isteklim refresh tokenom.
**Predloženi fix:** zaobilazni try/catch oko `extractEmail` (i ostale poziva na liniji 209-227) → vrati InternalLoginException(BAD_CREDENTIALS).
**Riziko-procjena fixa:** mali, ali dira auth flow.
**Status:** OPEN

---

---

### [F1-019] CRIT bug — Stripe webhook nije idempotent; double processing pri retry-ju
**Lokacija:** `domains/reservation/controllers/StripeWebhookController.kt:31-46`, `domains/reservation/service/StripePaymentService.kt:166-197`
**Detekcija:** statička
**Opis:** `handleWebhookEvent(event)` pronađe payment phases po `stripeSessionId`, postavi `paidOn = Instant.now()` i, ako je novo plaćanje, zove `promoteReservationToBooking` koji **(a)** confirma rezervaciju kod vanjskog providera (NauSYS/MMK), **(b)** šalje confirmation email. **Nigdje se ne provjerava je li ovaj `event.id` već procesiran** — Stripe redovito retry-a webhook na ne-2xx, ali i bez retry-a, mreža/timeout/rebalance može dovesti do toga da Stripe pošalje isti event dvaput. Bez dedupe-a, jedna rezervacija se promovira dvaput → može rezultirati duplom NauSYS/MMK rezervacijom + dvostrukim emailom korisniku.
**Posljedica:** dvostruki external booking (financijska/operativna šteta), dvostruki email, dvostruki status updates. Sve se manifestira tek pod prod-loadom + Stripe event retry-em — neće se uhvatiti u dev testovima.
**Predloženi fix:** držati `processed_stripe_event_ids` tablicu (event.id UNIQUE) — pri ulasku u handler prvo umetnuti retku (constraint violation = drop event), zatim handlea-ti event u istoj transakciji. Alternativa: store event.id u Reservation entity-u s constraint-om. Stripe garantira `event.id` jedinstvenost.
**Riziko-procjena fixa:** dira payment flow + treba migraciju → ne mijenjam dok ne odobriš. Ovo je **kandidat za blocker prije produkcije**.
**Status:** OPEN — eskalacija

---

### [F1-020] HIGH security — File upload validira samo `Content-Type` HTTP header, ne magic bytes (PDF i image kroz MultipartFile)
**Lokacija:** `common/services/FileSystemService.kt:138-148, 150-158, 36-56`
**Detekcija:** statička
**Opis:** `validateImageFile(MultipartFile)` provjerava samo `file.contentType` (vrijednost koju klijent može lažirati). Path: napadač uploada `.exe` s `Content-Type: image/jpeg` → prolazi validaciju → ImageUtils.convertToWebP pokušava parse, FAILA, ali tek nakon prihvaćanja. Za **PDF** (`validatePdfFile`, line 150-158) provjera je samo `contentType == "application/pdf"`. Nema poziva na magic-byte provjeru. PDF se sprema kao `<UUID>.pdf` i kasnije se može pristupiti / poslati kao attachment u confirmation email (charter agreement flow).
**Posljedica:** napadač uploada bilo koji file kao `.pdf`. Ako se taj file kasnije servira drugim korisnicima ili emailira, napadač distribuira sadržaj pod legitimnim PDF imenom. Magic-byte provjera za images **postoji** u `validateImageBytes` (line 171-199) ali samo se zove iz drugog overload-a (`saveImage(ByteArray)`).
**Predloženi fix:** primijeniti istu magic-byte provjeru iz `validateImageBytes` i u `validateImageFile`. Za PDF provjeriti `%PDF-` magic header.
**Riziko-procjena fixa:** mali, izoliran u FileSystemService. Mogu predlagati fix uz tvoj OK.
**Status:** OPEN

---

### [F1-021] HIGH security — Path traversal rizik u `FileSystemService.saveFile/saveImage` ako su `subpath` ili `customFilename` od user inputa
**Lokacija:** `common/services/FileSystemService.kt:94-114, 116-136, 201-209, 211-228`
**Detekcija:** statička, treba grep callera
**Opis:** `Paths.get(uploadDir, subpath).resolve(customFilename)` — ako `subpath` sadrži `../../etc/` ili je apsolutan path, `Paths.resolve` može pisati izvan `uploadDir`. Isti rizik za `deleteFile(filename)` (`Paths.get(uploadDir, filename)`) i `getResourcePath(filename)` (read kroz controller-e koji serviraju images). Treba verificirati pozive: ako se `subpath` ili `filename` izvode iz user inputa (request param, path variable, body), to je **path traversal arbitrary file write/read/delete**. Ako su tvrdo kodirani konstanti u callerima (npr. `subpath = "yacht-images"`), riziko je manji.
**Posljedica:** ovisno o useru: read /etc/passwd, write na sistemske putanje, delete arbitrary fileova. Veliki ulog za "ako".
**Predloženi fix:** kanonikalizacija u FileSystemService: `val resolved = Paths.get(uploadDir).resolve(subpath).resolve(customFilename).normalize().toAbsolutePath(); require(resolved.startsWith(Paths.get(uploadDir).toAbsolutePath()))`. Bilo bi dobro centralizirati u jednoj helper funkciji.
**Riziko-procjena fixa:** mali, defenzivan. Ne mijenjam dok ne odobriš + ne grep-am sve callere.
**Status:** OPEN — ovisi o caller analizi (controlleri u Fazi 1 nastavak)

---

### [F1-022] HIGH security — `PublicReservationRateLimiter` slijepo vjeruje X-Forwarded-For / X-Real-IP
**Lokacija:** `common/ratelimit/PublicReservationRateLimiter.kt:96-104`
**Detekcija:** statička
**Opis:** `clientIp(request)` čita `X-Forwarded-For` i `X-Real-IP` bez provjere da je request došao od **trusted reverse proxy-a**. Ako pred aplikacijom NEMA nginxa/CDN-a, ili je nginx loše konfiguriran (ne strip-a XFF), napadač pošalje `X-Forwarded-For: <random IP>` u svakom requestu i bypass-a per-IP rate limit trivijalno (svaki request ima drugačiji "IP" pa svaki dobiva svoj 5/min budget). README_PROD ne spominje nginx ni gateway pred VM2 — dakle ovo je realan rizik. Komentar autora "NOT a replacement for gateway-level rate limiting" priznaje ograničenje, ali ne spominje XFF-spoof.
**Posljedica:** rate limit potpuno bypassiran. `/public/reservations` ostaje DoS-vector, što je upravo motiv ovog filtra prema komentaru iz koda.
**Predloženi fix:** Spring Boot ima `ForwardedHeaderFilter`/`server.forward-headers-strategy=NATIVE`. To prepušta servlet container-u da pravilno parsira XFF (samo iz known proxies). Ovdje, na razini filter-a, imati config `application.rate-limit.trust-forwarded=true|false` i samo u `true` koristiti XFF; inače `request.remoteAddr` (TCP peer).
**Riziko-procjena fixa:** mali, izoliran. Ne mijenjam bez odobrenja.
**Status:** OPEN — verificirati u Fazi 6 systemd / nginx setup

---

### [F1-023] MED bug — `PublicReservationRateLimiter` bucket map ne self-prune; memory leak preko vremena
**Lokacija:** `common/ratelimit/PublicReservationRateLimiter.kt:51, 78-85`
**Detekcija:** statička
**Opis:** Komentar (line 27): "Map entries self-prune when they go stale (no request in > 2× window)" — ali u kodu **nema pruning logike**. `buckets.compute(ip)` samo update-a postojeću ili kreira novu — nikad ne briše. Svaki novi IP doda entry; entry se nikad ne briše. Pod rastvorenim DDoS-om s rotirajućim IP-jevima (vidi F1-022), map raste neograničeno → JVM heap polako curi. Komentar je proma**š**en.
**Posljedica:** dugoročni memory leak; nakon nekoliko mjeseci prometa heap može početi rasti, povećavajući GC pritisak.
**Predloženi fix:** scheduled cleanup: `@Scheduled(fixedRate = 60_000)` koji uklanja bucketove gdje `lastRefill < now() - 2 * windowSeconds`. Ili koristiti Caffeine cache s expireAfterAccess.
**Riziko-procjena fixa:** mali, lokaliziran.
**Status:** OPEN

---

### [F1-024] HIGH bug — `StripePaymentService.handleWebhookEvent` višestruki non-null assertion na user-controlled metadata; NPE → 500 → Stripe retry → loop
**Lokacija:** `domains/reservation/service/StripePaymentService.kt:166-197`
**Detekcija:** statička
**Opis:** Niz non-null assertion na webhook event payload-u:
- line 172: `session!!.id` — komentar autora "Session object always exists on a successful payment?" sa **upitnikom**.
- line 176: `session.metadata["reservationId"]!!.toLong()` — ako fali ili nije broj, NPE/NumberFormatException.
- line 178: `session.metadata["paymentPhaseId"]?.toLong()` — siguran (`?.`), OK.
- line 185: `dbPaymentPhases.first()` — NoSuchElementException ako lista prazna.
- line 187: `dbPaymentPhases.find { it.id == paymentPhaseId }!!` — NPE ako fazi nema u bazi.

Ako bilo što od ovog throw-a, controller vraća 500. Stripe (`Stripe.setMaxNetworkRetries(2)` je za odlazne pozive — webhook retry je on Stripe side i radi samo na ne-2xx) **vidi 500 i retry-a webhook prema svom planu (eksponencijalno preko više dana)**. Bez idempotency-a (F1-019), kad konačno prođe može doći do dvostrukog plaćanja efekta. Prije toga, alarm/log spam.
**Posljedica:** ne-2xx webhook → Stripe retry storm → log noise + (kombinirano s F1-019) potencijalno duplicate state changes.
**Predloženi fix:** validirati svaki metadata field uz jasan log (ne NPE), vratiti 200 (acknowledged + ignored) za neočekivane formate. Throwati custom WebhookValidationException s kontekstom za logging.
**Riziko-procjena fixa:** dira payment flow → ne mijenjam dok ne odobriš.
**Status:** OPEN

---

### [F1-025] MED bug — `StripePaymentService.initiatePayment` idempotency optional; double-click → dvije sesije
**Lokacija:** `domains/reservation/service/StripePaymentService.kt:149-158`
**Detekcija:** statička
**Opis:** Komentar kaže "double click returns the same Session instead of charging twice" ali **samo ako frontend pošalje key**. `idempotencyKey: String? = null` u potpisu — ako je null/blank, fall-back je `Session.create(params)` bez key-a. Stari klijenti / direktni curl pozivi s validnim auth bypassiraju idempotency. Backend može sam generirati key (deterministic od reservationId + paymentPhaseId + minute timestamp) ako frontend ne pošalje.
**Posljedica:** rijedak ali realan: dvostruka Stripe sesija → dvije payment phases s istim sessionId... zapravo različitim. Pri webhook-u jedan event update-a phases iz session A, drugi iz session B. Money flow OK (ne dvostruko se naplaćuje), ali payment phase metadata izgleda inconsistent.
**Predloženi fix:** backend-generated default idempotency key (`"$reservationId-$paymentPhaseId-${LocalDateTime.now().truncatedTo(MINUTES)}"`) ako frontend ne pošalje.
**Riziko-procjena fixa:** mali.
**Status:** OPEN

---

### [F1-026] MED bug — Više non-null assertion-a u initiatePayment putu (deadline!!, calculatedTotalPrice!!, reservationFlow.id!!)
**Lokacija:** `domains/reservation/service/StripePaymentService.kt:87, 92, 122, 127`
**Detekcija:** statička
**Opis:** `reservationFlow.calculatedTotalPrice!!` (line 87), `unpaidPhases.minByOrNull { it.deadline!! }` (line 92), `reservationFlow.id!!.toString()` (line 123, 127). Svi **u user-triggered flow-u** (POST /reservations/.../initiate-payment). Bilo koji od njih null = NPE = 500 prema useru. Posebno `deadline!!` — ako je legacy phase upisan bez deadline-a, NPE.
**Posljedica:** edge-case 500 na payment initiate, customer ne može platiti.
**Predloženi fix:** zamijeniti non-null assertion s elvis-throw uz domain exception (`?: throw ParameterValidationException(...)` s opisom "missing X").
**Riziko-procjena fixa:** mali.
**Status:** OPEN

---

### [F1-027] LOW bug — Generic `RuntimeException("Stripe is not enabled in this environment")` propada do klijenta
**Lokacija:** `domains/reservation/service/StripePaymentService.kt:243-247`
**Detekcija:** statička
**Opis:** `checkIfStripeIsEnabled` baca `RuntimeException("Stripe is not enabled in this environment")` ako `application.stripe.enabled=false`. Bez specifičnog handlera u ApiErrorHandler-u (Faza 5), ovo postaje 500. Klijent vidi internu poruku "Stripe is not enabled in this environment" — leak konfiguracijskog stanja. Trebao bi biti domain exception (npr. `PaymentNotEnabledException`) sa 503 Service Unavailable + generic poruka.
**Posljedica:** info leak + neugledan UX.
**Predloženi fix:** kreirati `PaymentNotEnabledException` (domain), mapirati na 503 u Fazi 5.
**Riziko-procjena fixa:** mali, Faza 5 dependency.
**Status:** OPEN

---

### [F1-028] MED security — `promoteReservationToBooking` može se izvršiti ponovno za istu rezervaciju
**Lokacija:** `domains/reservation/service/StripePaymentService.kt:191-193, 234-239`
**Detekcija:** statička
**Opis:** Komentar (line 190): "Reservation is created for the first time" — ali u kodu nema provjere da rezervacija već nije promovirana. `promoteReservationToBooking` zove `confirmExternalReservation` (NauSYS/MMK external API) i `confirmReservation` (DB state change). Ako webhook handle-a se dvaput (F1-019) ili bilo koje druga polja-ka šaltera prelazi u stanje gdje `paymentPhaseId == null`, izvršit će se dva confirmExternal calla na isti booking.
**Posljedica:** dvostruki vanjski booking, mogući financial impact, hard-to-undo state na vendor-strani.
**Predloženi fix:** dodati provjeru `dbReservation.confirmedAt == null` (ili sl. polje) prije poziva. Idemnpotency na vanjske confirmExternalReservation pozive (Faza 3 task).
**Riziko-procjena fixa:** dira reservation lifecycle → ne mijenjam.
**Status:** OPEN
**Blocked by:** F1-019 fix

---

### [F1-029] LOW bug — `setName("Boat Booking — Reservation #$paymentReference")` — paymentReference može sadržavati admin-controlled string
**Lokacija:** `domains/reservation/service/StripePaymentService.kt:109, 121, 143`
**Detekcija:** statička
**Opis:** `paymentReference = dbReservation.reservationNumber ?: reservationId.toString()` — `reservationNumber` se postavlja iz reservation flow-a (manageraka logika). Vrijednost se interpolira u Stripe metadata, description i product name. Ako manager omaškom unese kontrolne znakove ili čudne string-ove, oni završe u customer's bank statement (description) i Stripe Dashboard. Ne security exploit, ali UX/branding hygiene.
**Posljedica:** nestandardni stringovi u Stripe Dashboard / bank statement.
**Predloženi fix:** sanitize na alfanumerik + `/` + `-` (regex `[^A-Za-z0-9/-]`).
**Riziko-procjena fixa:** trivijalno.
**Status:** WAITING-DECISION (trivijalni batch)

---

### [F1-030] MED security — `StripeWebhookController` čita cijelo request tijelo; nema explicit max-size limita
**Lokacija:** `domains/reservation/controllers/StripeWebhookController.kt:32`
**Detekcija:** statička
**Opis:** `request.reader.readText()` čita cijeli body u memory. Spring/Tomcat ima default max post size (~2MB), ali nije eksplicitno postavljen za webhook endpoint. Napadač sa validnim Stripe-Signature-om bi trebao biti rijetko mogućnost (jer webhookSecret), ali bilo tko može poslati `POST /webhooks/stripe` s ogromnim body-jem koji **prije signature provjere** uđe u memory. Dva-MB limit nije dovoljan attack pod paralelizacijom.
**Posljedica:** ograničen DoS preko memory pressure.
**Predloženi fix:** limit `application.servlet.multipart.max-request-size` ili custom filter koji baci 413 na > N bytes prije body read-a. Razmotriti i da Stripe-Signature header validacija bude prva, prije puno reading-a.
**Riziko-procjena fixa:** mali, dira boundary handler.
**Status:** OPEN

---

### [F1-031] LOW security — Webhook catch-all `catch (e: Exception)` zatamnjuje stvarni razlog
**Lokacija:** `domains/reservation/controllers/StripeWebhookController.kt:38-41`
**Detekcija:** statička
**Opis:** `catch (e: Exception)` lovi sve, vraća "Invalid signature" porukom. Ali u kodu prije catch-a se događa: `Webhook.constructEvent` može throw-ati `SignatureVerificationException`, `JsonSyntaxException`, ili druge. Vraćanjem "Invalid signature" za bilo koju grešku, log nam ne kaže što se stvarno dogodilo izvan poruke. Klijent (Stripe) vidi 400 — što je OK — ali support debugiranje je teško.
**Posljedica:** debug težak (tvoj zahtjev: exceptioni koji ne govore dovoljno).
**Predloženi fix:** uhvatiti specifično `SignatureVerificationException` s tom porukom; ostalo `Exception` kao "Webhook processing error: ${e.javaClass.simpleName}".
**Riziko-procjena fixa:** mali.
**Status:** WAITING-DECISION

---

### [F1-032] LOW perf — `validateImageBytes` koristi hardkodirani 15MB limit umjesto `maxFileSizeMb` configa
**Lokacija:** `common/services/FileSystemService.kt:171-180, 26-27`
**Detekcija:** statička
**Opis:** `validateImageBytes` ima hardkodiran `15 * 1024 * 1024` limit, dok `validateFile` koristi `maxFileSizeMb` config (default 10MB). Inkonzistentno: image-from-multipart limitirano 10MB, image-from-bytearray (tipično iz internal poziva) 15MB.
**Posljedica:** zbunjujuće default ponašanje, mogući OOM u rare case.
**Predloženi fix:** koristiti istu config vrijednost.
**Riziko-procjena fixa:** trivijalno.
**Status:** WAITING-DECISION

---

### [F1-033] MED security — Bez virus / malware skena na file uploadima
**Lokacija:** `common/services/FileSystemService.kt` (cijeli)
**Detekcija:** statička
**Opis:** Image i PDF uploadi se spreme kao-jesu (osim WebP konverzije za images). Nema integracije s ClamAV / VirusTotal API / sl. PDF charter agreement-i se kasnije šalju email-om kao attachmenti — ako napadač uploada zaraženi PDF, on ide u inbox-e legitimnih klijenata.
**Posljedica:** distribucija malicioznih sadržaja kroz legitiman channel.
**Predloženi fix:** integrirati ClamAV daemon (`clamd`) — sprema u temp, scan, reject ako zaraženo. Production-ready libs: `org.springframework.cloud:spring-cloud-stream-clamav` ili custom client.
**Riziko-procjena fixa:** novi infra dependency, deployment plan, treba dogovor.
**Status:** OPEN — eskalacija (deployment decision)

---

### [F1-034] MED security — `getResourceFromPath`, `deleteFile`, `getResourcePath` bez kanonikalizacije; rizik ovisi o caller-ima
**Lokacija:** `common/services/FileSystemService.kt:201-228`
**Detekcija:** statička
**Opis:** `deleteFile(filename)` koristi `Paths.get(uploadDir, filename)` direktno. `getResourceFromPath(path: Path)` ne provjerava staza unutar dozvoljenog korijena. `getResourcePath(filename)` isto. Iste implikacije kao F1-021 — ako bilo koji caller prosljeđuje user-controlled string, rizik se materijalizira. Ovo je **defense-in-depth**: čak i ako su trenutno svi pozivi safe, defenzivna kanonikalizacija unutar metode čuva od buduće regress.
**Posljedica:** ovisi o callerima. Treba grep poziva u svim controller-ima.
**Predloženi fix:** kanonikalizacija unutar svakog public metoda kao u F1-021.
**Riziko-procjena fixa:** mali.
**Status:** OPEN

---

## Sažetak ovog batcha (F1-019 do F1-034)

- **CRIT (1):** F1-019 (Stripe webhook idempotency) — kandidat za blocker prije produkcije
- **HIGH (4):** F1-020, F1-021, F1-022, F1-024
- **MED (7):** F1-023, F1-025, F1-026, F1-028, F1-030, F1-033, F1-034
- **LOW (3):** F1-027, F1-029, F1-031, F1-032

---

### [F1-035] CRIT security — Self-signed SSL keystore (`boat4you_selfsigned.p12`) u classpath-u i Docker image-u
**Lokacija:** `application.yml:8` (`key-store: classpath:boat4you_selfsigned.p12`), `Dockerfile:26` (`COPY --from=build /app/src/main/resources/boat4you_selfsigned.p12 keystore.p12`)
**Detekcija:** statička
**Opis:** Privatni ključ za HTTPS sjedi u `src/main/resources/boat4you_selfsigned.p12`, ide u JAR (kroz classpath), kopira se u Docker image. Tko god dohvati JAR / image / git repo — ima privatni ključ. P12 format je s lozinkom, ali default password u docker-compose je `${SSL_KEYSTORE_PASSWORD:-changeme}` što sugerira da je trenutna lozinka možda jednostavna. **Self-signed cert** dodatno: nema validan CA chain, ne radi za production HTTPS prema vanjskim klijentima (browseri ga reject-aju). Ako prod **stvarno koristi ovaj keystore**, HTTPS je teoretski (browser warning + key compromise). Ako prod koristi drugi keystore (npr. Let's Encrypt), `application.yml` config mora biti override-an env-om — ali to nije eksplicitno.
**Posljedica:** ako je u upotrebi: zero TLS protection (ključ je javan u Git-u). Ako nije: dead artefakt s privatnim ključem koji curi u svaki build.
**Predloženi fix:** **odmah maknuti `boat4you_selfsigned.p12` iz repa** (i iz git history-a — `git filter-repo` ili BFG Repo-Cleaner). Promijeniti `application.yml` da `key-store` dolazi iz env-a / external mount, bez classpath fallbacka. U prod-u koristiti pravi cert (Let's Encrypt + nginx ili ALB).
**Riziko-procjena fixa:** ako se koristi u prod-u: prvo treba migracijski plan (cert na VM-ovima, restart, fallback). Ne diram, **eskalacija**.
**Status:** OPEN — eskalacija (potencijalni blocker prije produkcije)

---

### [F1-036] HIGH security — Default credentials u `application*.yml` (DB postgres:postgres u root yml; boat4you_app:boat4you_app u prod yml)
**Lokacija:** `application.yml:29-30` (root), `application-prod.yml:48-49` (prod)
**Detekcija:** statička
**Opis:**
- root yml: `username: ${DB_USER:postgres}`, `password: ${DB_PASSWORD:postgres}` — defaultni postgres superuser i postgres password.
- prod yml: `username: ${DB_USER:boat4you_app}`, `password: ${DB_PASSWORD:boat4you_app}` — username == password, oba su default. README mentions `boat4you_app` user u env example bez password.
Ako prod env file zaboravi postaviti `DB_PASSWORD` (i `boat4you_app` user u Postgres ima password "boat4you_app"), aplikacija starta i koristi tu kombinaciju. Worse: ako Postgres VM4 nema strogu autentikaciju (pg_hba.conf), trivijalna brute-force kombinacija.
**Posljedica:** moguća prijava na DB s defaultnim credentialima. Operativni rizik vrlo realan.
**Predloženi fix:** ukloniti defaultne vrijednosti za `DB_USER` i `DB_PASSWORD` (`${DB_PASSWORD:?DB_PASSWORD required}`). App refuses startup ako env nije postavljen — fail-loud umjesto fail-silent-with-bad-creds. Dosljedno s STRIPE_SECRET_KEY pristupom (linija 161-162 root yml).
**Riziko-procjena fixa:** mali, ali config; ne mijenjam dok ne odobriš.
**Status:** OPEN

---

### [F1-037] HIGH security — `NAUSYS_URL` default `http://ws.nausys.com` (HTTP, ne HTTPS)
**Lokacija:** `application-prod.yml:13`, `application-dev.yml:18`, `.env.example:28`
**Detekcija:** statička
**Opis:** Default URL za NauSys je `http://ws.nausys.com`. Komunikacija ide preko plaintext HTTP-a. Credentials NauSYS_USERNAME/PASSWORD šalju se u svakom requestu (vidi DevEquipmentSyncController liniju 58-63 — username/password u tijelu). MitM napadač u istoj mreži (osobito ako NauSYS server stvarno ne podržava HTTPS) može presjeći credentials i sve sinkronizirane podatke jahti/agencija.
**Posljedica:** kompromis NauSYS credentialsa, presretanje sinkronizacijskih podataka.
**Predloženi fix:** verificirati podržava li `ws.nausys.com` HTTPS. Ako da — promijeniti default na `https://`. Ako ne — eskalacija prema NauSYS-u (oni moraju omogućiti HTTPS) ili prikazivanje warningom ako NauSYS endpoint nije HTTPS. Faza 3 dependency.
**Riziko-procjena fixa:** ovisi o NauSYS-u; ne radim sam.
**Status:** OPEN — Faza 3 deeper analiza, eskalacija

---

### [F1-038] CRIT/HIGH security — `application-prod.yml` postavlja `server.ssl.enabled: false` na port 8080; pretpostavlja TLS-terminating proxy koji README_PROD ne dokumentira
**Lokacija:** `application-prod.yml:1-4`
**Detekcija:** statička
**Opis:** Prod profil postavlja:
```yaml
server:
  ssl:
    enabled: false
  port: 8080
```
To znači da app u prod profilu sluša **plain HTTP** na portu 8080. Ovo je tipično za setup gdje pred aplikacijom stoji TLS-terminating reverse proxy (nginx, HAProxy, ALB). README_PROD.md, koji je naš jedini izvor produkcijske konfiguracije, **ne spominje nginx ni bilo koji proxy**. Sistem definicija (systemd) samo zove `java -jar` direktno na port 8080.

Ako VM2/VM3 izlažu port 8080 direktno u javnost, **HTTPS se ne koristi nigdje** — sve auth credentials, JWT tokeni, payment redirect URL-ovi idu preko plaintext-a. To zajedno s F1-022 (rate-limit XFF spoof) sugerira da nema reverse proxy.
**Posljedica:** ako nema proxy: **kompletna deprivatizacija sve mrežne komunikacije**. Auth tokens, lozinke, PII, payment data — sve plaintext na network nivou.
**Predloženi fix:** treba **odmah verificirati** s tobom (operativno znanje):
- Postoji li nginx/ALB/CloudFront pred VM2/VM3?
- Ako da, dokumentirati ga u README_PROD i postaviti X-Forwarded-* sigurno (vidi F1-022).
- Ako ne, treba HTTPS na app-strani — `server.ssl.enabled: true` + Let's Encrypt cert (ne self-signed iz F1-035).
**Riziko-procjena fixa:** ovisi o operativnom kontekstu; ne mijenjam dok ne pojasniš.
**Status:** OPEN — eskalacija (potencijalni blocker)

---

### [F1-039] HIGH security — Swagger gating: defaults su `true`, prod yml ne postavlja explicit `false`
**Lokacija:** `application.yml:86, 91-105`, `application-prod.yml` (nedostaje), `.env.example:54`
**Detekcija:** statička
**Opis:** `springdoc.api-docs.enabled: ${SWAGGER_ENDPOINT_ENABLED:true}` i `spring.web.resources.add-mappings: ${SWAGGER_ENDPOINT_ENABLED:true}` — oba defaultaju na **`true`**. `application-prod.yml` **ne sadrži override za springdoc niti za swagger gating**. Jedini gating je postavljanje `SWAGGER_ENDPOINT_ENABLED=false` u env file-u. Ako se to zaboravi (vrlo realno: `.env.example` postavlja `=true`!), **swagger UI + svi OpenAPI endpointi su otvoreni u prod-u**. To u kombinaciji s F1-002 (Spring Security permitAll-a swagger paths) znači da **bilo tko, bez auth, može povući kompletni katalog svih internih endpointa, modela, parametara, secret schema-a, security definicija**.
**Posljedica:** kompletni API discovery za napadača, mapa svih napadnih površina.
**Predloženi fix:** prod yml mora imati eksplicitno:
```yaml
springdoc:
  api-docs:
    enabled: false
  swagger-ui:
    enabled: false
spring:
  web:
    resources:
      add-mappings: false
```
S `SWAGGER_ENDPOINT_ENABLED=false` kao environment override-om mogućnosti za testing. Bolje: hardcoded `false` u prod yml, bez env override-a.
**Riziko-procjena fixa:** mali, ali važan. Ne mijenjam dok ne odobriš.
**Status:** OPEN

---

### [F1-040] MED security — JWT access token expiration 24 sata
**Lokacija:** `application.yml:117`
**Detekcija:** statička
**Opis:** `application.security.jwt.expiration: 86400000` = 24 sata. Industrijski standard za **access token** je 15-60 minuta; refresh tokeni žive duže (3-30 dana). 24h access znači da kompromitirani token (npr. preko leaked logu, MITM, browser cache) ostaje validan punih 24h. Refresh je 3 dana — cleaner pattern bi bio access 30min + refresh 3 dana.
**Posljedica:** widow napada s ukradenim tokenom je 24h umjesto 30min.
**Predloženi fix:** `expiration: 1800000` (30 min) ili `3600000` (1h).
**Riziko-procjena fixa:** mali, ali utječe na frontend. Ako frontend ne radi automatski refresh, korisnici se logiraju svakih 30min — UX impact. Treba dogovor.
**Status:** WAITING-DECISION

---

### [F1-041] HIGH security — `DevEquipmentSyncController` ima `@RequestMapping("/public/dev")` + nema `@PreAuthorize`; sigurnost ovisi isključivo o `@Profile("dev")`
**Lokacija:** `domains/external/dev/DevEquipmentSyncController.kt:31-32`
**Detekcija:** statička
**Opis:** Controller je namijenjen "dev only" (vidi komentar line 16-29). Path je **`/public/dev/**`** — koje SecurityConfiguration permitAll-a (`/public/**`). Defense-in-depth POSTOJI samo kao `@Profile("dev")` — što znači da Spring ne registrira bean kad profil nije aktivan. **Problem**: ako se profil greškom aktivira u prod-u (npr. `SPRING_PROFILES_ACTIVE=prod,dev` ili copy-paste env-a iz dev-a), endpointi su:
- bez auth-a (jer permitAll na `/public/`)
- triggeraju heavy NauSys + MMK API pozive (DoS na vanjskim providere kvotama, "Long-running ~minutes for 12k yachts")
- `clearCaches()` — brisanje svih cache-ova (DoS na app)
- `nauSysYachtEquipment` izlaže NauSys API responses (PII vendor data)

Komentar autora eksplicitno priznaje: "this one bypasses auth because it lives behind the dev-only profile". To je **fragile defense-in-depth violation**.
**Posljedica:** ako profil greškom uključen u prod, kompletna anonymous DoS + data leak vector.
**Predloženi fix:** dodati `@PreAuthorize("hasRole('SYSTEM_ADMIN')")` defenzivno. Ili premjestiti na `/admin/dev/` path. Ili obrisati cijeli controller iz produkcije i držati ga u zasebnoj `dev-tools/` source root-u koji ne ide u prod build.
**Riziko-procjena fixa:** mali, defenzivan.
**Status:** OPEN

---

### [F1-042] LOW design — `MmkSyncController` i `NausysSyncController` koriste GET za state-changing operacije
**Lokacija:** `domains/external/mmk/controller/MmkSyncController.kt:27, 42, 47, 52, 57, 62`, `domains/external/nausys/controller/NausysSyncController.kt:19, 24, 29, 34, 39, 44`
**Detekcija:** statička
**Opis:** `@GetMapping("/sync")`, `@GetMapping("/yachts")` itd. — sve pokreću **state-changing** sinkronizaciju (write u DB, vanjski API pozivi). REST konvencija nalaže POST/PUT za state changes. GET-ovi se mogu pokrenuti CSRF-om na admin user-u koji je logiran (CSRF je disabled, ali browser cache, link-prefetch, cross-tab navigacija svejedno mogu trigger-ati GET-ove). Manji rizik kod stateless JWT autentikacije, ali svejedno anti-pattern.
**Posljedica:** anti-pattern; cross-tab/link-prefetch može slučajno pokrenuti sync ako admin user otvori URL.
**Predloženi fix:** promijeniti u `@PostMapping`. Ne dira poslovnu logiku.
**Riziko-procjena fixa:** trivijalno, ali utječe na admin frontend (mora promijeniti method).
**Status:** WAITING-DECISION

---

### [F1-043] MED bug — `MmkSyncController.offer2` ima hardkodirani datum 2025-08-09/2025-08-13
**Lokacija:** `domains/external/mmk/controller/MmkSyncController.kt:62-68`
**Detekcija:** statička
**Opis:** `@GetMapping("/offer2")` poziva `mmkYachtOfferIntegrationServiceAsync.syncOffersForDateRange(startDate, endDate, null, null, null)` s hardkodiranim `LocalDate.of(2025, 8, 9)` do `LocalDate.of(2025, 8, 13)`. Ovo je očito test/debug code u kojem je netko probao async sync na nekom konkretnom datumu. Ostao u repu, ostao u produkciji. Ako admin slučajno klikne, sync se pokrene za prošlogodišnji datum — bezopasno ali besmisleno.
**Posljedica:** dead/test code u produkciji; lažni admin endpoint.
**Predloženi fix:** ili obrisati endpoint, ili promijeniti da prima `@RequestParam`-ima datume.
**Riziko-procjena fixa:** trivijalno, mogu u trivijalni batch.
**Status:** WAITING-DECISION

---

### [F1-044] MED design — `TestJobController` u produkcijskom kodu, krivi package, dead-code komentar
**Lokacija:** `domains/catalouge/job/TestJobController.kt`
**Detekcija:** statička
**Opis:** Klasa se zove `TestJobController` što za prod kod sugerira "ovo nije production-ready". Ipak u repu, profile-gated (`data-sync`) + auth-gated (`SYSTEM_ADMIN`) — **nije sigurnosni rizik trenutno**, ali:
- Naziv `TestJobController` je smell — ne kaže što radi (admin ručno triggeranje cron jobova).
- Smješten u `domains/catalouge/job/` umjesto `…controller/`. Krivi package, otežava grep i mental model.
- Linija 41-44: closed-comment dead method (`// @GetMapping("/sendOptionExpiredNotification")`).
- File ima nevidljive imports za `DeleteExpiredReservationsAndOffersJob` i `ExchangeRateSyncJob` na liniji 17-18 (ovi nisu u imports — možda u istom paketu).
**Posljedica:** confusing, demonstrira slabu code hygiene; svako-buduće-novo-test-funkcionalno-trigger-anje vjerojatno ide ovdje pa raste rizik.
**Predloženi fix:** rename u `JobAdminController` (ili sl), premjestiti u `domains/admin/controllers/`, obrisati dead-comment, dodati class-level KDoc s objašnjenjem.
**Riziko-procjena fixa:** mali, refactor.
**Status:** WAITING-DECISION

---

### [F1-045] MED security/privacy — `application.external.sync.audit.response-body: true` u root yml-u; PII curi u audit log/DB
**Lokacija:** `application.yml:126-128`, `application-prod.yml:9-11`, `application-dev.yml:14-16`
**Detekcija:** statička
**Opis:** Root yml ima `audit.response-body: true` (default). Prod yml override-a na `false`. Dev yml override-a na `false`. Ali ako prod profil **iz nekog razloga ne učita**, default je `true`. Što to radi — pretpostavljam (Faza 3 verifikacija): zapisuje cijela response body-ja iz NauSys/MMK u audit tablicu ili log. Ti odgovori sadrže PII (imena, emailovi, phone, adresa, payment), kao i komercijalne podatke (cijene, dostupnost). Memoriziranje u plaintext bazi ili logu = GDPR data scope expansion.
**Posljedica:** ako prod profile fail-over (rare), PII curi u audit storage.
**Predloženi fix:** root yml default `false`. Eksplicitno `true` samo u dev yml-u za debugging. Validirati u Fazi 3 gdje se response-body spremaju i kako se brišu.
**Riziko-procjena fixa:** trivijalno, mogu u trivijalni batch.
**Status:** WAITING-DECISION

---

### [F1-046] MED security — `key-store-password: ${SSL_KEYSTORE_PASSWORD:}` defaulta na **prazan string** u root yml
**Lokacija:** `application.yml:9`
**Detekcija:** statička
**Opis:** Ako env var nije postavljen, lozinka za p12 keystore je `""` (empty string). PKCS12 standard tehnički dopušta empty password — što je tu lošije nego "no password" u praksi: keystore se može učitati bez znanja lozinke. Što znači **ako keystore propusti nekamo (napadač dobije p12 file iz Docker image-a, vidi F1-035), može ga otvoriti bez znanja lozinke**.
**Posljedica:** zajedno s F1-035 — privatni ključ trivijalno dostupan.
**Predloženi fix:** `${SSL_KEYSTORE_PASSWORD:?SSL_KEYSTORE_PASSWORD required}` — fail startup ako nije postavljen. Linkano s F1-035.
**Riziko-procjena fixa:** mali, ali budi treba postaviti env vars; ne mijenjam dok ne odobriš.
**Status:** OPEN

---

### [F1-047] LOW UX — `server.host-public` default `http://localhost:5173` propušta u password reset email-ove ako env nije postavljen
**Lokacija:** `application.yml:3`, `security/services/UserAuthService.kt:387-389`
**Detekcija:** statička
**Opis:** `serverHostPublic: ${SERVER_HOST_PUBLIC:http://localhost:5173}` — default je local dev URL. `generatePasswordResetLink` ovo koristi: `serverHostPublic + "/forgot-password?passwordResetCode=..."`. Ako prod env zaboravi postaviti, password reset emailovi šalju linkove `http://localhost:5173/forgot-password?...` — useri kliknu, link ne radi (njihov localhost).
**Posljedica:** broken UX, customer support tickets, nemogućnost reseta lozinke ako ENV pogriješi.
**Predloženi fix:** `${SERVER_HOST_PUBLIC:?SERVER_HOST_PUBLIC required}`.
**Riziko-procjena fixa:** trivijalno; mogu u trivijalni batch.
**Status:** WAITING-DECISION

---

## Sažetak F1-035 do F1-047

- **CRIT (2):** F1-035 (SSL keystore u repu), F1-038 (HTTP u prod profile)
- **HIGH (4):** F1-036 (DB default creds), F1-037 (NauSYS HTTP), F1-039 (Swagger default true), F1-041 (DevEquipmentSync /public/dev)
- **MED (5):** F1-040, F1-043, F1-044, F1-045, F1-046
- **LOW (1):** F1-042, F1-047

---

---

## Batch 4 — Updates after VM2 SSH inspection + external probes (2026-05-07)

VM2 nginx config je pregledan, probe-ovi izvana izvršeni. Sažetak otkrića koji utječe na ranije nalaze + novi nalazi.

### Confirmed VM2 environment
- Hosting: javni IP `91.98.209.181` (vjerojatno Hetzner range, neverificirano)
- nginx 1.24.0 (Ubuntu) sluša 80/443
- Java app sluša `*:8080` (sva sučelja, ne samo loopback) — **POPRAVLJENO** (vidi F1-049 status)
- nginx config za `api.boat4you.com`: Let's Encrypt cert, TLS 1.2+1.3, redirect 80→443, proxy_pass na 127.0.0.1:8080
- XFF handling: `proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for` + `X-Real-IP $remote_addr`
- **NEMA OS-level firewalla** (`ufw`, `nft`, `firewalld` svi missing) — moguć cloud-level firewall, neverificiran

### Status updates na ranije nalaze

**F1-019** (Stripe webhook idempotency) — **STAYS CRIT**, nije adresirano.

**F1-022** (XFF spoof) — **STAYS HIGH, sada s točnim fixom**: nginx postavlja `X-Real-IP $remote_addr` (TCP peer = stvarni klijent, nije spoof-able). App-ov `clientIp()` u `PublicReservationRateLimiter.kt:96-104` čita XFF prvo, X-Real-IP drugo. Trivijalan fix: invertirati redoslijed da prvo gleda `X-Real-IP`. Plus — može potpuno zanemariti XFF (nginx je single hop, X-Real-IP je dovoljno).

**F1-035** (SSL keystore u repu) — **DEMOTIRA s CRIT na MED**: prod NE koristi `boat4you_selfsigned.p12` (verificirano: prod koristi Let's Encrypt iz `/etc/letsencrypt/live/api.boat4you.com/`). p12 file je dead-weight + private key u Git history-u. Treba ga maknuti iz repa (i git history-a) za hygiene, ali ne kompromitira production TLS.

**F1-038** (`server.ssl.enabled: false` u prod yml) — **DEMOTIRA s CRIT na N/A**: nginx terminira TLS na rubu, app-app komunikacija ide preko 127.0.0.1 — standardni i sigurni pattern. **Zatvoreno bez akcije** uz dokumentaciju.

**F1-039** (Swagger gating) — **DEMOTIRA s HIGH na LOW**: prod env ima `SWAGGER_ENDPOINT_ENABLED=false` (verificirano probe-om: `/v3/api-docs` vraća 500 + "No endpoint" body, ne servira UI). Ostaje preporuka da yml hardcode-a `false` u `application-prod.yml` umjesto oslanjanja na env var.

**F1-048** (TLS 1.0/1.1 u nginx.conf) — **DEMOTIRA s HIGH na LOW**: `boat4you.conf` includea `options-ssl-nginx.conf` koji override-a globalni `ssl_protocols` na samo `TLSv1.2 TLSv1.3`. Globalna postavka utječe samo na default 404 catchalla — irrelevant za app traffic.

**F1-049** (port 8080 izložen) — **POTVRĐEN CRIT, ZATIM POPRAVLJEN**: external probe potvrdio `curl http://api.boat4you.com:8080/auth/login` doseže Spring Boot direktno (response time 186ms, full Spring Security headers). **FIXED-COMMIT 02532a9** (`server.address: 127.0.0.1` u `application-prod.yml`). Treba **rebuild JAR-a + restart systemd service** na VM2 (i VM3). **Cloud-level firewall block na port 8080** je preporučeni defense-in-depth.

---

### [F1-049-EXTERNAL-VERIFICATION] (referencni zapis)
**Lokacija:** N/A — production probe rezultat
**Detekcija:** dynamic — vanjski curl probe iz review session-a
**Reprodukcija:**
```bash
curl -sIv http://api.boat4you.com:8080/ 2>&1
# → HTTP/1.1 403, Connected to 91.98.209.181:8080
curl -X POST -H "Content-Type: application/json" \
  -d '{"email":"x","password":"x"}' \
  http://api.boat4you.com:8080/auth/login
# → HTTP 403, time 0.186s — direktan pogodak Spring Boot-a
```
**Status:** FIXED — nakon prod redeploya više neće biti reproducibilno.

---

### Novi nalazi iz nginx + probe analize

### [F1-050] LOW security — nginx leak-a verziju kroz `Server:` header
**Lokacija:** `/etc/nginx/nginx.conf` na VM2 — `# server_tokens off;` (zakomentirano)
**Detekcija:** vanjski probe — `curl -I https://api.boat4you.com/` vraća `Server: nginx/1.24.0 (Ubuntu)`
**Opis:** nginx default-no šalje verziju u `Server:` header. Dok je nginx 1.24.0 trenutno bez kritičnih CVE-ova, otkrivanje verzije pomaže napadaču u targetingu (CVE database lookup, exploit selection).
**Predloženi fix:** odkomentirati `server_tokens off;` u `/etc/nginx/nginx.conf` (`http { ... }` block). Reload nginx (`sudo nginx -s reload`).
**Riziko-procjena fixa:** trivijalno, nginx-side promjena.
**Status:** WAITING-DECISION (nginx config promjena, vjerojatno tvoj direktan fix na serveru)

---

### [F1-051] LOW security — Default `_`-host nginx server block-a otkriva nginx default page
**Lokacija:** `/etc/nginx/sites-enabled/default` na VM2
**Detekcija:** `nginx -T` — `default_server` na portu 80 sa `server_name _; root /var/www/html;`
**Opis:** Default nginx site u sites-enabled aktivan. Hit-aju li napadači `http://91.98.209.181/` (direktno IP, bez DNS hosta), nginx vraća sadržaj iz `/var/www/html` (ili 404 ako prazno). Ne curi nikakav app data, ali otkriva da je nginx tu i da je default config.
**Predloženi fix:** `sudo rm /etc/nginx/sites-enabled/default && sudo nginx -t && sudo nginx -s reload`. Ili promijeniti default na `return 444;` (silent close).
**Riziko-procjena fixa:** trivijalno, ali izvanrepo (nginx-side).
**Status:** WAITING-DECISION

---

### [F1-052] INFO — Let's Encrypt cert se auto-renew-a; treba verificirati timer
**Lokacija:** `/etc/letsencrypt/live/api.boat4you.com/` (VM2)
**Detekcija:** `nginx -T` pokazuje `# managed by Certbot` u config-u
**Opis:** Cert je iz Let's Encrypt-a, valjanost 90 dana. Mora postojati cron/systemd timer koji ga pokreće (`certbot renew`). Ako timer crash-ne ili nije instaliran, cert istekne.
**Predloženi fix:** verifikacija (jednoredno):
```bash
sudo systemctl list-timers | grep -i certbot
sudo systemctl status certbot.timer --no-pager
```
**Status:** OPEN — verifikacija (samo provjera, nije nužno fix)

---

### [F1-053] MED security — Nedostaje HSTS header iz nginx-a
**Lokacija:** `/etc/nginx/conf.d/boat4you.conf`
**Detekcija:** vanjski probe — `curl -I https://api.boat4you.com/` ne vraća `Strict-Transport-Security`
**Opis:** Bez HSTS-a, browser dopušta first-time SSL stripping napad (napadač u sredini servira plain HTTP umjesto redirect-a na HTTPS). HSTS nakon prve uspješne posjete govori browseru "ne kušaj HTTP godinu dana".
**Predloženi fix:** u `/etc/nginx/conf.d/boat4you.conf`, unutar `server { listen 443 ssl; ... }` block-a:
```nginx
add_header Strict-Transport-Security "max-age=63072000; includeSubDomains" always;
```
Razmotriti i `preload` direktivu kasnije (zahtjeva prijavljivanje na hstspreload.org).
**Riziko-procjena fixa:** mali, ali jednom postavljen HSTS se ne može lako maknuti (browseri ga keš-iraju). Ne mijenjam dok ne odobriš.
**Status:** OPEN

---

### [F1-054] INFO/POSITIVE — Spring Security default security headers su aktivni
**Lokacija:** Spring Security defaults (kroz `SecurityConfiguration.kt`)
**Detekcija:** vanjski probe — response sadrži:
- `X-Content-Type-Options: nosniff` ✓
- `X-Frame-Options: DENY` ✓
- `X-XSS-Protection: 0` ✓ (correct — modern guidance je da se XSS Auditor isključi, jer browser implementacije imaju vlastite probleme)
- `Cache-Control: no-cache, no-store, max-age=0, must-revalidate` ✓
- `Pragma: no-cache`, `Expires: 0` ✓
**Status:** INFO — pozitivan nalaz, ostaje kao zapis

---

### [F1-055] MED exception clarity — Globalni error handler curi internu put-strukturu u response body
**Lokacija:** `common/errorhandling/ApiErrorHandler.kt` (treba pročitati u Fazi 5)
**Detekcija:** vanjski probe — `GET /v3/api-docs` (nepostojeći path) vraća:
```json
HTTP 500
{"code":1000,"message":"Unknown error: No endpoint GET /v3/api-docs."}
```
**Opis:** Generic handler vrati 500 (umjesto 404) s tekstom koji explicitno citira request method i path. Napadač može endpoint-scan-ati: ako `/auth/login` vraća drugi error pattern, "No endpoint" pokrivanje signalizira "ovo nije implementirano". Bonus: 500 status pogrešan — to je 404 / 405 case, ne 500. Tvoj zahtjev "exception poruke koje ne govore dovoljno (programeru)" je ovdje obrnut: govore previše napadaču + krivi HTTP code.
**Posljedica:** endpoint discovery, route-fingerprint, krivi status code curi u monitoring (false 500 spike).
**Predloženi fix:** vratiti 404 + generic poruka `{"code":404,"message":"Not found"}`; logirati internu poruku u serveru. Faza 5 dependency.
**Riziko-procjena fixa:** dira global error handler — ne mijenjam dok Faza 5 ne pregleda ApiErrorHandler u kontekstu.
**Status:** OPEN — Faza 5

---

## Trenutni ukupni progress (post Batch 4)

| Severity | Count | Lista |
|---|---|---|
| **CRIT** | 1 | F1-019 (Stripe webhook idempotency) — F1-049 popravljen, F1-035 i F1-038 demoted |
| **HIGH** | 11 | F1-002, F1-003, F1-004, F1-005, F1-020, F1-021, F1-022, F1-024, F1-036, F1-037, F1-041 |
| **MED** | 19 | F1-001, F1-006, F1-007, F1-008, F1-009, F1-010, F1-011, F1-023, F1-025, F1-026, F1-028, F1-030, F1-033, F1-034, F1-035 (↓), F1-040, F1-043, F1-044, F1-045, F1-046, F1-053, F1-055 |
| **LOW** | 13 | F1-012, F1-014, F1-015, F1-016, F1-017, F1-018, F1-027, F1-029, F1-031, F1-032, F1-039 (↓), F1-042, F1-047, F1-048 (↓), F1-050, F1-051 |
| **INFO** | 2 | F1-052 (Let's Encrypt OK), F1-054 (Spring Security defaults OK) |
| **FIXED** | 1 | F1-049 (commit 02532a9) |

**Ukupno: ~50 nalaza** (s deduplicirano demote-anim).

Read pass je dovršen za: security core (14), webhook + Stripe chain (3), file upload core (1), rate-limit (1), profile-gated controllers (4), application yml configi (3), nginx config (vanjska analiza), VM2 OS state (vanjska analiza).

**Ostalo za read pass:** application-aws.yml ✓, public + admin REST controlleri (~24), file upload **callers** (5 — kritično za F1-021/F1-034 verifikaciju).

---

## Batch 5 — Public + admin controlleri, ReservationDocumentService, YachtMutationService (2026-05-07)

### F1-021/F1-034 verifikacija (path traversal)

**ReservationDocumentService**: NE koristi FileSystemService. Spreme dokumente direktno u DB BYTEA (`data: ByteArray` u entity-u). Filename je sanitiziran (`sanitizeFilename` strip-a path separatore Windows + POSIX, trim, blank fallback "document", clamp 255). **Path traversal NIJE primjenjiv** za reservation documente.

**YachtMutationService**: KORISTI `FileSystemService`. Pozivi:
- `createYacht(...)` → `createNewYachtImage(yacht, image, ...)` — upload images preko FileSystemService
- `attachPdfFile(yacht, pdfFile)` — PDF upload preko FileSystemService
- Svi pozivi prosljeđuju **MultipartFile koji dolazi direktno iz HTTP request-a** od admina (preko `AdminYachtController` `@RequestPart`)
- ALI: `subpath` koji se prosljeđuje u `FileSystemService.saveImage/saveFile` se interno gradi u service-u, NE iz user inputa (yacht ID + statički prefix). Treba pročitati `createNewYachtImage` da to potvrdim.

**F1-021 (path traversal in subpath)**: ovisi o tome je li `subpath` user-controlled. Iz čitanja YachtMutationService.kt-a (linije 1-120 view), `subpath` izgleda hardkodiran ili izveden iz yacht ID-a. **Treba pročitati ostatak YachtMutationService.kt-a + `createNewYachtImage` da završimo**.

---

### Pozitivni nalazi iz ovog batcha (INFO)

- **`ReservationController.downloadMyReservationDocument`** (line 105-137) je **ekselentan primjer** ownership guarda: provjerava (a) user owns reservation, (b) document belongs to reservation, (c) document not internal. **Pohvala — koristiti kao template za druge endpointe.**
- **ReservationController.cancelReservationRequest** (line 173-195), **getMyReservations** (line 64-79, 83-102), **getYachtSwap** (line 240-259), **acknowledgeYachtSwap** (line 262-281) — sve provjeravaju ownership. **NEMA IDOR-a u ReservationController-u.**
- **AdminReservationController** ima `@PreAuthorize("hasRole('SYSTEM_ADMIN')")` na class-level. Sve admin endpoint metode pokrivene.
- **PublicReservationRateLimiter** štiti `/public/reservations` od F1-022 vidljivog rizika (uz X-Real-IP fix).
- **GDPR endpointi** (`/users/me`, `/users/me/export`, `/users/me/account-info`) imaju audit logging i razmišljaju o pravu na zaborav — **arhitekturno mature**.

---

### Novi nalazi

### [F1-056] HIGH integrity — `cancelReservation` non-atomic external delete + DB cancel; razdvojeno stanje moguće
**Lokacija:** `domains/reservation/controllers/AdminReservationController.kt:363-380`
**Detekcija:** statička
**Opis:** Sekvenca: `deleteExternalReservation` (NauSys/MMK API) → `cancelReservation` (DB) → `sendCancellationApproved` (email). Bez saga / two-phase commit / kompenzacijskih akcija. Ako external API uspije ali DB transaction faila (FK constraint, optimistic lock, network blip): **partner nema booking, mi i dalje pokazujemo aktivnu rezervaciju**. Reverse (DB ok, external 5xx): **mi pokazujemo cancelled, partner i dalje drži yacht-a kao booked**. Email u oba slučaja može biti poslan bez stvarne synca.
**Posljedica:** desync između nas i partnera, financial impact (yacht-a koji je "naš" cancelled i partner ga proda drugome, ali kupac dobije izvještaj da je njegov), customer complaints.
**Predloženi fix:** outbox pattern — DB transaction ima vlastitu cancellation + outbox event row. Async job konzumira outbox i poziva external. Ili minimalno: hvataj iznimke iz external poziva i logiraj alarm; ako DB cancel faila, kompenziraj external delete-om ili logiraj alarm.
**Riziko-procjena fixa:** dira reservation lifecycle + treba migraciju (outbox table) → ne mijenjam dok ne odobriš.
**Status:** OPEN — eskalacija (Faza 3 produbljivanje za external pattern)

---

### [F1-057] HIGH security — `PublicUserController.setPasswordForReservation` bez rate limita; sekvencijalni reservationId enumeration
**Lokacija:** `domains/users/controllers/PublicUserController.kt:41-81`, `common/ratelimit/PublicReservationRateLimiter.kt:53-57` (samo /public/reservations)
**Detekcija:** statička
**Opis:** Public endpoint, no auth required. Set password for guest user koristeći `(reservationId, email)` par kao auth proof. Vague exception message za failed match (✓ dobro). ALI:
- Nema rate limit — `PublicReservationRateLimiter` štiti samo `POST /public/reservations`, ne `POST /public/users/set-password-for-reservation`.
- `reservationId` je sekvencijalan Long (autoincrement DB id) — napadač može enumerirat 1, 2, 3, ... s istim email-om i pogađati matchom.
- Vjerojatnost matcha jako mala (treba (correct reservationId, correct email)), ali napadač može probati MILIJUNE puta bez ikakvog throttlinga.
- Plus: 6-character password minimum (vidi F1-004) — ako napadač pogodi (reservationId, email), može postaviti slabu šifru.
- **Komentar autora** (line 27): "Everything here must defend against anonymous abuse on its own (rate limit / captcha at the gateway)" — explicit ack da rate-limit treba postaviti, ali nije.
**Posljedica:** bez rate limita, mass enumeration; ako attacker zna email od targeta, može probati N reservation ID-eva dok ne pogodi.
**Predloženi fix:** ili (a) proširiti `PublicReservationRateLimiter` na sve `/public/users/**` paths, ili (b) dodati globalni rate-limiter filter za sve /public/ rute (vidi F1-003), ili (c) koristiti random string password setup token umjesto reservationId+email.
**Riziko-procjena fixa:** mali, ali boundary; rate-limiter ekstenzija je trivijalan refaktor.
**Status:** OPEN — povezuje s F1-003

---

### [F1-058] MED design — Repetitivni manual auth check pattern u svakom secured endpointu; nedostaje class-level `@PreAuthorize`
**Lokacija:** `ReservationController.kt:67-79, 88-95, 111-112, 178-185, 213-221, 245-249, 267-271`, `users/controllers/UserController.kt:175-178, 196-198, 216-218, 232-234`, `domains/reservation/controllers/AdminReservationController.kt:110-114, 128-132`
**Detekcija:** statička
**Opis:** Svaki secured endpoint (osim `/admin/**` koji ima `@PreAuthorize` na klasi) manualno radi:
```kotlin
val user = getAuthenticatedUserId().takeIf { it != ANONYMOUS_USER_ID }?.let { userRepository.findById(it).getOrNull() }
if (user == null) throw AccessDeniedException("User is not authenticated")
```
Repetitivno (~10x), DRY violation, novi endpoint lako zaboravi check (regress rizik). `ReservationController` (`/secured/reservations`) ima poznat URL prefix `/secured/` — ali Spring Security config ne provjerava tu da li je authentificiran (vidi SecurityConfiguration.kt — `anyRequest().authenticated()` pokriva sve van permitAll, ali isključivo na razini Spring Security filter chain-a, što znači user može biti `AnonymousAuthenticationToken` koji prolazi kroz `authenticated()`? — treba dvostruko provjeriti; čini mi se da `authenticated()` ne pušta anonymous, ali kod tretira anonymous kao "logged in" sa specifičnim token-om).
**Posljedica:** 10 mjesta gdje se može zaboraviti check; kod je previše nakićen za jednostavnu provjeru.
**Predloženi fix:** dodati `@PreAuthorize("isAuthenticated()")` na class-level `ReservationController`. Pomaknuti load user-a iz DB-a u helper. Verificirati Spring Security `authenticated()` semantiku — anonymous prolazi ili ne?
**Riziko-procjena fixa:** mali, ali dira sve secured endpointe → ne mijenjam dok ne odobriš.
**Status:** OPEN

---

### [F1-059] MED exception clarity — `runCatching { … }.onFailure { /* comment-only */ }` proguta failure bez logiranja
**Lokacija:** `domains/reservation/controllers/AdminReservationController.kt:340, 378`
**Detekcija:** statička
**Opis:**
```kotlin
runCatching { reservationEmailService.sendOptionCreatedEmail(reservation.id!!) }
    .onFailure { /* email failure must not roll back the reservation */ }
```
Empty `onFailure {}` block — exception se proguta, ne logira nigdje. Komentar argumentira "ne smije rollbackati", što je **točno za atomicity**, ali ne implicira "ne smije logirati". Operativni tim nikad ne sazna kad email throw-a (SMTP outage, template error, mail server unreachable). Customer ne dobiva email, support ticket dolazi, debug je sviranje na sluh.
**Posljedica:** **direktno protiv tvog zahtjeva** "exceptioni koji nam ne govore dovoljno" — ovdje exception se uopće ne registrira nigdje.
**Predloženi fix:** `.onFailure { e -> log.error("Failed to send <emailtype> for reservation $id", e) }`. Razmotriti dodavanje "failed_email_attempts" metrika za monitoring.
**Riziko-procjena fixa:** trivijalno; mogu u trivijalni batch.
**Status:** WAITING-DECISION

---

### [F1-060] LOW exception clarity — `findById(id).orElseThrow()` bez supplier-a vraća generic 500
**Lokacija:** `domains/reservation/controllers/AdminReservationController.kt:224`, `PublicUserController.kt:53` (ali tu je prije `getOrElse{...}`), drugi pozivi imaju specifične exceptione
**Detekcija:** statička
**Opis:** `reservationRepository.findById(id).orElseThrow()` (line 224) bez supplier-a baca generičku `NoSuchElementException("No value present")`. Bez specific handler-a u ApiErrorHandler-u, postaje 500 prema klijentu sa internom porukom. Standardni pattern u ostatku koda je `.getOrElse { throw ReservationNotExistException() }` — line 224 ne slijedi.
**Posljedica:** 500 status code (ne 404), generic poruka, info leak.
**Predloženi fix:** `reservationRepository.findById(id).orElseThrow { ReservationNotExistException() }`.
**Riziko-procjena fixa:** trivijalno.
**Status:** WAITING-DECISION

---

### [F1-061] LOW config — `application.test.enabled` postavljen u yml-u, NIGDJE se ne čita; stale komentar u kodu
**Lokacija:** `application-prod.yml:26-27`, `application-aws.yml:19-20`, `domains/reservation/controllers/AdminReservationController.kt:320` (komentar)
**Detekcija:** grep
**Opis:** `application.test.enabled` se postavlja u yml-ovima (prod=false, aws=true), ali grep pokazuje **nigdje se ne konzumira u Kotlin kodu** (nema `@Value("\${application.test...}")`, nema `@ConditionalOnProperty`). Komentar u AdminReservationController.kt:320 referencira "DEV BYPASS (test.enabled=true => synthetic mock response)" — što sugerira da je nekad postojao kod koji čita ovu zastavicu, kasnije uklonjen, komentar ostao.
**Posljedica:** zbunjujući config + stale komentar; minor cleanup. Posljednje verzije refaktora gube info.
**Predloženi fix:** ili (a) ukloniti `application.test.enabled` iz yml-ova + komentar; ili (b) revratiti namjeru (ako se planirano koristi u test profile-u za mock external APIje, vrate consumer kod). Vjerojatnije (a).
**Riziko-procjena fixa:** trivijalno.
**Status:** WAITING-DECISION

---

### [F1-062] MED security — Login timing attack: BCrypt compare se izvršava samo ako user postoji
**Lokacija:** `security/services/UserAuthService.kt:74, 99-103`
**Detekcija:** statička
**Opis:** `findByEmail(email) ?: throw InternalLoginException(BAD_CREDENTIALS, email)` — ako email ne postoji, response je trenutan (samo DB lookup ~5ms). Ako email postoji, izvršava se `passwordService.doesMatch(password, dbUser.password)` što je BCrypt compare — ~50-100ms. Razlika u response time-u dovoljna napadaču da razlikuje "user postoji ali šifra kriva" od "user uopće ne postoji" — **email enumeration kroz timing**.
**Posljedica:** account enumeration, isto kao F1-010 ali kroz timing umjesto exception type.
**Predloženi fix:** uvijek izvršiti dummy BCrypt compare ako user ne postoji (compare protiv konstantnog hash-a):
```kotlin
val dbUser = userRepository.findByEmail(email)
if (dbUser == null) {
    passwordService.doesMatch(password, DUMMY_BCRYPT_HASH) // constant-time burn
    throw InternalLoginException(BAD_CREDENTIALS, email)
}
```
**Riziko-procjena fixa:** mali, dira auth flow → ne mijenjam dok ne odobriš.
**Status:** OPEN

---

### [F1-063] MED security — `YachtController.getYachts` ne validira veličine list query paramova; potencijalni query bomb
**Lokacija:** `domains/catalouge/controllers/YachtController.kt:50-104` — 24 query paramova, mnogi `List<...>` bez `@Size`/`@Max`/itd.
**Detekcija:** statička
**Opis:** Public endpoint `GET /public/yachts?did=1,2,3...&mfid=1,2,3...&yid=1,2,3...&...`. Nijedan `List<...>` parametar nije veličinski validiran. Anonimni napadač može poslati `mfid=1,2,3,...,10000` — service generira SQL `WHERE manufacturer_id IN (1,2,...,10000)`. Postgres može handle-ati to, ali:
- query plan complexity raste, ne koristi indeks efikasno
- response može biti veliki ako agency search nije također filtriran
- pod paralelnim opterećenjem (DDoS): hijab pretvara DB CPU
**Posljedica:** moguć resource exhaustion preko anonymous boom-search query-ja.
**Predloženi fix:** `@Size(max = 50)` na svaki List<> param. Možda i `@Min(0)/@Max(...)` gdje semantika dozvoljava (`maxBuildYear`, `maxPersons`, ...).
**Riziko-procjena fixa:** mali, dira validation; UX implicit (legit klijent koji šalje > 50 entries će dobiti 400).
**Status:** OPEN

---

### [F1-064] HIGH security/perf — `YachtController.getYachts` triggera **synkroni external sync prema NauSys/MMK** iz public endpointa
**Lokacija:** `domains/catalouge/controllers/YachtController.kt:158-164, 218-220, 282`
**Detekcija:** statička
**Opis:** Tri public GET endpointa:
- `GET /public/yachts?...&startDate=...&endDate=...` (line 158-164): ako daterange != 7 dana **i** locations je postavljen → `externalSyncService.syncYachtOffers(...)` na **HTTP request thread-u**.
- `GET /public/yachts/{slug}` (line 218-220): ako su date paramovi → external sync.
- `GET /public/yachts/{slug}/offers` (line 282): **uvijek** external sync (bez ikakvog uvjeta).
Posljedice:
1. **Quota exhaustion**: anonimni napadač pošalje 10k requestova → 10k vanjskih NauSys/MMK API poziva → potroši nam dnevnu quota.
2. **Slow response**: legitiman customer čeka na vanjski API u svom request thread-u.
3. **DDoS amplification**: pošaljemo "manje requestova nego što napadač šalje", ali svaki request multiplicira u N vanjskih poziva.
4. **Combined with F1-022/F1-049**: napadač spoof-a IP, pošalje 1 request svakih 10ms iz tisuće "IP-a" → trivijalno potroši external API quota za dan i ne košta ga ništa.
**Posljedica:** financial damage (vjerojatno NauSys/MMK quota imaju cijenu po pozivu), application slowness, vendor relationship problem.
**Predloženi fix:** sync orchestracija ide u BACKGROUND (ScheduledJob ili async task), ne u request thread-u. Ili minimalno: dedupe (ne sync-aj ako je već syncano u zadnje X minute za isti yachtId+daterange). Plus: fronta endpoint trebaju biti za GUEST samo cached read; svaki sync je admin-priveleged operacija.
**Riziko-procjena fixa:** dira hot path search query → ne mijenjam dok ne odobriš.
**Status:** OPEN — eskalacija (Faza 3/4 produbljivanje, ali ovdje ostavljam jer je boundary)

---

### [F1-065] MED security — Document download `Content-Disposition` filename parcijalno saniran (samo `\"`)
**Lokacija:** `domains/reservation/controllers/AdminReservationController.kt:274`, `domains/reservation/controllers/ReservationController.kt:130`
**Detekcija:** statička
**Opis:** `val safeName = doc.filename.replace("\"", "")` — strip-a samo double-quote. Ne uklanja `\r`, `\n`, `;`, `=`, `,`, ili druge HTTP header injection vektore. Filename u DB-u je sanitiziran u upload pathu (`sanitizeFilename` u ReservationDocumentService — vidi line 111-117), ali samo strip-a path separatore. **CRLF nije strip-an**. Ako filename sadrži `file.pdf\r\nSet-Cookie: evil=true`, header injection moguć.
**Posljedica:** HTTP header injection ako filename u DB-u može sadržavati CRLF (samo manualnim DB writeom ili bug-om u uploadu).
**Predloženi fix:** sigurnije: `val safeName = doc.filename.replace(Regex("[\"\\r\\n]"), "_")` ili koristiti `ContentDisposition.attachment().filename(unsafe).build()` (Spring helper).
**Riziko-procjena fixa:** mali.
**Status:** OPEN

---

### [F1-066] LOW exception clarity — `IllegalStateException` baca se s user-facing porukama u public endpointima
**Lokacija:** `PublicUserController.kt:53, 64`, `PublicReservationController.kt:63-65`, `ReservationController.kt:154-156`
**Detekcija:** statička
**Opis:** Public/secured endpoints throw-aju `IllegalStateException("Reservation has no associated user")` ili `IllegalStateException("External reservation status is not OPTION, but ${...}")`. Ove poruke vjerojatno propadaju do klijenta kao 500 + raw poruke (ovisno o ApiErrorHandler-u). Curi internu state info: napadač zna da reservation može biti bez user-a, da postoji "external reservation" koncept, da statusi mogu biti OPTION/RESERVATION/itd.
**Posljedica:** info leak + krivi HTTP status (500 umjesto 4xx).
**Predloženi fix:** uvesti specifične domain exceptione (npr. `ReservationCorruptStateException`) koje globalni handler mapira u 500 + generic poruku. Internu poruku log-aj.
**Riziko-procjena fixa:** mali, ali Faza 5 dependency.
**Status:** OPEN — Faza 5

---

## Sažetak Batch 5 (F1-056 do F1-066)

- **HIGH (3):** F1-056 (cancel non-atomic), F1-057 (set-password rate limit), F1-064 (synkroni external sync iz public)
- **MED (5):** F1-058, F1-059, F1-062, F1-063, F1-065, F1-066
- **LOW (2):** F1-060, F1-061
- **VERIFIKACIJE iz prijašnjih batcheva:**
  - F1-021/F1-034: ReservationDocumentService **NE** koristi FileSystemService (DB BYTEA storage). YachtMutationService DOES koristi FileSystemService — ali subpath se izgrađuje interno, nije direktno user-controlled. Treba još pročitati YachtMutationService.createNewYachtImage da konačno zatvorimo.

---

## Trenutni ukupni progress (post Batch 5)

| Severity | Count | Lista |
|---|---|---|
| **CRIT** | 1 | F1-019 |
| **HIGH** | 14 | F1-002, F1-003, F1-004, F1-005, F1-020, F1-021, F1-022, F1-024, F1-036, F1-037, F1-041, F1-056, F1-057, F1-064 |
| **MED** | 24 | F1-001, F1-006, F1-007, F1-008, F1-009, F1-010, F1-011, F1-023, F1-025, F1-026, F1-028, F1-030, F1-033, F1-034, F1-035 (↓), F1-040, F1-043, F1-044, F1-045, F1-046, F1-053, F1-055, F1-058, F1-059, F1-062, F1-063, F1-065 |
| **LOW** | 16 | F1-012, F1-014, F1-015, F1-016, F1-017, F1-018, F1-027, F1-029, F1-031, F1-032, F1-039 (↓), F1-042, F1-047, F1-048 (↓), F1-050, F1-051, F1-060, F1-061, F1-066 |
| **INFO/POSITIVE** | 4 | F1-052 (Let's Encrypt), F1-054 (Spring Security headers), batch 5 ownership guards, GDPR endpoints |
| **FIXED** | 1 | F1-049 (commit 02532a9) |

**Read pass status:** ~85% dovršen. Ostaje:
- Detaljan read YachtMutationService (path traversal final verification — F1-021 close-out)
- AdminAgencyController, AdminInquiryController, AdminCustomOfferController, AdminCatalogueController (admin-only, manje rizika)
- CustomOfferController, CatalogueController, LocationController, InquiryController, YachtImageController (public, treba IDOR/input check)
- Settings controllers (Public + Admin)
- Invoice controllers
- StripePaymentController, PublicStripePaymentController
- YachtDistributionController (NEW from WIP — high priority za novi kod)

---

## Batch 6 — Final read pass: payment, inquiry, catalogue, image controlleri (2026-05-07)

### F1-021 ZATVORENO — DEMOTE s HIGH na MED
**Verifikacija (YachtMutationService.kt liniji 218, 297):**
- `fileSystemService.savePdfFile(pdfFile, "y-${newYacht.id}")` — `subpath = "y-${yachtId}"`, **NIJE user-controlled** (yacht id je iz DB sequence-a)
- `fileSystemService.saveImage(image, "y-${newYacht.id}")` — isto
- ReservationDocumentService — ne koristi FileSystemService (DB BYTEA storage)
- AdminReservationController, AdminYachtController — uploadi prosljeđuju MultipartFile direktno, subpath se ne dohvaća iz user inputa

**Zaključak:** path traversal kroz subpath/customFilename **NIJE eksploatabilan** preko trenutnih caller-a. F1-021 ostaje kao defensive-improvement (kanonikalizacija u FileSystemService) na razini MED — ne CRIT/HIGH.

F1-034 isto demotira na LOW (defense-in-depth).

---

### Pozitivni nalazi (POSITIVE/INFO)

- **`StripePaymentController`** ima `@PreAuthorize("isAuthenticated()")` na klasi — sve metode auth-zaštićene. ✓
- **YachtMutationService** koristi yacht.id u subpath-u — path traversal eliminated by design. ✓
- **CatalogueController** je read-only, sve metode GET. ✓
- **YachtDistributionController** je read-only (V2 search distribution histograms) — admin notes potpisuju namjere fronti.

---

### [F1-067] HIGH security/privacy — `/public/inquiries/{id}/email-preview` curi sadržaj inquiry email-a anonimno (autor: "before go-live")
**Lokacija:** `domains/catalouge/controllers/InquiryController.kt:46-57`
**Detekcija:** statička
**Opis:** Public endpoint, no auth, vraća rendiran HTML email (broker notification) za bilo koji inquiry ID. Email sadrži:
- Ime, email, phone, poruku kupca
- Yacht detalje
- Brand-info, recipient mailbox

Anonymous napadač iterira ID-eve `1, 2, 3, ...` i čita sadržaj svakog inquiry email-a. **PII leak** za sve inquiriese ikad zaprimljene. **Autor eksplicitno priznaje:** "this should still be moved behind auth (or removed entirely) before go-live. See SESSION_HANDOFF for the migration plan."

**Posljedica:** masovni leak korisničkih PII iz svih inquiriesa.
**Predloženi fix:** ili (a) premjestiti pod `/admin/...` s `SYSTEM_ADMIN`, ili (b) kompletno izbrisati endpoint (SESSION_HANDOFF migration plan).
**Riziko-procjena fixa:** trivijalno; samo ukloni `/public` mount.
**Status:** OPEN — **kandidat za blocker prije produkcije** (autor sam priznaje)

---

### [F1-068] CRIT abuse — `/public/inquiries/{id}/send-test` može slati emailove anonimno bypassiranjem global email-flag-a
**Lokacija:** `domains/catalouge/controllers/InquiryController.kt:66-72`
**Detekcija:** statička
**Opis:** Public endpoint koji **šalje email** za bilo koji inquiry ID, **bypass-irajući `application.email.enabled`** flag (force=true). Anonimni napadač:
- Iterira inquiry ID-eve (1, 2, 3, ...)
- Za svaki šalje email — koji ide na **broker notification mailbox**
- 1000 zahtjeva/min → 1000 emailova/min ide brokerima

Uz to: bypass `EMAIL_ENABLED=false` znači da se ovaj endpoint čak i u dev-u (gdje je email off) izvršava. Stripe-postavljena rate limit-a za SMTP server (Mailgun, smtp2go, Gmail) može postati ratelimited / blacklist-ana — i tada legitimne booking confirmation maileve ne stignu kupcima.

**Autor priznaje:** "Open while the preview endpoint is open — both should be tightened or removed before go-live."

**Posljedica:** email-bombing brokerske inbox-e + DDoS na SMTP provider + potencijalna SMTP blacklist.
**Predloženi fix:** **ukloniti endpoint** ili premjestiti pod `/admin/...` s `SYSTEM_ADMIN`.
**Riziko-procjena fixa:** trivijalno (delete or move).
**Status:** OPEN — **blocker prije produkcije**

---

### [F1-069] HIGH security — `/public/inquiries` POST nema rate limita; brand resolution iz request header-a
**Lokacija:** `domains/catalouge/controllers/InquiryController.kt:28-38`
**Detekcija:** statička
**Opis:** Public POST endpoint, no auth, kreira inquiry. Komentar: "DEPLOY_NOTES section A — captcha at gateway" — captcha plan je tu, implementacija nije. Brand se resolva iz `X-Boat4You-Brand` header-a, ili Origin/Referer fallback (treba pročitati `BrandResolver`). Bez rate-limita anonimni napadač:
- Submitira 10000 inquiry-ja s lažnim email/phone podacima → broker mailbox flooded
- Combined s F1-064 (synkroni external sync na search) → eskalacija
- F1-022 + F1-049 (još uvijek otvoreni za 8080 i XFF) — trivijalno spoofa IP

**Posljedica:** inquiry-spam, broker notification spam, DB write fillable s mock entries.
**Predloženi fix:** rate-limit (proširiti `PublicReservationRateLimiter` na sve `/public/inquiries` paths, ili globalni limiter za `/public/`). Plus: captcha integracija po DEPLOY_NOTES.
**Riziko-procjena fixa:** mali, povezan s F1-003.
**Status:** OPEN

---

### [F1-070] HIGH/MED security — `/public/image/{imageId}` resize bez validacije `width`/`height` → OOM/DoS
**Lokacija:** `domains/catalouge/controllers/YachtImageController.kt:23-33`
**Detekcija:** statička
**Opis:** Public endpoint za resize yacht slike. `width: Int?` i `height: Int?` query parameteri, **bez validacije max vrijednosti**. Anonimni napadač:
- `GET /public/image/123?width=999999&height=999999` → server pokušava decode + resize sliku u 1Tbyte buffer → OutOfMemoryError → JVM dies / GC thrashing
- Paralelizirano (s F1-022 IP spoof) — DoS na cijelu app
- OpenCV (native lib) bez throttlinga može uzeti više memorije nego JVM heap

**Posljedica:** OOM / GC thrash, app down. Trivijalan napad.
**Predloženi fix:** `@RequestParam @Min(1) @Max(2400) width: Int?` (i isti za height). Plus: failsafe u `YachtImageService.resizeImage` da odbaci dimenzije > MAX. Razmotriti caching često tražene resize-eve.
**Riziko-procjena fixa:** mali; dira public boundary.
**Status:** OPEN — kandidat za blocker

---

### [F1-071] MED security — `/secured/payments/stripe/create-checkout-session/{reservationId}` bez ownership checka
**Lokacija:** `domains/reservation/controllers/StripePaymentController.kt:27-48`, `domains/reservation/service/StripePaymentService.kt:46-163`
**Detekcija:** statička
**Opis:** `@PreAuthorize("isAuthenticated()")` osigurava da je korisnik logiran, ali **nigdje se ne provjerava da `reservationId` pripada tom korisniku**. User A logiran može pozvati `/secured/payments/stripe/create-checkout-session/<reservation-of-userB>` i kreirati Stripe Checkout session za rezervaciju user B-a. Posljedice:
- Stripe session koristi user A-inu Stripe customer ako je session vezan na user A-u — money flow zbunjujući
- Alternativno user A "plati" rezervaciju user B-a — legitiman use case (član obitelji?), ali nepredviđen
- IDOR informacija — user A može probati N reservation ID-eva i kreirati session za svaku da otkrije koje postoje + iznose
**Posljedica:** IDOR / cross-tenant payment confusion.
**Predloženi fix:** dodati ownership check u `StripePaymentService.initiatePayment` — `if (reservation.reservationFlow.user.id != currentUserId && !isAdmin) throw AccessDenied`.
**Riziko-procjena fixa:** dira payment service; ne mijenjam dok ne odobriš.
**Status:** OPEN

---

### [F1-072] MED security — `/public/payments/stripe/create-checkout-session/{reservationId}` IDOR enumeration na guest paymentu
**Lokacija:** `domains/reservation/controllers/PublicStripePaymentController.kt:29-46`
**Detekcija:** statička
**Opis:** Public endpoint, no auth. Anonimni napadač iterira `reservationId` i kreira Stripe checkout session za svaku rezervaciju. **Posljedice:**
- Otkriva koje reservation ID-eve postoje (200 odgovor vs error)
- Otkriva iznos plaćanja kroz stripe session response (`redirectUrl` vodi do Stripe stranice s iznosom)
- Trošak na Stripe API-ju (svaki Session.create poziv)
- **Combined s F1-019** (idempotency) — ako napadač dvaput pošalje za istu rezervaciju s istim idempotencyKey, Stripe baca duplicate; bez idempotencyKey, kreira se **dvije sesije** za istu rezervaciju.

Stripe session je kratkotrajan (24h expiry), tako da napadač ne može trajno fingerprintati, ali u tom oknu može mass-create sessions za N rezervacija.
**Posljedica:** info leak, Stripe API trošak, eventualni double-session za F1-019 amplifikaciju.
**Predloženi fix:** rate-limit (kao F1-003); zahtijevati guest-token-ovaj proof-of-ownership (npr. proxy token koji frontend dobije pri kreaciji rezervacije).
**Riziko-procjena fixa:** dira guest payment flow → ne mijenjam dok ne odobriš.
**Status:** OPEN

---

### [F1-073] LOW exception clarity — `IllegalArgumentException("Yacht not found")` umjesto domain exception
**Lokacija:** `domains/catalouge/services/YachtMutationService.kt:125, 128`, `equipmentRepository.findById(...).orElseThrow()` line 135
**Detekcija:** statička
**Opis:** `findById(id).orElseThrow { IllegalArgumentException("Yacht not found") }` — generic exception, vjerojatno mapira u 500 / 400 ovisno o handler-u. Standardni pattern u ostatku koda je domain-specific (ReservationNotExistException, UserDoesNotExistException). Yacht treba `YachtNotExistException` / sl.
**Posljedica:** krivi HTTP status, "exceptions which don't tell programmer enough" pattern.
**Predloženi fix:** kreirati domain exception, koristiti.
**Riziko-procjena fixa:** trivijalno.
**Status:** WAITING-DECISION

---

## Sažetak Batch 6 (F1-067 do F1-073)

- **CRIT (1):** F1-068 (inquiry send-test email bombing)
- **HIGH (3):** F1-067 (inquiry email preview leak), F1-069 (inquiry rate limit), F1-070 (image resize OOM)
- **MED (2):** F1-071, F1-072
- **LOW (1):** F1-073
- **DEMOTES:** F1-021 HIGH→MED (path traversal not exploitable via current callers), F1-034 MED→LOW

---

## TOTAL CUMULATIVE (kraj read pass-a)

| Severity | Count | Lista |
|---|---|---|
| **CRIT** | 2 | F1-019 (Stripe webhook idempotency), F1-068 (inquiry send-test email bombing) |
| **HIGH** | 16 | F1-002, F1-003, F1-004, F1-005, F1-020, F1-022, F1-024, F1-036, F1-037, F1-041, F1-056, F1-057, F1-064, F1-067, F1-069, F1-070 |
| **MED** | 25 | F1-001, F1-006-F1-011, F1-021 (↓), F1-023, F1-025, F1-026, F1-028, F1-030, F1-033, F1-035 (↓), F1-040, F1-043, F1-044, F1-045, F1-046, F1-053, F1-055, F1-058, F1-059, F1-062, F1-063, F1-065, F1-071, F1-072 |
| **LOW** | 18 | F1-012, F1-014, F1-015, F1-016, F1-017, F1-018, F1-027, F1-029, F1-031, F1-032, F1-034 (↓), F1-039 (↓), F1-042, F1-047, F1-048 (↓), F1-050, F1-051, F1-060, F1-061, F1-066, F1-073 |
| **INFO/POSITIVE** | 4 | F1-052, F1-054, ownership guards, GDPR endpoints |
| **FIXED** | 1 | F1-049 (commit 02532a9) |

**Read pass DOVRŠEN.** Ukupno **66 nalaza**. Ostaje samo neka spot-check čitanja koja nisu kritična (Settings, Invoice, AdminAgency / AdminInquiry / AdminCustomOffer / AdminCatalogue — sve admin-only s class-level @PreAuthorize, slabija prioritet).

---

## Pending verification — riješeno (2026-05-08)

- **F1-021/F1-034 — VERIFIED SAFE.** Grep `fileSystemService\.(saveImage|saveFile|savePdfFile|deleteFile|getResourcePath|getResourceFromPath)` pokriva sve callere. Subpath je uvijek `y-${id}` (DB sequence id) ili UUID, customFilename je uvijek `${UUID.randomUUID()}.{webp|pdf}`. `deleteFile` / `getResourcePath` čitaju `pdfUrl` / `image.url` iz DB-a — vrijednosti su pisane prethodno preko `saveFile` koji vraća uuid-baziran path. Path traversal nije eksploatabilan kroz trenutne callere. F1-021 ostaje MED (defense-in-depth: kanonikalizacija u FileSystemService), F1-034 LOW.
- **F1-002 — DJELOMIČNO FIXED u batch-2.** Originalni `application.yml` defaulta `springdoc.api-docs.enabled` i `swagger-ui.enabled` na `${SWAGGER_ENDPOINT_ENABLED:true}` — ako env nije postavljen, swagger ON. `application-prod.yml` sad eksplicitno overrida na `false` (commit `2e451cc`). Static OpenAPI YAML datoteke (`/boat4you_ws_*.openapi.yaml`, `/nausys_v6.openapi.yaml`, `/mmk_api_2_1_3.json`) ostaju `permitAll()` u Spring Security configu — to nije pokriveno trivijalnim fix-om jer dira security policy. **F1-002 ostaje OPEN** za HIGH dio (security policy promjena).
- **F1-003 — DEFERRED-Faza7 (CONFIRMED MISSING).** README_PROD.md ne spominje rate-limiter na gateway-u. Aplikacija ima samo `PublicReservationRateLimiter`, ne pokriva login/register/reset. **Verifikacija na VM2 (2026-05-08):** `sudo nginx -T | grep -E "limit_req|limit_conn"` vraća **prazno** — nijedna rate-limit zona nije definirana. Confirmed missing. Po dogovoru s korisnikom, fix se primjenjuje u Fazi 7 nginx batch-u (svi nginx changes u jednom deploy window-u).
- **F1-010 — VERIFIED.** `ApiErrorHandler.handleInternalLoginException` (linije 274-291) vraća `HTTP 403` s **različitim error code-om** za svaki `InternalLoginException.Type`: `BAD_CREDENTIALS`, `USERS_INVITE_NOT_ACCEPTED`, `LOGIN_ATTEMPTS_EXCEEDED`. Anonimni napadač razlikuje "email ne postoji" od "invite čeka prihvat" od "lockout". Email enumeration **potvrđen**. F1-010 ostaje MED/OPEN za fix u Fazi 5 (cross-cutting exception mapping); fix dira frontend (jedinstveni error code → frontend treba dvije nove poruke).
- **F1-022 — DEFERRED-Faza7 (CONFIRMED UNSAFE).** **Verifikacija na VM2 (2026-05-08):** `sudo nginx -T | grep proxy_set_header` pokazuje `proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;` — to je točno scenarij C iz triage-a. `$proxy_add_x_forwarded_for` čuva klijentom-poslan XFF i samo nadodaje stvarni `$remote_addr` na kraj. App-side `PublicReservationRateLimiter` čita prvi segment XFF-a → trustova fake IP. Anonymous attacker rotira `X-Forwarded-For: <fake>` između requestova i bypass-a per-IP rate-limit. Confirmed unsafe. Fix u Fazi 7 nginx batch-u (zamjena s `$remote_addr`).

---

## Batch 2 — trivial fixes (commit-i 2e451cc → 274dc5a, 2026-05-08)

| ID | Severity | Naslov | Commit |
|---|---|---|---|
| F1-002 | HIGH | (djelomično) Disable swagger by default in prod profile (yml side) | `2e451cc` |
| F1-016 | LOW | Drop hardcoded swagger server hosts | `d8feb1a` |
| F1-017 | LOW | Real OpenAPI title/description | `d8feb1a` |
| F1-042 | LOW | POST for state-changing /admin/mmk + /admin/nausys sync triggers | `c7e1c2e` |
| F1-043 | MED | Rolling LocalDate.now() for MMK offer2 instead of hardcoded 2025-08-09 | `c7e1c2e` |
| F1-044 | MED | TestJobController → AdminJobController, drop dead code | `274dc5a` |

---

## Phase gate verification (2026-05-08)

| Provjera | Rezultat | Komentar |
|---|---|---|
| `./gradlew compileKotlin` | ✓ PASS (45s) | Niti jedan compile error nakon batch-2 izmjena. |
| `./gradlew detekt` | ⚠ FAIL (291 weighted issues) | **Pre-existing baseline** — nijedan issue u datotekama batch-2; sve LongMethod / ReturnCount / UnusedProperty / UseRequire smell-ovi po service / controller hijerarhiji. Tracking u Faza 5 (cross-cutting code quality) i Faza 6 (repo hygiene). |
| `./gradlew test` | ⚠ FAIL (29/103 failed) | **Pre-existing baseline** — sve failure-e u `ReservationPaymentPhasesServiceTest`. Service je promijenjen (commit `6f11eef`) bez ažuriranja testova. Vidi novi nalaz F1-074 niže. Nije batch-2 regresija. |

Phase gate odluka: **PASS s dvije dokumentirane iznimke** (detekt baseline, test baseline). Nijedna iznimka nije nova; obje su pre-existing stanje koje se prati u kasnijim fazama.

---

## Novi nalaz iz phase gate-a

### [F1-074] LOW code health — `ReservationPaymentPhasesServiceTest` razilazi se s logikom servisa
**Lokacija:** `src/test/kotlin/hr/workspace/boat4you/domains/reservation/service/ReservationPaymentPhasesServiceTest.kt`, `src/main/kotlin/hr/workspace/boat4you/domains/reservation/service/ReservationPaymentPhasesService.kt`
**Detekcija:** dinamička (gradle test)
**Opis:** 29/103 unit testova faila. Test "one month plus a day until startDate" očekuje 50/50 split (2 phase) za `now=2025-01-01`, `startDate=2025-02-02`. Service rule A (linija 51-54) sad vraća **100% sada** za sve "charter within 2 months", što daje 1 phase. Service je prepravljen u commit `6f11eef checkpoint: pre filter V2 backend distribution endpoint (25.4.2026)` ili kasnije; testovi opisuju staru poslovnu logiku. Posljedica: cijela `ReservationPaymentPhasesServiceTest` klasa nepouzdana — bilo kao regression detector, bilo kao dokumentacija pravila.
**Posljedica:** test suite ne hvata regresione na payment-phase logici (jedna od kompleksnijih business rules — A/B/C iz docs comment-a). Ako se ponovno mijenja logika rasporeda plaćanja, ništa ne pucne kao indikator.
**Predloženi fix:** ili (a) ažurirati testove na trenutnu A/B/C logiku iz dokumentacije servisa, ili (b) ako je test reflektovao pravu poslovnu logiku → service ima bug; pitati Maria koja od dvije logike je željena. Riziko-procjena: dira business rules (pre-payment timeline) → eksplicitan biz signoff prije fix-a.
**Status:** OPEN — eskalacija Maria (poslovna odluka) prije bilo kakvog fix-a.

---

## Phase 1 closure summary

**Read pass:** dovršen (66 nalaza pri kraju read pass-a + 1 novi iz phase gate-a = 67).
**Trivial fix-evi:** 14 batch-1 (commit-i `7cc3b09`-`b169ff1`) + 6 batch-2 (commit-i `2e451cc`-`274dc5a`) = **20 fix-eva commit-ano**.
**OPEN nalaza pri zatvaranju:** 47 (2 CRIT + 16 HIGH + 19 MED + 10 LOW). Vidi `REGISTER.md` za točan breakdown.
**Pending za phase 6 (repo hygiene):** F1-050, F1-051 (nginx config — out-of-repo, eskalacija ops-u).
**Pending za phase 5 (cross-cutting):** F1-010 (uniformne login error responses), detekt baseline cleanup, F1-074 (test divergence).
**Pending blocker odluka:** F1-019 (Stripe webhook idempotency), F1-068 (inquiry send-test email bombing) — autor sam priznaje "blocker before go-live"; ne fixaju se u Fazi 1 jer nisu trivijalni.
**Nginx ops batch — DEFERRED-Faza7:** F1-003 (rate-limit), F1-022 (XFF strip), F1-048 (TLS), F1-050 (server_tokens), F1-051 (default `_`-host), F1-053 (HSTS). Korisnik je 2026-05-08 verificirao F1-003 (confirmed missing) i F1-022 (confirmed scenario C) na VM2. Sve nginx promjene idu u jednom commit window-u u Fazi 7. **Faza 2 može krenuti odmah.**
