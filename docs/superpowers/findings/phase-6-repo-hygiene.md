# Faza 6 — Repo hygiene + deploy artefakti

**Status:** in progress (inventory + read pass)
**Datum starta:** 2026-05-11
**Scope per spec (`2026-05-07-boat4you-prod-review-design.md:138-156`):**

- `Dockerfile` (root user, multi-stage, image size, health check)
- `docker-compose.yml` (default secreti, exposed portovi) + `model/docker-compose.yml`
- `.env`, `.env.example`, `.gitignore`
- Repo cruft: `__MACOSX/`, `.DS_Store`, `boat4you-ws-perf-update.tar.gz`
- `scripts/*` — deploy + ops scripts
- `README.md` — hardkodirani test useri "123456" — gdje su seedani?
- `README_PROD.md` — deployment proces, secret handling
- systemd service (verify deploy artefakt, out-of-repo)
- `build.gradle.kts` — `ignoreFailures = true` na ktlint, detekt baseline
- `detekt_config.yml`
- `boat4you_selfsigned.p12` u `src/main/resources/` — privatni ključ u Git-u (F1-035 family)

---

## Inventory

### Container artefakti

| Path | Lines |
|---|---|
| `Dockerfile` | 30 |
| `docker-compose.yml` | 61 |
| `model/docker-compose.yml` | 22 |

### Environment configs

| Path | Lines |
|---|---|
| `.env` | 73 |
| `.env.example` | 53 |

### Documentation

| Path | Lines |
|---|---|
| `README.md` | 111 |
| `README_PROD.md` | 169 |

### Build configuration

| Path | Lines |
|---|---|
| `build.gradle.kts` | 293 |
| `detekt_config.yml` | 24 |
| `.gitignore` | 61 |

### Scripts

| Path | Type |
|---|---|
| `scripts/model_cleanup/clean_models.sql` | SQL ops script |
| `scripts/model_cleanup/models_to_fix.csv` | data input |

### Binaries u repo-u

| Path | Size | Note |
|---|---|---|
| `boat4you-ws-perf-update.tar.gz` | **298 MB** | Spec spomenuo — repo cruft |
| `src/main/resources/boat4you_selfsigned.p12` | 1192 B | Private key (F1-035 already filed HIGH) |

Total docs / config text: ~897 lines + 298 MB binary cruft + 1 binary keystore.

---

## Spec questions to answer

Per design 138-156:
1. **Dockerfile** — root user vs non-root? multi-stage build vs single? image size?  health check?
2. **docker-compose.yml** — default passwords / secrets exposed? ports exposed to host beyond needed?
3. **`.env`** — does it have real secrets? Should be in `.gitignore` (verify)
4. **`.env.example`** — placeholder values match real env-var names? Up-to-date?
5. **`.gitignore`** — covers IDE files, build outputs, secrets, OS cruft (`__MACOSX/`, `.DS_Store`)?
6. **`boat4you-ws-perf-update.tar.gz` 298 MB u repo root-u** — what is it? Should be in .gitignore.
7. **`README.md`** — spec spomenuo "hardkodirani test useri '123456'" — gdje? F2-043 connection.
8. **`README_PROD.md`** — deployment proces complete? Secrets ne smije biti u tekstu.
9. **systemd service** — out-of-repo, ali README_PROD trebao bi dokumentirati.
10. **`build.gradle.kts`** — `ignoreFailures = true` na ktlint? Detekt baseline strategy?
11. **`detekt_config.yml`** — what rules disabled? Why?
12. **`boat4you_selfsigned.p12`** — F1-035 already filed; verify expiration date + presence consequences.

---

## Workflow Faze 6

Plan: **single batch** (~12 fajlova, ~900 linija text + 2 binarna artefakta). Manjeg obima nego Phase 3/5; jedan pass dovoljan. Triage live u ovaj file kao `[F6-NNN]` nalazi.

---

## Findings

---

## Batch 1 — Repo hygiene + deploy artefakti (2026-05-11)

### [F6-001] HIGH container security — `Dockerfile` ne specifikuje `USER` direktivu; container radi kao root
**Lokacija:** `Dockerfile` (entire file, 30 lines)
**Detekcija:** statička
**Opis:**
```dockerfile
FROM eclipse-temurin:21-jre
...
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar
COPY --from=build /app/src/main/resources/boat4you_selfsigned.p12 keystore.p12
EXPOSE 8443
ENTRYPOINT ["java", "-Dserver.ssl.key-store=file:/app/keystore.p12", "-jar", "app.jar"]
```

**Nema `USER nobody:nogroup`** (ili `USER appuser`) direktive — container po defaultu radi kao **root unutar container-a**. Industry-standard: non-root user (UID > 1000) za defense-in-depth.

**Posljedice container-as-root:**
- Ako attacker dobije RCE preko app vulnerability (npr. F1-070 image OOM, F4-009 native memory bug + heap spray), shell unutar container-a ima root privileges
- Container escape vulnerabilities (CVE-2019-5736 runc, etc.) — root unutar = root na host-u
- File system permissions u container-u nemaju layer of protection
- Linux capabilities su sve aktivne unless explicit `--cap-drop`

**Plus** Dockerfile `COPY` p12 keystore u image (line 26) — F1-035 sibling. Keystore would benefit from being mounted as secret volume umjesto baked-in.

Compare s README_PROD.md systemd setup: VM2 runs as `User=cusma2`, VM3 as `User=cusma3`. **Production deploy NIJE preko Dockerfile-a** (sistemd unit deploys JAR direktno). Dockerfile je vjerojatno dev / one-off purpose. Verify if container ever ships to prod.
**Posljedica:** ako container se ikad pokrene u prod-env-u, root-by-default je security risk. Plus dev cycle: developer's machine running container as root affects host filesystem permissions on bind-mounted volumes.
**Predloženi fix:**
```dockerfile
FROM eclipse-temurin:21-jre

RUN apt-get update && \
    apt-get install -y --no-install-recommends \
        libstdc++6 libc6 && \
    rm -rf /var/lib/apt/lists/* && \
    groupadd -r -g 1001 boat4you && \
    useradd -r -u 1001 -g boat4you boat4you

WORKDIR /app
COPY --from=build --chown=boat4you:boat4you /app/build/libs/*.jar app.jar
COPY --from=build --chown=boat4you:boat4you /app/src/main/resources/boat4you_selfsigned.p12 keystore.p12

USER boat4you:boat4you

EXPOSE 8443
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD curl -fsk https://localhost:8443/actuator/health || exit 1

ENTRYPOINT ["java", "-Dserver.ssl.key-store=file:/app/keystore.p12", "-jar", "app.jar"]
```

Plus `--no-install-recommends` smanjuje image size. Plus HEALTHCHECK (F6-010 covers).
**Status:** OPEN — HIGH (if container ships to prod) / MED (if dev-only) — verify Mario intent

---

### [F6-002] HIGH security — `README.md:79-97` dokumentira table test user-a sa SYSTEM_ADMIN role + password "123456"; combined s F2-043 CRIT = documented prod admin takeover path
**Lokacija:** `README.md:79-97`
**Detekcija:** statička
**Opis:** README.md ima sekciju:
```markdown
## Hardcoded test users:

|          email           | password |       roles       |
|:------------------------:|:--------:|:-----------------:|
| pskvorcevic@workspace.hr |  123456  |      MANAGER      |
| that@workspace.hr        |  123456  |   SYSTEM_ADMIN    |
| bhokman@workspace.hr     |  123456  |       USER        |
| vcutic@workspace.hr      |  123456  |      MANAGER      |
| astekl@workspace.hr      |  123456  |   SYSTEM_ADMIN    |
...
```

**5 SYSTEM_ADMIN accounts s password "123456"** listed in public-repo README.

Combined s **F2-043 CRIT** (V9_xx test data Flyway migracije se izvršavaju u prod-u bez `FLYWAY_TARGET_VERSION` env override):
1. F2-043: V9_xx kreira ove account-e u **prod DB** s istim bcrypt-hash-em za sve (cijela password phrase je ista — vjerojatno "123456")
2. README ovdje **otkriva password = "123456"**
3. Attacker pročita README → login kao `that@workspace.hr` / `123456` → **instant SYSTEM_ADMIN access u prod-u**

Attack chain je **trivijalan, javno dokumentiran, i guaranteed-to-work** ako:
- V9_xx executes u prod-u (F2-043 verify still pending)
- Login endpoint ne lock-uje ovaj specific account
- BCrypt hash `$2a$10$CQd0ZdAerBaOmw5Akg2dpufPwQYkadFtGtTuz9nBLf3eDyqkWE/Bu` reverses to `"123456"`

**Verify last point:** generate bcrypt hash of `"123456"` s `$2a$10$CQd0ZdAerBaO...` salt (first 22 chars of hash are salt-encoded) → compare. Computational cost ~1s. **HAZARD: dokaz attack-a je trivijalan run.**

Plus: even bez F2-043 fix, ako test data ever populated prod DB historically (manual sync from dev → prod migration test), ti account-i mogli biti tamo.
**Posljedica:** **prod admin takeover documented in repo**. Highest-impact single finding identified u review-u zbog kombinacije:
- F2-043 CRIT (V9_xx runs u prod-u)
- F6-002 dokumentira password
- Single login attempt → full SYSTEM_ADMIN

Plus: Ovo je **public repo na GitHub-u** (https://github.com/Boat4You-Organization/boat4you-backend). README je javni file. Anyone pretražuje GitHub za "boat4you" → vidi README → vidi password table.
**Predloženi fix:** dvije razine:
1. **Immediate:** ukloniti tablicu iz README.md. Replace s:
   ```markdown
   ## Test users
   Test users are seeded by `db/migration/V9_00__insert_test_data.sql`
   (dev profile only — never runs in prod per FLYWAY_TARGET_VERSION).
   Local dev password is documented in `docs/local-dev/test-users.md`
   (gitignored).
   ```
2. **Combined s F2-043 fix:** verify `FLYWAY_TARGET_VERSION=1.90` u prod env; verify rotation of any account that has ever been u prod DB
**Riziko-procjena fixa:** trivijalan README delete. Plus internal docs migration.
**Status:** OPEN — **HIGH (pair s F2-043 CRIT)**, single-glance attack vector

---

### [F6-003] HIGH repo bloat — `boat4you-ws-perf-update.tar.gz` 298 MB u repo root; svrha nepoznata, verify (potencijalno PII u backup)
**Lokacija:** `boat4you-ws-perf-update.tar.gz` (repo root)
**Detekcija:** glob + ls -la
**Opis:** 298 MB tar.gz fajl u repo root-u. Spec explicit listed kao "repo cruft" za reviewer. `git ls-files` confirms **NIJE tracked** (`.gitignore:50 *.tar.gz` covers). Postoji samo lokalno na ovoj dev-machini.

Pitanja:
- **Što je sadržaj?** Tar.gz mogao biti:
  - Old prod backup snapshot (CRIT: PII would leak if dev machine compromised)
  - Old build artifact (just bloat)
  - Perf-test dataset
  - Code snapshot od prijašnje verzije
- **Tko ga je stvorio?** Filename "boat4you-ws-perf-update" implicira **perf update / deploy artifact**. Datum 11 Apr 2026 17:40 (per ls -la).
- **Da li bi se trebao tracked u Git-u?** Per `.gitignore:50` `*.tar.gz` ignored — answer NO.

Worst case scenario: ako je production DB backup, sadrži:
- User table (emails, bcrypt hashes — even if hash is bcrypt-10, repeated attack)
- Reservation flow s customer details
- Stripe session IDs / payment intent references
- ServiceCall audit s NauSys/MMK responses (PII)

GDPR Article 32: "security of processing" includes "ability to ensure the ongoing confidentiality... of processing systems". 298 MB blob na dev machini može sadržavati PII without protection.
**Posljedica:** unknown — depends on contents. **Verify before continuing.**
**Predloženi fix:**
1. `tar -tzf boat4you-ws-perf-update.tar.gz | head -50` → see what's inside
2. Ako PII / backup → **secure delete** (`shred -uvz` na Linux, ili Windows secure-erase) + add `.tar.gz` to per-machine ops checklist
3. Ako bloat → trivial delete

Plus: dodati `.gitignore-like` audit u CI / pre-commit hook da detect large binary files u repo working tree.
**Status:** OPEN — VERIFY contents before classification; HIGH ako sadrži PII, LOW ako bloat

---

### [F6-004] MED operational — `.env` (lokalna, NOT tracked) sadrži PROD partner credentials (NauSys/MMK live tokens, real mail password) — dev-machine compromise = secret leak
**Lokacija:** `.env:49-55` (NauSys/MMK), `:28-32` (mail), `:4` (JWT key)
**Detekcija:** statička, plus `git ls-files .env` confirms not tracked
**Opis:** Dev `.env` fajl sadrži:
- `NAUSYS_USERNAME=rest@EURCU` + `NAUSYS_PASSWORD=C93r5cMK` — comment line 43-45: "Production paid account (hits live NauSys data). Kept in local .env so local dev mirrors prod behaviour."
- `MMK_TOKEN=63d-209555d805901...` (~130 chars) — looks like real MMK production bearer
- `MAIL_PASSWORD=1IrisRobert53551982Jadreemail` — real boat4you mail-server password (per cPanel comment line 25-27)
- `JWT_SECRET_KEY=270fa8d16a50c17d96143ebc14bc8ea5921c557cff403d043c9a0166442f6c55` — 256-bit hex key

Stripe keys ARE test mode (`sk_test_...`, `whsec_dsy...`) — OK.

**Confirmation: `.env` is NOT tracked** (gitignore line 53 + `git ls-files .env` returns nothing). Secrets stay local. ✓

**Ali risk-vektor postoji:**
1. **Dev machine breach** — laptop loss, malware, supply chain attack → all secrets immediately exposed
2. **Backup leak** — dev's iCloud / Dropbox / Time Machine backup of home dir includes `.env`
3. **Inadvertent commit** — `git add -f .env` ili `.gitignore` change ne briše history; once committed, leaked forever
4. **Employee turnover** — ex-dev keeps `.env` after departure; partner credentials must be rotated when ANY dev leaves

**Plus comment** line 44 explicit-akceptira ovaj risk: "Kept in local .env so local dev mirrors prod behaviour."

This is operational policy, not bug — but worth flagging za security awareness.
**Posljedica:** ovisi o dev-machine hygiene. Combined s GDPR Article 32, prod credentials should rotate on regular schedule + immediate rotation on dev turnover.
**Predloženi fix:** dvije razine:
- (a) **Use staging-equivalent partner credentials** za local dev. NauSys/MMK obično omogućuju test-mode accounts s sandbox data. Verify s NauSys/MMK whether they have staging environments. If yes, dev koristi staging creds — leak still bad but limited blast radius.
- (b) **Secrets-management tool** (1Password CLI, Doppler, AWS Secrets Manager) — dev `.env` regenerates from vault on-demand, ne lives on disk.

(a) je pragmatic — depends on partner support.

Plus: ops checklist — partner credentials rotate quarterly + on dev departure.
**Status:** OPEN — operational policy (verify s Mario)

---

### [F6-005] MED docs drift — `.env.example` references removed Viva integration (V1_55), outdated `SERVER_HOST_PUBLIC` port, inconsistent env var naming vs application-prod.yml
**Lokacija:** `.env.example:39-48, 52, 19-23`
**Detekcija:** statička
**Opis:** Three sub-issues:

1. **Viva integration removed u V1_55 (Phase 2 review),** ali `.env.example:39-48` joj i dalje lista env vars:
```env
# --- Viva Payments ---
VIVA_OAUTH_URL=https://demo-accounts.vivapayments.com/connect/token
VIVA_API_URL=https://demo-api.vivapayments.com
VIVA_CHECKOUT_URL=https://demo.vivapayments.com/web/checkout
VIVA_SOURCE_CODE=
VIVA_MERCHANT_ID=
VIVA_WEBHOOK_VERIFICATION_API_KEY=
VIVA_WEBHOOK_VERIFICATION_BASE_URL=https://demo.vivapayments.com
VIVA_CLIENT_ID=
VIVA_CLIENT_SECRET=
```

2. **`SERVER_HOST_PUBLIC=http://localhost:5173`** (line 52) — Vite's port. Real `.env:71` overrides to `localhost:3000` (Next.js). Per `.env` comment line 68-69: "Next.js defaults to 3000. The prior value `:5173` was Vite's default and caused Stripe to redirect users to a dead port after payment."

3. **Mail env var naming inconsistent:**
   - `.env.example:20-23` uses `MAIL_SERVER_HOST/PORT/USERNAME/PASSWORD` (matches application-prod.yml)
   - Real `.env:28-31` uses `MAIL_HOST/PORT/USERNAME/PASSWORD` (matches application.yml root)
   - **Two different conventions for same purpose** — F5-014 sibling

Plus: `.env.example` doesn't reflect actual real `.env` structure (missing JWT_SECRET_KEY guidance? no — line 4 covers). But Viva block je ostala iza.
**Posljedica:** new developer copies `.env.example` → sets up confusing env vars / fills in irrelevant Viva. Wastes 30 min troubleshooting.
**Predloženi fix:**
- Remove Viva block from `.env.example`
- Update `SERVER_HOST_PUBLIC` to `localhost:3000`
- Unify mail env var naming s real `.env` + application yml hierarchy

Plus: add note u `.env.example` da `.env` should be created via `cp .env.example .env` + fill in real values.
**Status:** WAITING-DECISION (trivijalan docs cleanup, pair s F5-014 mail naming unification)

---

### [F6-006] MED config-safety — `docker-compose.yml:22` `SSL_KEYSTORE_PASSWORD=${SSL_KEYSTORE_PASSWORD:-changeme}` placeholder default `changeme`; F5-014 family
**Lokacija:** `docker-compose.yml:22`
**Detekcija:** statička
**Opis:**
```yaml
- SSL_KEYSTORE_PASSWORD=${SSL_KEYSTORE_PASSWORD:-changeme}
```

If `SSL_KEYSTORE_PASSWORD` env var nije postavljen, container starts s `SSL_KEYSTORE_PASSWORD=changeme`. App tries to open keystore s krivom passwordom → JKS/PKCS12 password mismatch exception → app startup fail (silent, only u logs).

Plus: F5-014 family — F1-046 already fixed application.yml `SSL_KEYSTORE_PASSWORD` to `:?required`, but docker-compose.yml passes `${VAR:-default}` (bash-like syntax) → if env not set, **container** sees `changeme` and passes that to Spring. Spring's `${SSL_KEYSTORE_PASSWORD:?required}` then sees `changeme` (a value!) and passes validation → fails to open keystore at runtime.

Other docker-compose env vars use mixed pattern:
- Line 12-16: `${DB_USER:?DB_USER required}` ✓ (proper fail-fast in compose)
- Line 21: `${JWT_SECRET_KEY:?JWT_SECRET_KEY required}` ✓ (proper)
- Line 22: `${SSL_KEYSTORE_PASSWORD:-changeme}` ✗ (placeholder default)
- Line 23-25: `${NAUSYS_USERNAME:-}` etc — empty defaults

Inconsistent application of fail-fast pattern.
**Posljedica:** dev container starts s krivim keystore password — startup fail at runtime, harder to debug than fail-fast.
**Predloženi fix:** all critical secrets use `:?` fail-fast pattern u docker-compose.yml:
```yaml
- SSL_KEYSTORE_PASSWORD=${SSL_KEYSTORE_PASSWORD:?SSL_KEYSTORE_PASSWORD required}
- NAUSYS_USERNAME=${NAUSYS_USERNAME:?NAUSYS_USERNAME required for sync}
- MMK_TOKEN=${MMK_TOKEN:?MMK_TOKEN required for sync}
- STRIPE_SECRET_KEY=${STRIPE_SECRET_KEY:?STRIPE_SECRET_KEY required}
```

Or for partner / Stripe creds where dev may want to skip — explicit comment that empty value disables that integration:
```yaml
# Optional in dev — empty disables NauSys integration (no sync, no booking)
- NAUSYS_USERNAME=${NAUSYS_USERNAME:-}
```
**Status:** OPEN — pair s F5-013/F5-014/F5-016 single yml-hardening commit

---

### [F6-007] MED build hygiene — `build.gradle.kts:292 ignoreFailures = true` na ktlint; code-quality failures **don't fail build**
**Lokacija:** `build.gradle.kts:291-293`
**Detekcija:** statička
**Opis:**
```kotlin
configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
    ignoreFailures = true
}
```

Ktlint format violations **don't break build**. Developer hits `./gradlew build` → green even if 200 style violations. **Spec explicit-listed kao concern** (section 150: "build.gradle.kts — `ignoreFailures = true` na ktlint").

Plus: combined s detekt baseline strategy (`buildUponDefaultConfig = true`, line 129) i baseline file probably gitignored — code quality drift over time je accepted.

**Why this matters:**
- New violations don't surface in PR-review CI signal
- Style inconsistencies accumulate (Phase 5 F5-007 anti-pattern logger format je detekt-detectable but not enforced)
- Detekt 291 baseline issues (per memory) — actually means there ARE 291 weighted issues but they're ignored

**Industry standard:** fail build on new ktlint violations + maintain pre-existing as baseline; introduce auto-format step (`./gradlew ktlintFormat`) u pre-commit hook.
**Posljedica:** code quality slow drift. Pre-prod nije akutni problem.
**Predloženi fix:**
```kotlin
configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
    ignoreFailures = false  // enforce
}
```
Plus run `./gradlew ktlintFormat` to auto-fix existing violations, commit, then enable enforcement. Phase 6 cleanup commit.
**Status:** WAITING-DECISION (trivijalan toggle, but format-and-commit step needed first)

---

### [F6-008] LOW docs drift — `README.md:100-112` documents OLD `GET /nausys/sync` endpoints; F1-042 fix already applied POST methods
**Lokacija:** `README.md:100-112`
**Detekcija:** statička
**Opis:** README data-sync section:
```markdown
For development purposes we have exposed following GET endpoints to sync data from MMK and NauSYS:
Nausys:
 - https://localhost:8443/nausys/agencies
 - https://localhost:8443/nausys/sync
 - ...

MMK:
 - https://localhost:8443/mmk/sync
 - ...
```

Lists GET endpoints. But:
- F1-042 already fix POST methods + `@PostMapping` on MmkSyncController + NausysSyncController
- New paths są `/admin/nausys/sync` + `/admin/mmk/sync` (per Phase 3 F3-039 INFO) — not bare `/nausys` prefix
- Plus `@PreAuthorize("hasRole('SYSTEM_ADMIN')")` u F1-042 + `@Profile("data-sync")` — endpoints not accessible via curl without auth

README documentation is outdated; misleads developers to try old paths.
**Posljedica:** developer wastes time. Pre-prod nije problem.
**Predloženi fix:** update README:
```markdown
## Data sync in development

Admin sync triggers require SYSTEM_ADMIN role + data-sync profile.
POST to:
- /admin/nausys/sync, /admin/nausys/yachts, /admin/nausys/offer, /admin/nausys/availability
- /admin/mmk/sync, /admin/mmk/yachts, /admin/mmk/offer, /admin/mmk/availability

curl example:
  curl -X POST -H "Authorization: Bearer $JWT" https://localhost:8443/admin/mmk/sync
```
**Status:** WAITING-DECISION (trivijalan README update)

---

### [F6-009] LOW detekt config — multiple style rules disabled (SwallowedException, MagicNumber, ThrowsCount); allow patterns flagged in earlier phases
**Lokacija:** `detekt_config.yml:1-24`
**Detekcija:** statička
**Opis:**
```yaml
style:
  MaxLineLength:
    maxLineLength: 180
  ThrowsCount:
    active: false      # <-- disabled
  MagicNumber:
    active: false      # <-- disabled
exceptions:
  SwallowedException:
    active: false      # <-- disabled
complexity:
  TooManyFunctions:
    ignorePrivate: true
  LongParameterList:
    constructorThreshold: 15  # <-- 15 params allowed!
```

Disabled rules let earlier-phase findings slip through detekt:
- **`SwallowedException: active: false`** — F3-011 (per-agency loop swallow), F3-031 (SMTP failure swallow), F2-038 swallows wouldn't trigger
- **`MagicNumber: active: false`** — magic numbers OK; F1-004 (6-char password) was detekt-flag-able
- **`LongParameterList constructorThreshold: 15` + comment "I'm sorry future me"** — author self-aware tech debt accepted

Plus `MaxLineLength: 180` is generous (industry 100-120).

Per ktlint vs detekt: ktlint formats; detekt analyzes. Disabled detekt rules = accepted tech debt categories.
**Posljedica:** code quality drift. Pre-prod nije problem.
**Predloženi fix:** consider enabling SwallowedException → fixes F3-011/F3-031 detection. Other rules per team preference.
**Status:** WAITING-DECISION (style choice; Phase 6 tracking)

---

### [F6-010] LOW container — Dockerfile no HEALTHCHECK directive; container orchestration can't detect unhealthy process
**Lokacija:** `Dockerfile`
**Detekcija:** statička
**Opis:** No HEALTHCHECK. If app process zombies (Spring Boot can hang without crash on certain failure modes — e.g. F3-001 partner timeout indefinite), Docker / Kubernetes can't restart. Container shows healthy but app unresponsive.

Best practice:
```dockerfile
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD curl -fsk https://localhost:8443/actuator/health || exit 1
```

Requires `spring-boot-starter-actuator` dep + `/actuator/health` endpoint exposed.
**Predloženi fix:** add HEALTHCHECK (covered in F6-001 fix). Plus: enable Spring Boot Actuator s minimal endpoints — `health` + `metrics` only, behind admin auth.
**Status:** OPEN — pair s F6-001 Dockerfile fix

---

### [F6-011] LOW systemd — `README_PROD.md` systemd ExecStart missing `-XX:+HeapDumpOnOutOfMemoryError`
**Lokacija:** `README_PROD.md:43, 68`
**Detekcija:** statička
**Opis:**
```ini
ExecStart=java -Xmx4096m -jar /home/cusma2/boat4you/webservice.jar       # VM2
ExecStart=java -Xmx6144m -jar /home/cusma3/boat4you/webservice.jar       # VM3
```

Heap size set. **No OOM dump flag.** Java app dies on OutOfMemoryError without heap dump → can't post-mortem analyze cause. Standard prod JVM flags:
```ini
ExecStart=java \
  -Xmx4096m \
  -XX:+HeapDumpOnOutOfMemoryError \
  -XX:HeapDumpPath=/home/cusma2/boat4you/logs/heap-dump-%p.hprof \
  -XX:+ExitOnOutOfMemoryError \
  -XX:+UseG1GC \
  -jar /home/cusma2/boat4you/webservice.jar
```

`-XX:+ExitOnOutOfMemoryError` ensures systemd restarts app on OOM (Java otherwise may continue in zombie state).

Combined s F4-009 (ImageUtils native memory leak ~1.5GB/day) — when OOM hits, current setup has no dump for forensics.
**Predloženi fix:** update systemd ExecStart u README_PROD + actual systemd unit on VM2/VM3 (ops-side).
**Riziko-procjena fixa:** trivial config change.
**Status:** OPEN — pre-prod ops checklist

---

### [F6-012] LOW dev config — `model/docker-compose.yml` hardcodes `POSTGRES_USER/PASSWORD=boat4you_owner` in tracked file
**Lokacija:** `model/docker-compose.yml:8-9`
**Detekcija:** statička
**Opis:**
```yaml
environment:
  POSTGRES_USER: boat4you_owner
  POSTGRES_PASSWORD: boat4you_owner
  POSTGRES_DB: boat4you_db
```

Hardcoded creds u tracked file (commit history visible). Dev-only file (for local DB setup), ali "boat4you_owner" / "boat4you_owner" credential pair is documented for anyone cloning repo.

Plus: same as `.env:15-16` `FLYWAY_USER=boat4you_owner` / `FLYWAY_PASSWORD=boat4you_owner` — consistent dev pattern.

**Risk-only-if** this dev DB ever gets exposed (port 5434 mapped to host per line 13). If dev machine has external access (corporate VPN, public WiFi) → port-scan attacker reaches Postgres → known creds → DB access.

Mitigation: dev DB exists in container, port mapping localhost-only typically. Verify Docker port binding (`127.0.0.1:5434:5432` would limit; just `5434:5432` exposes all interfaces).
**Posljedica:** small dev risk. Pre-prod nije problem.
**Predloženi fix:** consider env-var override:
```yaml
environment:
  POSTGRES_USER: ${POSTGRES_USER:-boat4you_owner}
  POSTGRES_PASSWORD: ${POSTGRES_PASSWORD:-boat4you_owner}
ports:
  - "127.0.0.1:5434:5432"  # bind localhost-only
```
**Status:** WAITING-DECISION (trivijalan)

---

### [F6-013] INFO positive — `.gitignore` properly excludes `.env` + `*.tar.gz` + `*.pem` + `*.key`; systemd runs as non-root cusma2/cusma3; Dockerfile multi-stage build; cron schedule documented in README_PROD
**Lokacija:**
- `.gitignore` (excludes `.env`, `.env.*` except `.env.example`, `*.pem`, `*.key`, `*.tar.gz`, `.DS_Store`, IDE files)
- `README_PROD.md:41, 65` (`User=cusma2` / `User=cusma3`)
- `Dockerfile:1-12` (multi-stage build stage)
- `README_PROD.md:152-170` (cron schedule table)

**Detekcija:** statička
**Opis:** Pozitivni patterni:
- **`.gitignore` solid** — covers env files, OS cruft, IDE files, secret artifacts. Plus `app-data/**` ignored (upload directory). `*.p12` is commented (F1-035 explicit decision — keystore in src/main/resources).
- **systemd non-root** — VM2 (`cusma2`) i VM3 (`cusma3`) — defense-in-depth proper. Compare s F6-001 (Dockerfile is root).
- **Multi-stage Docker build** — runtime image doesn't contain Gradle / build tools. Smaller image, smaller attack surface.
- **Cron schedule documented** — README_PROD.md:152-170 table cumulative schedule. F4-003 (cron clustering) je obvious from this table — documentation enables findings.
- **`.env.example` exists** — onboarding pattern correct (even if outdated per F6-005).

**Anti-pattern**: F6-001 vs systemd VM2/VM3 — Dockerfile is root but production deploy is non-root. Indicates Dockerfile not used for prod (verify).
**Status:** INFO

---

### Sažetak Batch 1

- **HIGH (3):** F6-001 (Dockerfile root user), F6-002 (README test-user passwords + F2-043 link = documented admin takeover), F6-003 (298 MB tar.gz unknown contents — verify before classification)
- **MED (4):** F6-004 (.env contains prod credentials, dev-machine risk), F6-005 (.env.example Viva + naming drift), F6-006 (docker-compose `changeme` default), F6-007 (ktlint ignoreFailures true)
- **LOW (5):** F6-008 (README old GET endpoints), F6-009 (detekt rules disabled), F6-010 (Dockerfile no HEALTHCHECK), F6-011 (systemd no OOM dump flag), F6-012 (model compose hardcoded creds)
- **INFO (1):** F6-013 (positives: gitignore solid, systemd non-root, multi-stage Docker, cron documented)

**Najkritičniji nalaz batch-a: F6-002.** Combined s F2-043 CRIT = single-glance prod admin takeover path documented in public-repo README. **Mora se brisati README table prije bilo čega**, plus F2-043 env var verify.

**Top consolidation:**
- **F6-006 + F5-013/F5-014/F5-016** — single yml-hardening commit pokriva docker-compose i application yml secrets.
- **F6-001 + F6-010 + F6-011** — Dockerfile + systemd hardening (containerization sweep).
- **F6-005 + F6-008** — docs cleanup (.env.example + README endpoints update).

**Phase 6 read-pass završen.** Total Phase 6: F6-001..F6-013, 1 batch.

Phase 6 next step: **closure + phase gate** (analogno Phase 2/3/4/5 flow).

---
