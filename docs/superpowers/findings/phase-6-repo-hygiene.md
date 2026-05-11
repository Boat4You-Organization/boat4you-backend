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

(Početak Batch 1 u sljedećem commit-u.)
