# Faza 7 — Završni pass i dinamička verifikacija

**Status:** in progress
**Datum starta:** 2026-05-11
**Scope per spec (`2026-05-07-boat4you-prod-review-design.md:157-165`):**

1. `./gradlew compileKotlin detekt test` — sve mora biti zeleno (ili dokumentirano otklon)
2. `docker compose up -d` lokalno — app + DB smoke test
3. Smoke-test po listi nalaza koji traže runtime potvrdu (Swagger gating, dev sync zaštita, login throttling, webhook potpis na invalidnom payloadu, itd.)
4. Re-read svih izmijenjenih fileova kroz sve faze (sanity sweep)
5. Generiranje `docs/superpowers/findings/SUMMARY.md` s ukupnim brojem CRIT/HIGH/MED/LOW/INFO, listom popravljenih commit-ova, listom OPEN nalaza s preporukom (popraviti prije deploya / nakon / accept risk), te zaključkom "deploy-spremno / nije zbog X"

**Output:** `docs/superpowers/findings/phase-7-verification.md` + `docs/superpowers/findings/SUMMARY.md`.

---

## Što reviewer (ja) može napraviti vs što Mario (ti) mora

**Static verifications (mogu sam):**
- ✓ Gate run (compileKotlin + detekt + test)
- ✓ REGISTER tally sanity check (sumarni broj findings vs per-phase)
- ✓ Doc completeness audit (svi phase-N-*.md fajlovi postoje + closure sekcije popunjene)
- ✓ Cross-finding referencing audit (F1→F3→F5 family chains documented)
- ✓ Read-pass scope completeness (per spec scope vs what I actually read)
- ✓ Generate SUMMARY.md

**Runtime verifications (ti moraš):**
- `docker compose up -d` lokalno — verify app starts s current code
- Smoke test endpointova navedenih ispod
- Network-side verifications (nginx config, partner HTTPS, prod env vars)
- Production environment state verifications (F2-043 FLYWAY_TARGET_VERSION, F4-004 image-sync profile, F6-003 tar.gz contents)

---

## Smoke-test checklist (Mario runtime)

Lista nalaza koje moraš runtime-potvrditi prije production deploy-a:

### Production environment verifications (highest priority)

| Finding | What to verify | How |
|---|---|---|
| **F2-043 CRIT** | `FLYWAY_TARGET_VERSION=1.90` (or specific V1.xx) env var set na VM2/VM3 | `systemctl cat boat4you` → `EnvironmentFile`; `cat /home/cusma2/boat4you/boat4you_vars.env \| grep FLYWAY_TARGET_VERSION` |
| **F2-043** | If env var NOT set, **block first prod deploy** until set; remove V9_00/V9_02/V9_03 from prod DB if applied | manual SQL: `DELETE FROM users WHERE email LIKE '%@workspace.hr';` plus reset Flyway state |
| **F6-002 + F2-043** | Verify if test users exist in prod DB | `SELECT email FROM users WHERE email LIKE '%@workspace.hr';` |
| **F6-003** | Inspect contents of `boat4you-ws-perf-update.tar.gz` (298 MB) | `tar -tzf boat4you-ws-perf-update.tar.gz \| head -50` — if PII → secure delete |
| **F2-044** | Mario commentary on V1_24 destructive DROP COLUMN | git commit annotating V1_24 rationale (`offer.payment_plans` + `external_reservations.external_id` were dead before drop) |
| **F4-004** | `image-sync` profile set in VM3 deploy env? | `cat /home/cusma3/boat4you/boat4youscheduler_vars.env \| grep SPRING_PROFILES_ACTIVE` |
| **F3-003** | NauSys HTTPS partner support verify | manual `curl -v https://ws.nausys.com/...` + Mario contact w/ NauSys support; switch `NAUSYS_URL=https://...` u env vars |

### Application-level smoke tests (Mario lokalno)

| Test | Endpoint / Action | Expected |
|---|---|---|
| Swagger disabled u prod | `curl https://prod.boat4you.com/swagger` | 404 / disabled (F1-002 fix) |
| Login throttling | 6 failed login attempts u istom računu | account locked, 5xx response (F1-011 fix) |
| Stripe webhook invalid sig | `curl -X POST /webhooks/stripe -H 'Stripe-Signature: invalid'` | 400 Invalid signature (F1-031 fix) |
| Health endpoint | `curl https://localhost:8443/actuator/health` | 200 + status:UP (verify exists) |
| Public yacht search | `curl https://prod/yacht/search?did=c-86` | 200, no synchronous external sync block (F1-064 concern) |
| Admin sync POST | `curl -X POST -H 'Authorization: Bearer ADMIN_JWT' https://prod/admin/mmk/sync` | 200; verify s log da sync started (F1-042 fix) |
| Inquiry POST (anon) | `curl -X POST /public/inquiries -d '{"email":"test@test.com"}'` | rate-limit verify (F1-068/F1-069) |
| Stripe webhook retry behavior | replay same `checkout.session.completed` event 2x via Stripe dashboard | second call = no-op (F3-022 fix verify; OPEN as of read-pass close) |

### Code-fix verifications (post-fix-batch)

Kad implementiraš fix-eve, verify per finding:

| Fix finding | Verify after fix |
|---|---|
| F4-001 | yml change confirmed: `grep -A 3 'scheduling' application.yml`; restart app; verify `[b4y-sched-N]` thread names u log-u |
| F4-002 ShedLock | start 2 VM instances s data-sync profile; verify only one runs each cron via `select * from shedlock` table |
| F4-009 ImageUtils | RSS metrics on VM3 over 24h after fix; native memory < 500 MB stable |
| F3-022 Stripe webhook | Stripe dashboard "test webhooks" retry → verify processed_stripe_events row insert + no duplicate side effects |
| F3-035 DevController | curl `/admin/dev/sync-equipment-catalog` as non-admin → 403; as admin → 200 |
| F5-012 Utils SecureRandom | `JAVA -Dnu.pattern.OpenCV.loadLocally=true ... ` and verify `getRandomPassword()` output differs across runs |
| F5-001 AccessDeniedException | curl forbidden endpoint w/ wrong role → 403 (not 500) |
| F5-013 JWT fail-fast | start app w/o JWT_SECRET_KEY env → app fails to start with clear error |
| F6-001 Dockerfile non-root | `docker run boat4you:latest sh -c 'whoami'` → boat4you (not root) |
| F6-002 README delete | `grep -i '123456' README.md` → empty |

---

## Sanity sweep results (static verifications complete)

### Phase docs completeness

| Phase | doc file | Closure section | Status |
|---|---|---|---|
| 1 | `phase-1-boundary.md` | ✓ (pre-existing) | CLOSED 2026-05-08 |
| 2 | `phase-2-data.md` | ✓ | CLOSED 2026-05-11 |
| 3 | `phase-3-integrations.md` | ✓ | CLOSED 2026-05-11 |
| 4 | `phase-4-jobs-native.md` | ✓ | CLOSED 2026-05-11 |
| 5 | `phase-5-cross-cutting.md` | ✓ | CLOSED 2026-05-11 |
| 6 | `phase-6-repo-hygiene.md` | ✓ | CLOSED 2026-05-11 |
| 7 | `phase-7-verification.md` | this file | in progress |

### REGISTER.md tally

Cross-check sum of per-phase OPEN counts vs cumulative table:
- Phase 1: 41 OPEN
- Phase 2: 44 OPEN
- Phase 3: 32 OPEN
- Phase 4: 12 OPEN
- Phase 5: 17 OPEN
- Phase 6: 12 OPEN
- **Sum: 158 OPEN** — matches cumulative table.

Plus FIXED total 23, INFO 22, DEFERRED 9, BLOCKED 1.

### Read-pass scope vs spec coverage

| Spec phase scope | Read in phase | Coverage |
|---|---|---|
| Boundary / attack surface | Phase 1 | ✓ (pre-existing) |
| Data layer (persistence) | Phase 2 (7 batches) | ✓ |
| External integrations | Phase 3 (6 batches) | ✓ |
| Scheduled jobs + native | Phase 4 (2 batches) | ✓ |
| Cross-cutting | Phase 5 (2 batches) | ✓ |
| Repo hygiene + deploy | Phase 6 (1 batch) | ✓ |
| Final verification | Phase 7 | in progress |

### Cross-finding referencing audit

Major cross-phase finding chains:
- **F1-019 CRIT → F3-022 CRIT → F2-026/F2-036 → F5-001 (Stripe payment integrity chain)** ✓
- **F1-021 LOW → F4-010 MED → F5-001/002 catch-all family (path traversal + error sanitization)** ✓
- **F1-036 HIGH FIXED → F5-013/014/016 (env var fail-fast extension)** ✓
- **F1-037 HIGH → F3-003/009 (NauSys HTTP escalation)** ✓
- **F1-041 HIGH → F3-035 HIGH (DevController triple-defense)** ✓
- **F1-055/F1-066 LOW → F5-002 HIGH (error message leak family)** ✓
- **F1-068 CRIT → F5-012 CRIT (anon endpoint → predictable verification codes)** ✓
- **F2-001/F2-004 → F2-017/F2-028/F2-038/F2-041 → F3-007/F3-010 (audit trail family)** ✓
- **F2-022 FIXED → F4-001/F4-002 scheduled job foundation** ✓
- **F2-026/F2-036 → F3-022/F3-023/F3-024 (payment phase entity contract + Stripe webhook)** ✓
- **F2-043 CRIT → F6-002 HIGH (V9 test data + README password documentation)** ✓
- **F3-001/F3-002 → F4-005 (no timeouts compounds u runYachtSync chain)** ✓
- **F3-037 → F4-002 (cross-VM mutex → ShedLock decision)** ✓
- **F4-009 → F6-011 (native memory leak → systemd OOM dump missing)** ✓

Cross-referencing solid.

---

## Phase 7 gate (final)

`./gradlew compileKotlin detekt test --continue` — final pre-Phase-7-summary gate:

- **compileKotlin ✓** — clean across all 7 phases of doc commits. No production code changes during read-pass; only F2-018/F2-019 (`0d1242a`) i F2-022 (`0dc514f`) code changes throughout entire session.
- **detekt: 291 weighted issues** — exact baseline match (Phase 1-6 close), zero regression.
- **test: 29/103 failed** — F1-074 baseline; zero new failures.

Gate: **PASS at baseline (zero regression across all 7 phases)**.

---

## SUMMARY.md generation

Generated separately at `docs/superpowers/findings/SUMMARY.md` — comprehensive top-level review summary.

Phase 7 final commit closes the review-pass branch.

---
