# Faza 5 — Cross-cutting

**Status:** in progress (inventory + read pass)
**Datum starta:** 2026-05-11
**Scope per spec (`2026-05-07-boat4you-prod-review-design.md:124-136`):**
- `common/errorhandling/` — `ApiErrorHandler.kt` (global `@ControllerAdvice`), `ApiErrorCodes.kt`
- `common/exceptions/` — `EntityNotDeletableException`, `ParameterValidationException`, `ResourceNotFound`, `UnmodifiableFieldsException`
- Sve `log.*(...)` linije (grep cijelog repa) — što loga-mo, na kojem level-u, s kojim PII
- `application.yml` + svi profili (`application-prod.yml`, `application-dev.yml`, `application-aws.yml`)
- `messages/*.properties` (10 fajlova — 1 default + 9 locale variants)
- `common/services/` — preostali fajlovi koji nisu već touched u Phase 1-4
- `logback-spring.xml` (logging config)
- `events/*` — NIJE u repo-u (no events folder per glob); spec spomenuo ali ne postoji

---

## Inventory

### Error handling (2 files)

| Path | Role |
|---|---|
| `common/errorhandling/ApiErrorHandler.kt` | Global `@ControllerAdvice` — exception → HTTP status mapping |
| `common/errorhandling/ApiErrorCodes.kt` | Enum/constants of API error codes (likely client-facing toast messages) |

### Exceptions (4 files)

| Path | Role |
|---|---|
| `common/exceptions/EntityNotDeletableException.kt` | Custom exception |
| `common/exceptions/ParameterValidationException.kt` | Param validation failure |
| `common/exceptions/ResourceNotFound.kt` | 404 mapping |
| `common/exceptions/UnmodifiableFieldsException.kt` | Field-level update reject |

### Common services (9 files — non-touched u earlier phases)

| Path | Already covered? |
|---|---|
| `common/services/CommonTranslators.kt` | NEW |
| `common/services/FileSystemService.kt` | Phase 1 F1-021/F1-034 — defense-in-depth callers verified except F4-010 |
| `common/services/ImageService.kt` | NEW |
| `common/services/ImageUtils.kt` | Phase 4 F4-009 native memory leak |
| `common/services/LocaleHelpers.kt` | NEW |
| `common/services/PriceCalculations.kt` | NEW |
| `common/services/UnitCalculations.kt` | NEW |
| `common/services/UrlShortener.kt` | NEW |
| `common/services/Utils.kt` | NEW |

### Application config (4 files)

| Path |
|---|
| `application.yml` (defaults, base configuration) |
| `application-prod.yml` (prod overrides) |
| `application-dev.yml` (dev overrides) |
| `application-aws.yml` (AWS-specific overrides) |

### i18n (10 properties files)

| Path |
|---|
| `messages/email.properties` (default, likely EN) |
| `messages/email_en.properties` |
| `messages/email_hr.properties` |
| `messages/email_de.properties` |
| `messages/email_es.properties` |
| `messages/email_fr.properties` |
| `messages/email_it.properties` |
| `messages/email_pt.properties` |
| `messages/email_nl.properties` |
| `messages/email_pl.properties` |

### Logging config

| Path |
|---|
| `logback-spring.xml` — log levels, appenders, rotation, format pattern |

### Logging callsites — grep target

Across all `src/main/kotlin/**/*.kt` files, search for:
- `log.info`, `log.warn`, `log.error`, `log.debug`, `log.trace`
- PII indicators: `${user.email}`, `${reservation...}`, `${jwt}`, `password`, `token`
- Stack traces in customer-facing exception paths

---

## Spec questions to answer

Per design spec section 124-136:
1. **Exception poruke korisniku** — leak interne info? (F1-055 / F1-066 / F3-015 / F4-013 patterni)
2. **Exception poruke u logu** — govori li programeru dovoljno (correlation id, user id, parametri sažeto)?
3. **Log linije s PII / JWT / password reset linkom / karticom** — pattern grep
4. **Log levele i rotacija** — prod logback-spring.xml audit
5. **Spring profile defaults koji moraju biti override u prod-u** — sve `${VAR:default}` antipattern check
6. **i18n missing keys** — Thymeleaf `#{key}` references koje nemaju match
7. **Sinkroni vs asinkroni eventi** — `@TransactionalEventListener` pattern (F3-033 pozitivan), specifične potrebe

---

## Workflow Faze 5

Plan reading pass-a u 2 batch-a:

1. **Batch 1 — Error handling + exceptions + i18n consolidation** — ApiErrorHandler + ApiErrorCodes + custom exceptions, plus i18n key audit (vlikih volumen, grep za missing keys)
2. **Batch 2 — Logging audit + yml profile audit + common services foundation** — logback-spring.xml + 4 yml fajla + remaining common services (CommonTranslators, ImageService, LocaleHelpers, PriceCalculations, UnitCalculations, UrlShortener, Utils)

Za svaki batch: triage live u ovaj file kao `[F5-NNN]` nalazi.

**Očekivani output:** Phase 5 surfaceira large-volume LOW/MED finding-a (log-PII grep, yml defaults, i18n gaps). Mali broj HIGH (foundational error-sanitization već flag-an kroz F1-055/etc.). Phase 5 cilj je **konsolidacija** prijašnjih point-finding-a u cross-cutting fix batch-eve.

---

## Findings

(Početak Batch 1 u sljedećem commit-u.)
