# Backend deploy notes

## 2026-07-06 — Image download: interleave oldest+newest (BE 5e50ac4, DEPLOYED)

**What:** `ImageDownloadJob` fetched un-synced images newest-id-first only
(`findBySyncedFalseOrderByIdDesc`). When ~500 MMK/NauSys agencies auto-created at once
(~58k new images), the nightly sync kept adding higher-id un-synced rows that leapfrogged
the backlog → old low-id agencies (FX Yachting) starved for days. Fix = `downloadImages()`
now splits the page ½ newest (DESC) + ½ oldest (ASC) when `count > pageSize` (strictly
disjoint → no double-download); `count ≤ pageSize` takes all. New repo method
`findBySyncedFalseOrderByIdAsc`. No migration, no config/entity/sync-write change,
`image-sync-count` untouched (5000, ~21min ≪ PT2H lock → no double-run).

**Deploy:** ONE combined jar from clean worktree @ 5e50ac4 (HEAD — linear, contains inquiry
534150f + image fix + V9_33–36; tree clean, no parallel WIP). Job is `@Profile("data-sync &
image-sync")` = cusma3 only, but jar shipped to BOTH nodes to keep the "isti webservice.jar"
invariant. **cusma3 first** (Started 16:17:42, image fix live before next 16:50 UTC run),
then **cusma2** (inert change there, API 200 local+edge, Started 16:18:54). Jar verified
pre-deploy: V9_30–36 present + `findBySyncedFalseOrderByIdAsc` compiled + `isInquireOnly`
intact. Rollback: `webservice_pre_imgfix.jar` on cusma2/3.

**Baseline for verification (16:19 UTC):** pending 37,509; min pending id 545 = yacht 30
"Alexandros" (oldest starved). After the 16:50 UTC run (interleave, ~2500 oldest/run) the
low-id front (545+) should flip synced → verify Alexandros images download.
⚠️ Committed by the parallel MMK/NauSys session; I did the single combined deploy so the
two sessions don't ship competing jars (5.7 concurrent-deploy outage lesson).

**RESULT + one-time drain (16:52→18:20 UTC):** interleave confirmed (Alexandros/low-id
blanks filling). To skip the ~15h wait at 5000/2h, bumped cusma3 env `IMAGE_SYNC_BATCH
10→30` + `IMAGE_SYNC_COUNT 5000→40000` (restart) → one run cleared **37,509 → 602**
(blank yachts 3309→5, blank main 3331→19). Residual 602 = dead partner URLs (neg-cache 7d).
Reverted env to 10/5000 + restart (steady-state), backup `.bak.imgdrain` removed.
⚠️ Learned: cusma3 image throughput is **CPU-bound** (2 cores, webp encode), ~260/min avg
(bursts 2500/min); batch beyond ~30 doesn't help. Frequent cusma3 SSH (monitor loops) trips
**fail2ban** ("Permission denied", not a bad pw) — poll pending via cusma2, spare cusma3.

## 2026-07-06 — Agencies: inquiry-only flag (BE 534150f, admin cc9b0f7, DEPLOYED)

**What:** per-agency "Inquiry mode" toggle in the admin /agencies edit modal (right of
Recommended). When ON, every yacht of that agency becomes inquiry-only — no direct/live
reservation, only the inquiry form — exactly like CUSTOM boats.

**Mechanism (no web change):** `Agency.inquiryOnly` (V9_36 `inquiry_only BOOLEAN NOT NULL
DEFAULT false`) → `Yacht.isInquireOnly()` now also true when `agency.inquiryOnly`. That one
method already feeds BOTH the web detail DTO (`YachtMapper.toDetailsDto.inquireOnly` →
web `resolveGate` shows the inquiry form) AND the booking guard
(`ReservationFlowMutationService.createReservationFlow:84` throws "Yacht is inquire only").
Agency is loaded within the @Transactional at both call sites (no LazyInit). Admin threads
the flag via `AgencyDto.inquiryOnly` + `toDto`/`updateBlockWithModel` (null-coalesced so a
partial PUT never clears it).

**Deploy:** jar built from clean detached worktree @534150f (Xmx5g, no-daemon). cusma2 FIRST
(Flyway applied V9_36 clean → `now at version v9.36`, Started 11.7s, `/public/countries` 200
local+edge), then cusma3 scheduler (Flyway-pinned, skips V9_36; column already present → clean
boot 12.9s). Admin dist → cusma1 `/var/www/admin.boat4you.com/html` (entry `index-DZLf54rx.js`,
`inquiryOnly` in bundle, `api.boat4you.com` baked 0× localhost). Rollbacks:
cusma2/3 `webservice_pre_inquiry.jar`, cusma1 `html.old`.

**Verified live (A/B on cusma2):** yacht 15932 (Allure, FX Yachting 1616) → FX flag OFF
`inquireOnly=false`, flag ON `inquireOnly=true`, reverted OFF `inquireOnly=false`. Adversarial
review: no real bugs. FX left at inquiry_only=**false** (feature shipped disabled everywhere —
Mario toggles per agency; 0/2061 agencies currently on).

## 2026-07-06 — Trip: crew push on document/crew-list add + install banner (BE 778662a, web -5SRuex, DEPLOYED)

**Push when we add something (Mario 6.7.):** admin uploads a customer-visible travel document
(BOARDING_PASS / CREW_LIST / PREFERENCE_LIST) or first sets the crew-list link → the crew's
subscribed devices get a web-push ("📄 New document in your trip" / "📋 Crew list ready").
Weather + charter-announcement pushes already exist (TripPushJob T-7/T-1/day-of).
TripPushService.notifyCrew resolves the token + sends after commit. Internal admin docs stay silent.

⚠️ **Review (HIGH) fixed pre-deploy:** the after-commit push ran on the request thread while the
outer tx's Hikari connection was still bound, and web-push send() blocks with NO timeout — a hung
push endpoint could pin a pooled connection on cusma2 (single no-swap API node). Fix:
`sendToReservationAsync` hands the blocking HTTP to a dedicated bounded pool (1-3 daemon threads,
queue 500, DiscardPolicy) so the request connection frees immediately and no DB connection is held
across the sends. Also applied to the pre-existing concierge chat push (same pattern). TripPushJob
(cusma3, scheduled) stays sync.

**Web install banner:** in browser mode (not standalone) a dismissible top banner (sticky, X →
localStorage) prompts adding to the home screen for notifications; Android drives the native
`beforeinstallprompt`, iOS shows the Share→Add-to-Home-Screen steps. (Note the Google Play Protect
"unsafe app" warning on Samsung Internet is a Google/WebAPK quirk, not ours — use Chrome / "Install
anyway".)

**Deploy:** built ONE unified jar from a CLEAN worktree at HEAD (778662a) — main now carries the
parallel session's V9_34 + charter-update (both committed + already applied, schema 9.34) plus my
trip changes, so Flyway skipped V9_34 (already applied) and both nodes booted clean on 9.34,
health 200. Rollback: webservice.jar.bak.pre-docpush (both). Web .next.bak on cusma1.

---

## 2026-07-05 (noć) — Trip: 10-day GDPR photo retention + ⚠️ concurrent-deploy outage

**Feature (Mario 5.7.2026):** trip photos are kept only 10 days after the charter, then deleted
(DB rows + NFS files). `TripPhotoRetentionJob` (cusma3, daily 09:50) purges photos of charters
ended >10d ago; uploads now close at +10; the T+1 "album ready" concierge post states the exact
download deadline (dateTo+10) + that we remove them afterwards (GDPR); the hub album shows the
deadline. Web: BUILD BSDKGYZhHgJu83CykyniT. Backend built from a CLEAN git worktree at my HEAD
(4c52b01) to exclude the parallel session's uncommitted WIP; deployed to cusma3 (retention runs
there, @Profile data-sync) and cusma2. First purge deletes nothing real yet (only future-dated
Zen test photos exist).

⚠️ **PRODUCTION INCIDENT — ~5 min API outage (~20:29–20:34 UTC):** TWO work streams were deploying
to cusma2 at the same time (mine = trip; a parallel one = booking-number prefix 1001→1441 **V9_33**
+ "reservation charter update" **V9_34**). Their concurrent restarts + a transient Flyway
"Migrations have failed validation" during the migration transition crash-looped cusma2 for ~5 min
(api.boat4you.com 502). It SELF-RECOVERED: the parallel jar (size 229964138) settled, applied V9_34
(schema now **9.34**), booted healthy (Tomcat 20:35:03). Final state verified stable: api/www/admin/
trip all 200/307, cusma2 no further restarts.

**Resulting split (benign, but note for next deploy):** cusma2 (API) runs the PARALLEL jar (9.34,
charter-update, and it DOES carry my trip code — album.zip 403-guard present, so they built from
main incl. my commits). cusma3 (scheduler) runs MY jar (9.33, retention job). Job profiles are
disjoint (data-sync only on cusma3) and only cusma2 serves the API, so both features work. V9_34 is
applied to the DB but its FILE is NOT yet on origin/main (parallel session's local WIP) — a unified
rebuild isn't possible until they push it. **⚠️ On cusma3's next restart, Flyway will see DB 9.34 as
a future migration (warn, no-op) — watch it comes up clean.**

**LESSON: never run two concurrent production backend deploys to cusma2.** Coordinate; deploy
serially. Rollbacks: webservice.jar.bak.pre-retention (cusma3 = my jar; cusma2 was overwritten by
the parallel deploy).

---

## 2026-07-05 (noć) — Trip: GDPR-minimal admin + album ZIP download (BE 05cdd55, web tfBhU7f…, admin, ALL DEPLOYED)

**Mario 5.7.2026:** the broker must NOT have casual access to the crew's private trip content.
- **Admin now has NO chat / participants / photo-browsing.** AdminTripController reduced to:
  album-summary (counts only), album.zip (?marketingOnly=true = only consented), regenerate-token.
  Removed the chat DIGEST email (it carried guest PII) and the 💬 unread badge; also removed the
  `tripChatUnread` field from the reservation view/entity/DTO/mapper (it leaked a crew-chat-activity
  signal to the broker on every bookings-list fetch — review find). Physical `admin_chat_seen_at`
  column left unused (no migration, avoids V9 clash).
- **Album delivery:** the crew hub gets a "⬇ Download all photos" (ZIP) button; the T+1 concierge
  automation now posts "your photos are ready — download them" (chat + push) when photos exist =
  the crew's download link. Admin keeps an on-demand ZIP (all, or marketing-consented) for when a
  guest asks / for approved marketing reuse.
- **ZIP is STREAMED** (StreamingResponseBody + Files.copy, one photo at a time, OUTSIDE any tx) —
  never buffered whole in heap (would OOM cusma2, the single no-swap API node) and never pins a
  Hikari connection across the NFS reads (review HIGH+MEDIUM). Service returns only the file list
  in a short tx; the controller streams.

⚠️ **V9_33 COORDINATION:** a parallel session's `V9_33__reset_booking_sequence_new_prefix.sql`
(booking-number prefix 1001→1441, resets booking_sequence to 0) was already applied to cusma2's DB
(schema at v9.33) and its 1441 `BookingNumberService` code is on origin/main (in HEAD). My jar
(max migration 9.32) was built with V9_33 PARKED OUT of the migration dir so it doesn't carry it;
Flyway treats the DB's 9.33 as a future migration (warn, no-op) and re-ran the repeatable R__1_02.
My jar DOES contain the 1441 code (from HEAD), so it's consistent with the reset counters —
first new online booking = 1441001/{year}, no collision with legacy 1001…. **If ever rolling my
jar back, the 1441 code stays (it's committed), so no counter hazard from my side.**

**Verified live on Zen:** crew album.zip 200 (valid ZIP, streamed), wrong key 403, admin endpoints
security-gated. Rollback: webservice.jar.bak.pre-gdpr (both nodes); web .next.bak on cusma1;
admin html.old. Test artifacts cleaned from Zen (real crew Cvijo/Jadranka/Mario kept).

---

## 2026-07-05 — Reservation-number prefix 1001 → 1441 + counter reset (V9_33)

**Rule (Mario 5.7.2026):** online bookings switch prefix `1001` → `1441` and the per-year
sequence restarts at 1, zero-padded to 3 digits → next is **`1441001/2026`**, then
`1441002/2026`; a 2027-start charter → `1441001/2027`. Reason: parallel bookkeeping this
year (legacy back-office reservations + new online ones) — the two streams must not clash.

- Code: `BookingNumberService.PREFIX="1441"` + `padStart(3,'0')`.
- `V9_33` = `UPDATE booking_sequence SET last_sequence = 0` (was 2026→84, 2027→3 on 5.7.).
  Existing `1001…` numbers untouched; `1441…` never collides (different prefix).
- **Deploy order: cusma2 FIRST** (applies Flyway; cusma3 pinned won't). Bookings are created
  on cusma2, so both the reset + new code land there together at boot (no window).
- **⚠️ ROLLBACK HAZARD:** rolling the jar back to old `1001`/unpadded code while the counters
  stay 0 → old code regenerates `10011/{year}` etc. → unique-constraint failure on booking
  creation. If you roll back the jar, ALSO restore the counters (2026→84, 2027→3) or bump
  each year past its highest used legacy sequence. Rollback jar: `webservice_pre_prefix1441.jar`.

## 2026-07-05 (navečer) — Trip hub → app-like bottom tabs (web 3a657a1, BUILD -eCPbHLPinSZWmIgCmHbP)

Per Mario + the approved design: the trip hub's single long scroll became a tabbed
app. Persistent hero on top; fixed safe-area bottom nav with 4 tabs — **Trip**
(gallery, owner payments, weather, SOS), **Documents** (travel docs + empty state),
**Chat** (crew + chat + album), **More** (push reminders, install guide, support).
Pure web layout change; no backend. Verified live: 200, all four tab labels served,
build -eCPbHLPinSZWmIgCmHbP. Rollback .next.bak on cusma1.

---

## 2026-07-05 (kasno navečer) — Trip album upload fix: nginx 413 (SERVER CONFIG + web a8a79ad)

**Symptom:** photo upload from Mario's iPhone silently did nothing. **Cause:** nginx on
cusma2 had NO client_max_body_size anywhere → default 1 MB; phone photos (3-10 MB) got
413 before reaching Spring (access log confirmed). Admin doc uploads never hit it (small
PDFs/Word).

**Fixes:** (1) `/etc/nginx/conf.d/boat4you.conf` — `client_max_body_size 25m;` in both
api server blocks (backup `.bak.pre-bodysize`, nginx -t + reload OK). ⚠️ MANUAL SERVER
CONFIG — not in git; restore it if the box is ever rebuilt. (2) Web (a8a79ad, BUILD
E-Q-IcLBAjUlxzS-62d_8): photos downscale client-side to 2048 px JPEG before upload
(keeps every phone photo ~1 MB, fast on marina Wi-Fi, also under the 10 MB server image
cap), upload failures now show a message instead of silently doing nothing, accept
widened to image/* (HEIC decodes in canvas → JPEG). Verified: 5.6 MB upload → 200 →
webp served; probe rows cleaned from Zen (Test Gost + poruka ostavljeni kao demo).

---

## 2026-07-05 — Boat4You Trip PHASES 3+4: crew, chat, album, admin console (BE b499987, web 876f006, admin fcc6d80 — ALL DEPLOYED)

**The full Trip product is live.** V9_32: trip_participant (secret per-device key,
roles OWNER/GUEST/SKIPPER/CONCIERGE, soft remove), trip_chat_message (automation_tag
for idempotent scheduled posts), trip_photo (per-upload marketing consent),
reservation.admin_chat_seen_at.

**Closed group:** guests join with just a name (unlocks after 1st payment / status
RESERVATION, max 20, refusal reasons LOCKED/FULL/FINISHED/CANCELLED); the owner
enters pre-claimed through his web session (/secured/trip/{token}/owner — stable
key across devices); leader/admin remove members; admin can regenerate the link
(now also deletes push subscriptions so old devices can't receive the new URL).

**Chat:** PG rows + SSE fan-out on cusma2 (heartbeat 25s, X-Accel-Buffering:no) +
30s full-resync poll (scheduler posts have no SSE emitters + fixes id-cursor gaps);
writable until dateTo+14d; concierge posts (admin or automation) push to crew
devices AFTER COMMIT. TripChatAutomationJob 09:45 (cusma3): itinerary T-14 (+push),
ready T-1 (no double push, TripPushJob covers it), daily digest email to admins;
per-post transactions (review fix — one bad reservation no longer poisons the batch).

**Album:** webp ingest to NFS trip-photos/{id}, consent checkbox per upload,
uploader/leader/admin delete (= GDPR consent-withdrawal path), uploads close
dateTo+30d. **Admin:** Trip chat & crew panel in the booking detail (concierge
composer, participant chips w/ remove, photo grid w/ MKT-consent badges + delete),
💬 unread badge in the bookings list (concierge posts excluded).

**QRs:** my-bookings (desktop→phone, next to the Trip app button) + hub invite
card (owner: QR + navigator.share). Push isOwner is now VERIFIED via the OWNER
participant key (client boolean was spoofable — review find).

**Review:** 22-agent adversarial workflow, 15 confirmed findings fixed pre-deploy
(tx poisoning, commit-ordered SSE/push, newest-200 history, badge filter, docs
+30d listing window, key-leaking album anchors removed). Known accepted: key in
query strings for GET/SSE/img (EventSource/img can't send headers; nginx logs are
internal), removed guests can rejoin under a new name (leader just removes again).

**E2E verified live on Zen:** join→chat post→history→SSE ':connected'→photo
upload(webp)→raw 200→self-delete 204; 403 wrong key; /admin/trip 403 unauthed.
Test rows left as demo: participants 'Test Gost' + 'SSE Probe', 1 chat message —
removable via the new admin panel. Rollbacks: webservice.jar.bak.pre-trip3 (oba),
.next.bak-20260705165603, admin html.old.

---

## 2026-07-05 — Boat4You Trip PHASE 2: push + analytics (BE 819390c, web 7b75ae0, ALL DEPLOYED)

**Web-push reminders + day-1 analytics are live.** V9_30 adds trip_push_subscription
(endpoint-unique upsert, is_owner flag) + trip_event; V9_31 backfills ACI Marina Split
coords (43.5024, 16.4295 — the only reservation marina without lat/lon; sync never
writes coords so it sticks). New deps: nl.martijndwars:web-push 5.1.1 (bcprov-jdk15on
excluded — we ship jdk18on).

**Endpoints:** POST /public/trip/{token}/push-subscriptions (400 on non-https endpoint,
404 wrong token) + /events (whitelist HUB_VIEW/SITE_CLICK/PUSH_SUBSCRIBE/PUSH_OPEN/
DOC_OPEN). TripDto gains vapidPublicKey (null = push off, hub hides the card).

**TripPushJob (cusma3, daily 09:40, shedlock):** T-7, T-1 + Open-Meteo forecast, day-of
welcome + forecast, T+1 thank-you, installment reminders 7/2 days before deadline —
owner-only devices, NO amounts ever (crew shares the hub). Click-through =
/trip/{token}?push=tag → hub logs PUSH_OPEN. Dead subscriptions (404/410) auto-delete.

**Web:** sw.js bumped b4y-v2 with push/notificationclick; hub gets a "Get trip
reminders" card (SW registered on /trip, hidden on unsupported browsers/finished trips),
analytics events fire-and-forget. iOS: card appears only inside the installed PWA.

**VAPID:** keypair generated 5.7, appended to cusma2 boat4you_vars.env + cusma3
boat4youscheduler_vars.env (backups *.bak.pre-vapid). Rollback jars:
webservice.jar.bak.pre-trip2 (both nodes); web .next.bak-20260705123210.

**Verified live:** V9_30+V9_31 applied (schema v9.31), Zen payload carries marina
coords + vapid key, sw.js v2 served, events 204→row in trip_event, 404/400 guards.
⚠️ First real push goes out at the 09:40 job — check `TripPushJob: delivered` in the
cusma3 journal after someone subscribes (Mario's phone is the E2E test).

---

## 2026-07-05 — Boat4You Trip PHASE 1 (commits 73fcf5d/858a54a/84d943f + web 4ceeccd + admin 161f1eb, ALL DEPLOYED)

**The PWA trip companion is live.** Every reservation carries an unguessable `trip_token`
(V9_29 — ⚠️ was V9_28 but the parallel agency-mirror session took that number; the clash
crash-looped cusma2 for ~90 s until the rename redeploy. LESSON: `git pull` + check migration
numbers against origin BEFORE building a jar). `GET /public/trip/{token}` serves the hub
payload (yacht+gallery+specs+slug, marina+coords, dates, crew-list link, agency phone, TRAVEL
docs only — no prices/PII); token-scoped travel-doc download; token in my-bookings + admin DTOs
via reservation_view (R__1_02 re-ran).

**Web:** `/trip/[token]` standalone EN mobile hub (own root layout; `trip` excluded from the
locale-middleware matcher!): countdown hero, gallery→boat page, Open-Meteo 7-day forecast for
the marina coords, country-aware SOS card, leader-only payments card (session+reservation-number
match), cancelled/finished modes, per-token manifest (`/trip/{t}/manifest.webmanifest`),
noindex. My-bookings shows a navy "Trip app" button. **Admin:** Trip hub panel with QR
(qrcode dep) + copy/open in the booking sidebar.

**Verified live on Zen (#100183/2026):** page 200 + SSR content, manifest OK, wrong token 404,
API 200, admin 200. Known gap: ACI Marina Split has NULL lat/lon → weather hidden there
(coords backfill = phase 2 item). Zen token: 718b59ec456c48c2b291ca2893ebbff8.

---


## 2026-07-05 — Agency mirror: auto-create/auto-deactivate partner agencies (V9_28)

**Rule (Mario 5.7.2026):** the partner's company list IS our agency list — every new MMK/NauSys
company (e.g. FX Yachting, MMK 8304) is auto-created with a primary source and its fleet follows
on the next yacht/offer sync; a company the partner stops returning is auto-deactivated
(active=false → yachts drop out of yacht_search_view). Deactivation is stamped
`sync_deactivated_by` (1=MMK, 2=NauSys): only the same system may re-activate, admin toggleActive
(resets to NULL) is never overridden, no cross-system ping-pong. Reconcile guards: empty response
= skip; >30% absent = truncated response, skip (PartnerWithdrawalGuard); legacy dual-primary rows
are never deactivated (logged). NauSys VAT/name match never merges into an MMK-sourced agency
(one row per system — dual VAT duplicates exist in prod, findAllByVatCode + filter).

**Pre-deploy checklist:**
- `V9_28` = ALTER TABLE agency (ACCESS EXCLUSIVE!) + unique index on
  agency_source(external_system_id, external_id) (prod pre-checked: 0 duplicates 5.7.).
  ⚠️ Before restarting cusma2: check `pg_stat_activity` for idle-in-transaction backends
  holding agency locks (29.6. lesson) — `pg_terminate_backend` first if any.
- Deploy order: **cusma2 FIRST** (applies Flyway; cusma3 is pinned FLYWAY_TARGET_VERSION=1.43
  and would not apply V9_28), then cusma3 (scheduler runs the actual mirror).
- Expected first run (measured 5.7. against live partner lists): MMK +367 created / ~168
  deactivated; NauSys +139 / ~24. 23 MMK + 52 NauSys agencies are inactive-with-us but still
  partner-listed — they STAY off (sync_deactivated_by=NULL = treated as manual) — Mario decides.

## 2026-07-03 — Travel documents: type + crew-list CTA (commit e7a1f87, DEPLOYED all 3 apps)

**Feature (Mario 3.7.2026):** near charter start the customer needs the crew list and the
boarding pass / base info. Decision: crew list = the PARTNER's own editor link (agency files
it with the port authority — no transcription by us); Kavas-style Word forms and boarding-pass
PDFs = admin-uploaded documents (existing reservation_document pipeline, reused).

**Backend:** `V9_27` adds `reservation_document.document_type` (BOARDING_PASS/CREW_LIST/
CONTRACT/OTHER, default OTHER — verified live) + threading through entity/repo projections/
DTO/upload endpoint (`type` param, lenient parse). Pre-charter reminder email gains a
conditional "Complete your crew list" CTA (partner `crew_list_url`; null → omitted), i18n in
9 email locales.

**Admin (b0dafed, deployed cusma1 /var/www/admin.boat4you.com):** "Upload as" type select on
the customer-visible drawer + type chip; crew-URL editor gains Open ↗ test button + onSaved →
reloadSelectedBooking (fixes stale-store bug after save).

**Web (80eb7ba, deployed cusma1 BUILD klrZoif73Nw4LSOK9Syt7):** sidebar Documents stack →
"Travel documents": typed rows (human label, filename · size · date meta, Open/Download
action), passport-accuracy note under the crew-list row. 7 new keys × 9 locales.

**Round 2 (same day, DEPLOYED):** `PREFERENCE_LIST` document type (backend f19930f — enum only,
document_type is VARCHAR so no migration; admin c427f6b adds it to the Upload-as select; web
3062697). Web also gains the **TravelDocumentsBar** — a prominent button strip rendered directly
UNDER the yacht images on /my-bookings/{id} (crew list link + uploaded crew form + boarding
pass/base info + preference list; renders nothing until something exists) so the customer can't
miss the travel documents. Boarding-pass label now reads "Boarding pass / Base info".

**Deployed 2026-07-03:** cusma2 (V9_27 applied) → cusma3 (flags preserved); admin dist swap
(rollback `html.old`); web .next swap (rollback `.next.bak-20260702225950`). Backend jar
rollbacks: `webservice.jar.bak.pre-docs` (both).

## 2026-07-02 — Taken-back yacht image purge (commit 5e9818e, DEPLOYED)

**Rule (Mario 2.7.2026):** yacht removed from the partner → its images go too. `ImageDownloadJob`
now purges partner-sourced images of deactivated yachts (rows + NFS files, 500/batch; main image
kept because sent reservation emails hotlink `/public/image/{mainImageId}`); `deleteYacht()` now
deletes files too. Backfill target measured pre-deploy: **6.5k rows / 335 inactive yachts**.
No migration.

**Deploy (DONE 2026-07-01 ~22:31 UTC):** jar from `5e9818e` → cusma2 (22:31, `/public/countries`
200) + cusma3 (22:32, started 10.9s). This jar also carried the two pending items below — both are
now LIVE (V9_26 was already applied at 22:28 by the parallel session's own cusma2 deploy of
`07fcff5`; this deploy supersedes that jar).

**Verify (DONE, first run 22:50 UTC):** `Purged 6221 images of deactivated yachts` + DB check:
exactly 335 rows (all main images) remain for inactive yachts. Summary line live:
`Image sync: 477 of 478 images failed for 139 yachts; 1 skipped as known-dead`.

**Follow-up `23c9ee2` (DEPLOYED both nodes ~22:56 UTC):** 6 residual ERRORs/run were partner images
over the 10 MB save cap (deterministic) — `IllegalArgumentException` from saveImage now joins the
7-day negative cache as WARN; ERROR stays for real disk/NFS failures only. Restart wiped the
in-memory cache, so the 00:50 run bursts ~477 WARNs once more; steady state (~12 WARN summaries/day,
0 image ERRORs) from 02:50 on.

---

## 2026-07-02 — cache-warm Hikari connection-pinning fix (commit 5c7aa53, DEPLOYED via 5e9818e jar 22:31 UTC)

**Overnight verify (22:55→06:26 UTC, 7.5 h) + follow-up:** the 5-min zombie mechanism is dead —
0 idle-in-transaction kills, 0 sync TimeoutExceptions (pre-fix 1.5–4k/day), 0 executor drops
(pre-fix ~16k/day), 1146 completed warm syncs, cache markers being written. Residual: 3 pool-
exhaustion bursts (03:32, 04:00, 05:32 — 102 errors total vs ~200+/day pre-fix), ALL inside the
nightly NauSys/MMK sync window; at those seconds NO connection was held >60 s (no leak WARNs
around them) → remaining bursts are pure throughput (cusma4 slow under sync writes + matview
refresh, 19–25 conns churning multi-second queries, 20 s waiters expire together). The ~188
overnight leak WARNs are the BOUNDED per-yacht warm path (partner call w/ retries can exceed the
60 s leak threshold; always unleaks) — expected noise, not a leak.

**Follow-up (06:31 UTC): `DB_POOL_MAX=35` on cusma2** (`boat4you_vars.env`, was default 25;
backup `boat4you_vars.env.bak.pre_pool35`; restart 06:30:59, verified in `/proc/<pid>/environ`,
health 200). PG headroom fine: max_connections=100, total in use ~21. cusma3 left at 25 (no
bursts there). Watch next night's 03:00–06:00 window: `journalctl -u boat4you | grep -c
"Connection is not available"` — expect 0; if bursts persist at 35, next lever is cusma4 query
perf during sync, not more connections.

Root cause of the nightly/daily "Connection is not available" bursts (18–41 errors in one
second, booking flow → "technical difficulties"): `ExternalSyncService`'s class-level
read-only transaction pinned a Hikari connection while the location-path cache-warm waited
up to 5 min on nested @Async tasks that starved/dropped on the same 6-thread pool
(F1-064 handler drops leave futures that never complete). Postgres
(`idle_in_transaction_session_timeout=5min` on cusma4) killed the session each cycle
(SQLSTATE 08006). Steady state: 6/25 connections gone + warm markers never written →
same ranges re-warmed forever. Fix: no ambient transaction on the location path,
partner syncs run in-thread sequentially (bounded by HTTP timeouts), per-yacht path
keeps its bounded read-only tx. NO migration in this commit — safe to ride along with
the V9_26 deploy below (any jar built from main ≥ 5c7aa53 carries it).

**Verify after deploy (cusma2):**
- `journalctl -u boat4you --since "<deploy time>" | grep -c "Apparent connection leak"` → should stay 0
  (pre-fix: ~1700/day in 5-min lockstep on AsyncThread-*).
- psql on cusma4: `SELECT count(*) FROM pg_stat_activity WHERE client_addr='192.168.55.2' AND state='idle in transaction' AND now()-xact_start > interval '90 seconds'` → 0 across a few samples
  (pre-fix: constantly 3–6).
- "Failed to sync yacht offers … TimeoutException" should drop to ~0 (pre-fix 1.5–4k/day);
  "Connection is not available" bursts should disappear over the following day.

---

## 2026-07-02 — V9_26 phone trunk-zero data fix (commit 64b8dd4, DEPLOYED — migration applied on cusma2 22:28 UTC)

`V9_26` rewrites stored `+3850…` phone numbers to `+385…` (reservation_flow 18, inquiry 2 —
customers typed national format 098… and the old PhoneInput stored the trunk zero; undialable
abroad). The FE fix (boat4you-web `ba943da`, PhoneInput strips trunk zero except IT/SM) is
ALREADY LIVE on cusma1. Deploy backend cusma2 (applies V9_26) + cusma3 when the parallel
image-spam session (commit 38a8d9d, same jar) finishes its own deploy — do NOT race two
deploys of the same service. Verify after: `SELECT count(*) FROM reservation_flow WHERE
phone LIKE '+3850%'` → 0.

---

## 2026-07-01 — First-payment deadline clamped to option expiry (commit f2c09c2, DEPLOYED)

**Bug (Mario):** payment page / emails said "pay by 08.07" while the NauSys option expired
06.07 23:59 (Zen 100183/2026). A customer paying between the two dates pays for a boat the
agency may already have re-let. Root cause: the first-phase deadline comes from the PARTNER
payment plan ("first installment within N days"), which is independent of the option window.

**Fix:** `ReservationMutationService.clampFirstPaymentDeadlineToOptionExpiry` — at reservation
creation (customer path only), the EARLIEST unpaid phase deadline is clamped to
`optionExpiresAt.toLocalDate()`. Later installments keep the partner schedule (the option
ceases to matter once the first payment confirms). Null expiry → untouched (never invent
deadlines). Same transaction → payment page, wire emails, and reminders all read the clamped
date. `V9_25` fixed pre-existing rows (live unconfirmed options, earliest unpaid phase only —
prod dry-run + actual: exactly 1 row, the Zen reservation: 08.07 → 06.07).

**Deployed 2026-07-01 ~21:52 UTC** cusma2 (V9_25 applied, verified Zen phase 88 = 2026-07-06)
+ cusma3. Rollback: `webservice.jar.bak.e2106ce` (both). Note: an already-open booking session
caches phases in sessionStorage — fresh page loads / my-bookings / emails read the DB.

---

## 2026-07-01 — Payment-method fees: card +5% / bank transfer 32 EUR (commit e2106ce, DEPLOYED)

**Policy (Mario 1.7.2026):** card payments +5% processing fee on the amount being paid
(per installment); bank transfers a fixed 32 EUR per reservation split evenly across
installments (2 phases → 16 EUR per wire, mandatory); all fees whole-euro (no cents).

**How it works:** the fee infrastructure already existed (settings `CARD_PAYMENT_SURCHARGE`
+ `BANK_TRANSFER_FIXED_FEE`, public endpoints, FE display) — card surcharge was already
applied to the Stripe charge but the setting was unset (0), and the bank fee was
display-only cosmetics. This deploy: `V9_24` seeds 5/32 (admin-editable later); Stripe
surcharge now rounded HALF_UP to whole EUR; NEW `BankTransferFeeShare` splits 32 whole-euro
across phases (earlier phases absorb remainder: 3 phases → 11/11/10); the wire "Transfer
amount" in fewMoreDetails / optionExpiryReminder / reservationPaymentPending emails now
carries the phase's share + a localized mandatory-fee notice (all 10 email locales).
**Payment phase rows keep the base charter price** — the fee is a payment-channel
surcharge applied at charge/communication time, so card payers never pay the wire fee
and vice versa; no phase mutation, no confirmed-price interaction.

**Frontend (boat4you-web 5e888c2, deployed cusma1 BUILD_ID GJ1QezPFzD74fW8uoKH22):**
UnifiedPaymentStep + PayNowModal mirror the backend math exactly (Math.round card fee;
per-installment bank share via `bankFeeShareForPhase` — was showing the full 32 on one
installment).

**Deployed 2026-07-01 ~21:35 UTC:** cusma2 (V9_24 applied, `/public/settings/*` return
5/32), cusma3 (scheduler jar for reminder emails; flags preserved), cusma1 FE swap.
Rollbacks: `webservice.jar.bak.78b8027` (both), `.next.bak-202607012138` (cusma1).

---

## 2026-06-30 — NauSys createOption INSUFFICIENT_DATA fix for strict agencies (commit 797f9bd)

**Symptom:** customers could not place an option on yachts of *strict* NauSys agencies
(Navigare = our agency 286 / NauSys companyId 122957; Dream Yacht Charter). The boat-detail
"enter-your-details" step failed; createInfo returned OK (with a price) but `createOption`
returned `INSUFFICIENT_DATA (201)`. Reported via Nedo (yacht 4548 / NauSys 37302180).

**Root cause (proven live, not guessed):** for strict agencies NauSys `createOption` requires the
client to carry a **COMPLETE postal address**. With only name+surname the option is rejected.
Surprisingly, supplying a client **email** *also* triggers `INSUFFICIENT_DATA` (NauSys then tries a
registered-client lookup that needs more fields). Live isolation matrix on 2026-06-30:
- name+surname only → createOption INSUFFICIENT (Navigare); OK for lenient agencies.
- name+surname + **address** (no email) → createOption **OK** for Navigare AND all 4 lenient agencies tested.
- address + **email** → INSUFFICIENT again. So: address required, email must be omitted.

**Fix:** `NausysReservationIntegrationService.createOption` now builds the createInfo `RestClient`
with name + surname + the **broker agency's registered address** (Vrboran 37, 21000 Split,
countryId=1=HRV — Cusmanich d.o.o., matches the NauSys agency profile) and **no email**. We don't collect the customer's address, and the option is a hold
we place as the broker, so the broker address is correct. Constants live in a `private companion object`.

**Scope:** API node only (`createOption` runs on the booking request path = cusma2). The scheduler
(cusma3) never serves bookings, so this is functionally a no-op there — sync its jar to 797f9bd at the
next idle window for consistency (preserve the `-Dreconcile.shadow-mode=false` ExecStart flag).

**Deploy (DONE 2026-06-30 ~23:05 UTC):** built JDK21 bootJar, scp to cusma2 `webservice.jar.new`,
atomic swap (rollback backup `webservice.jar.bak.c6b88c5`), `systemctl restart boat4you`. App up in
10.5s, `/public/countries` → 200. Verified: live createOption for Nedo (37302180/122957) with the exact
deployed recipe → OPTION created (price 7743.50 EUR), test hold stornoed. Lenient agencies unaffected
(4 tested, both old and new recipe succeed).

---

## 2026-06-29 — Permanent availability-mirror reconcile fix (natural-key + shadow + V9_23 cleanup + detector)

**What:** the absent-reconcile no longer depends on `external_mapping` integrity. It now matches our
reservations to the partner's complete response by NATURAL KEY (yacht + dates + status), so stale
(cancelled-at-partner) RESERVATION/SERVICE rows are removed even when their mapping is missing (96k
legacy rows) or duplicate-mapped to another yacht (the Vi La Ut case). Ships behind a SHADOW flag.

### Deploy order (standard backend deploy)
1. **cusma2 FIRST** — applies Flyway `V9_23` (FLYWAY_TARGET_VERSION=latest live). Restart `boat4you`.
2. **cusma3 SECOND** — scheduler (Flyway-pinned 1.43 → does NOT apply V9_23). Restart scheduler.

### PRE-DEPLOY dry-run (29.6.2026 ~18:30 UTC, prod)
`V9_23` deletes self-contradictory future hard-blocks (RESERVATION/SERVICE, option_expiration NULL,
date_to>today, overlapping one of OUR FREE offers on the same yacht):
- **438 reservations across 334 yachts** will be deleted.
- **Includes Vi La Ut res 283386 (yacht 4736, 08/08→15/08)** → that boat reappears as bookable.
**VERIFY post-migration:** Flyway-deleted count ≈ 438 (`SELECT count(*)` with the same criteria → 0 after).

### POST-DEPLOY (cusma2)
- The search hard-block reads `external_reservations` LIVE (correlated NOT EXISTS), so the fix is
  effective the instant the migration commits — **no manual matview refresh required.** (The
  `yacht_search_view` 5-min refresh updates the FREE/price display in due course.)

### SHADOW → LIVE (the catastrophe firewall — do NOT skip)
- Ships `RECONCILE_SHADOW=true` (default in code: `reconcile.shadow-mode:true`). While ON,
  `reconcileAbsent` LOGS what it WOULD delete (`[SHADOW] reconcile WOULD delete ...`) and deletes
  NOTHING. The migration above still runs (it is independent), so the 888/438 customer-facing damage
  is fixed on deploy regardless.
- After **3–7 full sync cycles**, review the `[SHADOW]` log on cusma3:
  - every WOULD-delete line must be a real cancellation / known-stale row,
  - **zero** WOULD-delete lines on a row a live partner read confirms is still booked,
  - per-agency counts in the low tens, not thousands (a thousands spike = breaker should fire = key bug).
- **DONE 29.6.2026 ~20:51 UTC** — after shadow evidence (tiny per-agency fractions, 30% breaker fired
  correctly) + 2 live partner spot-checks (Vi La Ut on NauSys, Eleonora on MMK), flipped to LIVE via
  the systemd ExecStart `-D` flag (NOT an env var). Live deletion drains the 96k mapping-less +
  duplicate backlog over normal cycles, within the per-agency 30% breaker. Verified first live run.

### cusma3 systemd state (server-only ops config — NOT in git; recorded here for reproducibility)
Current live `ExecStart` in `/etc/systemd/system/boat4youscheduler.service`:
```
ExecStart=java -Xmx2048m -Dreconcile.shadow-mode=false -jar /home/cusma3/boat4you/webservice.jar
```
- `-Xmx2048m` — heap cap (from the 29.6 sync-freq deploy; was 6144m). Backup: `~/boat4youscheduler.service.bak.6144`.
- `-Dreconcile.shadow-mode=false` — reconcile in LIVE delete mode. Backup: `~/boat4youscheduler.service.bak.shadow`.
- REVERT reconcile to shadow (deletes nothing): drop the `-D` flag → `daemon-reload` → restart.

### Verify (post-deploy)
- Vi La Ut (yacht 4736) week 08–15.08.2026 shows bookable on the site; DB has no res for that week.
- `AvailabilityIntegrityDetectorJob` (06:40 daily) logs: contradictions → trending to ~0, mapping-less
  → trending down, duplicate partner-ids → 0. WARN if contradictions > 25.
- Reservation count snapshot before/after the shadow flip must drop only by the projected shadow count.
