# Boat4You — Production Readiness Design (Faza 7 scope)

**Datum:** 2026-05-26
**Autor:** Mario + Claude (Opus 4.7)
**Sesija context:** Post-incident 2026-05-20 FE swap-thrash; backend review Faze 1-6 zatvorene (PR #1 merged u `main`, 92 nalaza FIXED); CDN za slike u pripremi; bez load test baseline-a.
**Prethodni spec:** [`docs/superpowers/specs/2026-05-07-boat4you-prod-review-design.md`](./2026-05-07-boat4you-prod-review-design.md) (definira hibridna fix policy, severity table, commit format)
**Faza 7 scope:** final verification + production go-live readiness, NE samo SUMMARY.md.

---

## 1. Cilj i ne-cilj

**Cilj:** dovesti Boat4You u stanje deploy-spreme po realnim mjerilima (QE + security pen-test + senior dev lens), s mjerljivim acceptance kriterijima i dokumentiranom go/no-go odlukom.

**Ne-cilj:**
- Refaktoriranje cijelog code-base-a (tehnički dug ostaje u REGISTER-u za Faza 2 review iteraciju)
- Full performance suite ili capacity model (samo smoke baseline, Approach B iz brainstorming odluke)
- Frontend code review (boat4you-web-main je odvojen codebase, izvan opsega ovog review-a — ali FE memory pattern iz incident-a 2026-05-20 ulazi kao operational concern)
- Phase 7 nginx batch je već _planirаn_ u prethodnom spec-u; ovaj dokument konkretizira execution

---

## 2. Trenutno stanje (snapshot 2026-05-26)

**Backend review (`boat4you-ws-main`, branch `main`):**
- Phase 1-6 zatvorene, PR #1 merged
- **92 nalaza FIXED**, 94 OPEN
- **Realnih blokera za deploy:** 1 CRIT (F2-043) + 7 HIGH (od kojih 3 NauSys pair, 3 nginx pair, 1 Dockerfile uvjetan, 1 tar.gz verify)
- Build baseline: `compileKotlin` clean, detekt 291 weighted issues (pre-existing), 29/103 tests fail (pre-existing F1-074)

**Frontend (cusma1, `boat4you-web-main`):**
- 6 Next.js instanci (portovi 3001-3006)
- 2026-05-20 incident: `boat4you.com` (port 3001) — swap thrash, PID u D state, accept queue 328/511. Riješeno `systemctl restart nextapp`. Underlying memory pattern neistraženo.
- 4GB RAM ukupno, već tijesno

**Deploy topologija:** systemd na VM-ovima (container NE ide u prod, potvrđeno).

**NauSys partner:** HTTPS endpoint dostupan (potvrđeno).

---

## 3. Architecture & topologija

```
┌──────────────────────────┐         ┌──────────────────────────┐
│  cusma1 (91.98.209.180)  │         │  cusma2                  │
│  ─────────────────────   │         │  ─────────────────────   │
│  FRONTEND server         │  HTTPS  │  BACKEND server          │
│                          │ ──────► │                          │
│  nginx :80/:443          │         │  nginx :80/:443          │
│   ├─ boat4you.com → 3001 │         │   └─ api.boat4you.com    │
│   ├─ caribbean    → 3002 │         │       → 127.0.0.1:8080   │
│   ├─ italy        → 3003 │         │                          │
│   ├─ europe       → 3004 │         │  boat4you.service        │
│   ├─ croatia      → 3005 │         │   (Java/Spring Boot)     │
│   └─ greece       → 3006 │         │   :8080 (local only)     │
│                          │         │                          │
│  6× next-server          │         │  ⇩ JDBC                  │
│  nextapp.service         │         └─────────┬────────────────┘
└──────────────────────────┘                   │
                                               ▼
                                  ┌──────────────────────────┐
                                  │  VM4: PostgreSQL 18      │
                                  └──────────────────────────┘
                                               │
                                               ▼
                                  ┌──────────────────────────┐
                                  │  NauSys partner          │
                                  │  ws.nausys.com (HTTPS)   │
                                  └──────────────────────────┘
```

**Per-fix mapping na server:**

| Fix | Server | Konkretno gdje |
|---|---|---|
| F2-043 FLYWAY_TARGET_VERSION verify | cusma2 | systemd env za `boat4you.service` |
| F6-003 tar.gz verify | lokalno (laptop) | repo root `boat4you-ws-perf-update.tar.gz` |
| F1-037 + F3-003 + F3-009 NauSys HTTPS | cusma2 | systemd env za `boat4you.service`, `NAUSYS_URL=https://...` |
| F1-003 + F1-022 + F1-053 nginx batch | cusma2 | `/etc/nginx/conf.d/api.boat4you.com.conf` (TBC iz lookup-a) |
| F7-FE-001 / F7-FE-002 monitoring | cusma1 | systemd PID watch + cron logger |
| Smoke load baseline | laptop → cusma2 | k6 sa scenarijima, output u repo |

---

## 4. Inventar posla — blocker list

### 4.1 Verified blockers (must fix/verify pre deploy)

| ID | Severity | Naslov | Effort | Tip |
|---|---|---|---|---|
| F2-043 | CRIT | Verify `FLYWAY_TARGET_VERSION` env var (V9_xx test data NE smije runnati) | 5min | OPS |
| F6-003 | HIGH | Verify content `boat4you-ws-perf-update.tar.gz` (298MB, mogući PII/cred leak) | 10min | OPS |
| F1-037 | HIGH | NauSys URL default `http://` → `https://` | 5min | OPS (env) |
| F3-003 | HIGH | NauSys credentials u plaintext body (riješi se s F1-037) | (paired) | OPS (env) |
| F3-009 | HIGH | PII (customer name/crew) u plaintext NauSys body (riješi se s F1-037) | (paired) | OPS (env) |
| F1-003 | HIGH | nginx per-IP rate limit za auth endpointe | 15min | OPS (nginx) |
| F1-022 | HIGH | nginx strip client `X-Forwarded-For` (inače F1-003 zaobiđen) | 5min | OPS (nginx, pair) |
| F1-053 | MED | nginx HSTS header (staged max-age=300 → 31536000 nakon 24h) | 5min | OPS (nginx, pair) |
| F6-011 | LOW→ELEVATED | JVM heap dump on OOM flags za `boat4you.service` (forensics ako backend OOM-i) | 5min | OPS (systemd, pair s NauSys) |
| F7-FE-002 | HIGH | Monitor FE memory growth post-restart (3-7 dana) | passive | OPS (FE) |

### 4.2 Conditional / partner-side (DEFERRED dependent)

| ID | Severity | Naslov | Reason |
|---|---|---|---|
| F6-001 | HIGH | Dockerfile no `USER` directive | DEFER — container ne ide u prod, systemd only |

### 4.3 Out-of-scope za ovaj deploy

Svi ostali nalazi iz REGISTER-a (Phase 1-6 OPEN MED + LOW) ostaju kao tehnički dug. Klasifikacija u REGISTER-u ostaje, ne mijenja se kroz Faza 7.

### 4.4 Novi nalazi iz 2026-05-20 incident-a (FE)

Sljedeći se filiraju u Faza 7 čak iako se odnose na drugu codebazu (boat4you-web-main):

| ID | Severity | Naslov | Status |
|---|---|---|---|
| F7-FE-001 | CRIT (ops) | 4GB RAM premali za 6 Next.js instanci — observed swap thrash 2026-05-20 | OPEN — capacity planning |
| F7-FE-002 | HIGH | Suspected memory leak: PID 3877875 VIRT 11.3g / RSS 749MB / 1d 18h uptime | OPEN — monitoring required |
| F7-FE-003 | MED | SSR `TypeError: Cannot read properties of undefined (reading 'id' / '0')` | OPEN — defensive coding gap |
| F7-FE-004 | LOW | `Failed to find Server Action "x"` post-deploy (stale client bundles) | OPEN — cosmetic |

---

## 5. Sequencing approach (Approach 1: baseline-before-and-after)

```
1. VERIFY (read-only)        ~15min  Steps 1a-1b
2. BASELINE A                ~1h     Smoke load test "current state"
3. BACKEND DEPLOY            ~15min  NauSys HTTPS env + restart
4. NGINX DEPLOY              ~25min  Rate limit + XFF + HSTS u 1 reload-u
5. FUNCTIONAL SMOKE          ~15min  Manual checklist
6. BASELINE B                ~1h     Isti scenarij kao Baseline A
7. MONITORING                3-7d    Passive FE memory watch + service health
```

Rationale: before/after baseline daje QE-grade dokaz da su fix-evi performance-neutralni; svaki step ima nezavisni rollback; sve concrete commands; serial diagnostics (memory rule).

---

## 6. Per-step plan

### Step 1 — VERIFY (~15min)

#### 1a. F2-043 — FLYWAY_TARGET_VERSION na cusma2

**CMD:**
```bash
sudo systemctl show boat4you --property=EnvironmentFiles
sudo systemctl show boat4you --property=Environment | tr ' ' '\n' | grep -iE 'flyway|target'
# zatim, ovisno o nalazu:
sudo grep -i flyway <env-file-path>
```

**VERIFY DB stanje (na cusma2 ako PG client postoji, ili kroz DB tool):**
```sql
SELECT version, description, success, installed_on
FROM flyway_schema_history
WHERE version LIKE 'V9%' OR version = '9'
ORDER BY installed_rank DESC;
```

**Outcomes:**
- ✅ `FLYWAY_TARGET_VERSION=<broj < 9>` postavljen, V9 redovi NEMA u `flyway_schema_history` → GO
- ⚠️ Env var prazan ali V9 redovi NEMA → fix env var, NO immediate prod incident
- 🔴 V9 redovi POSTOJE u `flyway_schema_history` → **Workspace.hr test useri u prod-u**, F2-043 escalation: identify accounts (`SELECT id, email, role FROM user_account WHERE email LIKE '%@workspace.hr' OR email LIKE '%@boat4you.com';`), disable/delete, audit recent activity, rotate any shared bcrypt hash users

**ROLLBACK:** N/A (read-only)

#### 1b. F6-003 — tar.gz contents (lokalno)

**CMD:**
```bash
cd /c/Users/A/source/boat4you-ws-main/boat4you-ws-main
ls -la boat4you-ws-perf-update.tar.gz
tar -tzf boat4you-ws-perf-update.tar.gz | head -100
tar -tzf boat4you-ws-perf-update.tar.gz | wc -l
# sumnja na sensitive content:
tar -xzOf boat4you-ws-perf-update.tar.gz 2>/dev/null | head -500 | grep -iE 'password|secret|token|key|@.*\.(com|hr)' | head
```

**Outcomes:**
- ✅ Build artefakti / JAR-ovi / public docs → dodaj u `.gitignore`, delete iz working tree, commit `[F6-003] move build artifact out of repo root`
- 🔴 Sadrži `.env`, credentials, customer PII → **history scrub** (BFG ili `git filter-repo`), force push (eksplicitni OK Mario), rotate sve credential-e koji su bili exposed

**ROLLBACK:** N/A pre commit

---

### Step 2 — BASELINE A (~1h)

**Alat:** k6 (kategorija "constant-arrival-rate", deterministic RPS)

**Install:**
```bash
winget install k6 --source winget
k6 version  # expected: k6 v0.x.x
```

**Scenarij `baseline.js`** (saved to `docs/superpowers/perf/baseline.js`):

```javascript
import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
  scenarios: {
    yacht_list: {
      executor: 'constant-arrival-rate',
      rate: 10, timeUnit: '1s', duration: '2m',
      preAllocatedVUs: 20,
    },
    yacht_detail: {
      executor: 'constant-arrival-rate',
      rate: 5, timeUnit: '1s', duration: '2m',
      preAllocatedVUs: 10, exec: 'detail',
    },
    image_proxy: {
      executor: 'constant-arrival-rate',
      rate: 5, timeUnit: '1s', duration: '2m',
      preAllocatedVUs: 10, exec: 'image',
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<3000', 'p(99)<10000'],
  },
};

const BASE = 'https://api.boat4you.com';

export default function () {
  const r = http.get(`${BASE}/public/yachts?locations=Croatia&did=c-54&page=0&size=9`);
  check(r, { 'status 200': (r) => r.status === 200 });
  sleep(0.5);
}

export function detail() {
  const r = http.get(`${BASE}/public/yachts/lagoon-lagoon-52-linahanna-13610`);
  check(r, { 'status 200': (r) => r.status === 200 });
}

export function image() {
  const r = http.get(`${BASE}/public/image/164510?width=1200`);
  check(r, { 'status 200': (r) => r.status === 200 });
}
```

**Run:**
```bash
mkdir -p docs/superpowers/perf
k6 run --out json=docs/superpowers/perf/baseline-a-$(date +%Y%m%d-%H%M).json baseline.js \
  | tee docs/superpowers/perf/baseline-a-$(date +%Y%m%d-%H%M).txt
```

**Što hvatamo:** `http_req_duration` p50/p95/p99, `http_req_failed` rate, `iterations` total, `vus_max`.

**Scheduling:** low-traffic prozor (recommendation: subota ujutro CET, mali realan promet, neće smetati korisnicima na 20 RPS-a).

**ROLLBACK:** N/A

---

### Step 3 — BACKEND DEPLOY: NauSys HTTPS + JVM OOM flags (~20min)

**Pair fix:** F1-037/F3-003/F3-009 (NauSys HTTPS) + F6-011 (JVM heap dump on OOM). Jedan service restart pokriva oba.

#### 3a. Prepare heap dump directory

```bash
# Heapdump direktorij (write-able od strane usera koji vrti service):
sudo mkdir -p /var/log/boat4you/heapdumps
SVC_USER=$(sudo systemctl show boat4you --property=User --value)
[ -z "$SVC_USER" ] && SVC_USER=cusma2  # fallback
sudo chown "$SVC_USER":"$SVC_USER" /var/log/boat4you/heapdumps
sudo chmod 750 /var/log/boat4you/heapdumps
df -h /var/log/boat4you/  # potvrdi ≥ 5GB free (heap dump može biti 2-4GB)
```

#### 3b. F1-037 / F3-003 / F3-009 — NauSys URL HTTPS env

```bash
ENVFILE=$(sudo systemctl show boat4you --property=EnvironmentFiles --value | awk '{print $1}')
echo "Env file: $ENVFILE"
sudo cp "$ENVFILE" "${ENVFILE}.bak.$(date +%s)"

# preview:
sudo grep -E "NAUSYS_URL|NAUSYS_HOST" "$ENVFILE"

# edit:
sudo nano "$ENVFILE"
# NAUSYS_URL=http://ws.nausys.com → NAUSYS_URL=https://ws.nausys.com
```

#### 3c. F6-011 — JVM heap dump flags u systemd

**Identify ExecStart line:**
```bash
sudo systemctl cat boat4you | grep -E "^ExecStart"
```

**Opcija A — drop-in override (preferirano, ne dira originalni unit file):**
```bash
sudo systemctl edit boat4you
```
U editor-u dodaj (override datoteka):
```ini
[Service]
# F6-011: OOM forensics
Environment="JAVA_OOM_FLAGS=-XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/var/log/boat4you/heapdumps -XX:+ExitOnOutOfMemoryError"
```

Pa **modificiraj ExecStart** da koristi `$JAVA_OOM_FLAGS` env var ako original podržava (provjeri konkretan ExecStart prije).

**Opcija B — direkt edit unit file (ako override pristup ne radi):**
```bash
sudo systemctl cat boat4you
# pronađi path .service datoteke (npr. /etc/systemd/system/boat4you.service)
sudo cp /etc/systemd/system/boat4you.service /etc/systemd/system/boat4you.service.bak.$(date +%s)
sudo nano /etc/systemd/system/boat4you.service
# dodaj u ExecStart liniju, prije -jar argumenta:
#   -XX:+HeapDumpOnOutOfMemoryError
#   -XX:HeapDumpPath=/var/log/boat4you/heapdumps
#   -XX:+ExitOnOutOfMemoryError
```

#### 3d. Apply oba (NauSys env + JVM flags) u jednom restart-u

```bash
sudo systemctl daemon-reload
sudo systemctl restart boat4you
```

#### 3e. VERIFY

```bash
# service started OK:
sudo systemctl status boat4you
sudo journalctl -u boat4you --since "2 min ago" --no-pager | head -30

# JVM started with new flags (provjeri u logu ili kroz ps):
ps -ef | grep '[b]oat4you' | head -1
# expected output sadrži: -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/var/log/boat4you/heapdumps -XX:+ExitOnOutOfMemoryError

# NauSys HTTPS u logu nakon prvog sync-a:
sudo journalctl -u boat4you -f | grep -iE "nausys|external_system"
# expected: https:// URL-evi, statusi 200, bez SSL handshake errors
```

**ROLLBACK:**
```bash
# env file:
sudo cp "${ENVFILE}.bak.<timestamp>" "$ENVFILE"
# systemd override:
sudo systemctl revert boat4you   # ili obriši override datoteku
# ili ako Opcija B:
sudo cp /etc/systemd/system/boat4you.service.bak.<ts> /etc/systemd/system/boat4you.service
# pa:
sudo systemctl daemon-reload
sudo systemctl restart boat4you
```

---

### Step 4 — NGINX DEPLOY (~25min)

**Pre-req:** identify config file na cusma2 (jedan od lookup-a):
```bash
sudo grep -l "api.boat4you.com" /etc/nginx/conf.d/*.conf /etc/nginx/sites-enabled/* 2>/dev/null
```

**Backup:**
```bash
CONF=/etc/nginx/conf.d/api.boat4you.com.conf  # POTVRDI pravi path
sudo cp "$CONF" "${CONF}.bak.$(date +%s)"
```

**Promjene (3 dijela u istom diff-u):**

```nginx
# (1) HTTP context — na vrhu vhost-a ili u /etc/nginx/nginx.conf http{} bloku:
limit_req_zone $binary_remote_addr zone=auth_limit:10m rate=5r/m;

# (2) U server { } bloku, na razini server-a:
add_header Strict-Transport-Security "max-age=300; includeSubDomains" always;
proxy_set_header X-Forwarded-For $remote_addr;   # REPLACES klijent-controlled XFF
# napomena: drugi proxy_set_header direktivi ostaju kako su bili

# (3) U location bloku za auth endpointe (DODAJ novi ili modificiraj postojeći):
location ~ ^/auth/(login|register|reset-password|reset|invite) {
    limit_req zone=auth_limit burst=10 nodelay;
    limit_req_status 429;

    proxy_pass http://127.0.0.1:8080;
    proxy_set_header Host $host;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_set_header X-Forwarded-For $remote_addr;
    # ... ostali postojeći proxy_set_header direktivi
}
```

**Validate + apply:**
```bash
sudo nginx -t  # MORA biti "syntax is ok" + "test is successful"
sudo systemctl reload nginx
sudo systemctl status nginx
```

**VERIFY (3 testa):**
```bash
# HSTS:
curl -sI https://api.boat4you.com/public/yachts?locations=Croatia | grep -i strict-transport
# expected: Strict-Transport-Security: max-age=300; includeSubDomains

# XFF spoof rejected:
curl -sI -H "X-Forwarded-For: 1.2.3.4" https://api.boat4you.com/public/yachts?locations=Croatia
# na cusma2:
sudo tail -1 /var/log/nginx/access.log
# expected: real client IP, NE 1.2.3.4

# Rate limit:
for i in $(seq 1 10); do
  curl -s -o /dev/null -w "%{http_code}\n" \
    -X POST https://api.boat4you.com/auth/login \
    -d 'email=x@x.com&password=wrong'
done
# expected: ~5x 4xx, zatim 429
```

**HSTS staged rollout:** ostavi `max-age=300` za prvih 24h. Nakon potvrde da sve radi → vrati u config i postavi `max-age=31536000`, nginx reload.

**ROLLBACK:**
```bash
sudo cp "${CONF}.bak.<timestamp>" "$CONF"
sudo nginx -t && sudo systemctl reload nginx
```

---

### Step 5 — FUNCTIONAL SMOKE (~15min)

Manual checklist u browser-u (NE automatizirano):

- [ ] `https://boat4you.com` — homepage učitava bez greške
- [ ] Search "Croatia" → lista yachti se prikazuje
- [ ] Klik na yacht detail page → renderira se s fotografijama
- [ ] Login (test account) — uspješan
- [ ] Trigger reset-password 6× brzo iz iste IP-jevog requesta → 6. zahtjev returns 429
- [ ] Admin login (SYSTEM_ADMIN account) → ulazi u admin panel
- [ ] DevTools → Network → response header `Strict-Transport-Security` je prisutan na `api.boat4you.com` response-u
- [ ] Mobile browser test (telefon na 4G, ne WiFi) — homepage učitava

---

### Step 6 — BASELINE B (~1h)

**Identičan scenariji kao Step 2:**
```bash
k6 run --out json=docs/superpowers/perf/baseline-b-$(date +%Y%m%d-%H%M).json baseline.js \
  | tee docs/superpowers/perf/baseline-b-$(date +%Y%m%d-%H%M).txt
```

**Compare:**
```bash
# brzo poređenje (po grep-anim metrikama):
grep -E "http_req_duration|http_req_failed|iterations|vus_max" \
  docs/superpowers/perf/baseline-a-*.txt \
  docs/superpowers/perf/baseline-b-*.txt
```

**Acceptance (po §7 decision matrix):**
- p95 delta ≤ 20% — ZELENO
- p95 delta 20-50% — ŽUTO, investigate ali ne blokira
- p95 delta > 50% — CRVENO, rollback last change

---

### Step 7 — MONITORING (3-7 dana)

**Cron logger na cusma1:**
```bash
sudo tee /usr/local/bin/nextapp-mem-log.sh > /dev/null <<'EOF'
#!/bin/bash
ts=$(date +%s)
for port in 3001 3002 3003 3004 3005 3006; do
  pid=$(ss -tlnp 2>/dev/null | awk -v p=":$port" '$4 ~ p {print $0}' | grep -oP 'pid=\K[0-9]+' | head -1)
  [ -n "$pid" ] && rss=$(ps -p "$pid" -o rss= 2>/dev/null | tr -d ' ') && echo "$ts $port $pid $rss"
done
EOF
sudo chmod +x /usr/local/bin/nextapp-mem-log.sh
(sudo crontab -l 2>/dev/null; echo "*/5 * * * * /usr/local/bin/nextapp-mem-log.sh >> /var/log/nextapp-mem.log 2>&1") | sudo crontab -
```

**Daily review (manual, ~5min):**
```bash
# RSS rast po procesu:
awk '{print $2, $4}' /var/log/nextapp-mem.log | sort -k1 -n -k2 -n | uniq -c | head -50
# swap status:
free -h | tail -1
```

**Exit criteria (po §7 decision matrix):**
- **Day 3:** RSS rast < 30 MB/dan/proces → leak NE postoji, samo tight server, output capacity recommendation
- **Day 3-7:** RSS rast > 50 MB/dan/proces → leak potvrđen, otvori F7-FE-002 evidence, eskalacija (heap profile)
- **Bilo kada:** swap > 80% ili D state → repeat incident, **escalate** (auto restart ili capacity bump)

---

## 7. Acceptance & go/no-go decision matrix

| Stavka | 🟢 Zeleno | 🟡 Žuto | 🔴 Crveno |
|---|---|---|---|
| F2-043 FLYWAY env | env var postavljen, DB potvrđuje V9_xx NIJE runnata | env postavljen, DB nije provjerena | V9 redovi u `flyway_schema_history` |
| F6-003 tar.gz | content benign + u `.gitignore` | debug/build artefakti, ne PII | `.env` / creds / customer PII |
| NauSys HTTPS | restart OK, sync logs `https://`, no SSL errors | restart OK, sync nije pokrenut još | service fail OR SSL handshake fail |
| JVM OOM flags (F6-011) | `ps -ef \| grep boat4you` pokazuje sva 3 -XX flag-a, heapdump dir 750 perm + write-able + ≥5GB free | flagovi prisutni, dir provjera ne odrađena | flag missing, ili dir read-only/full |
| nginx XFF strip | spoof test: access log NE 1.2.3.4 | spoof prošla, ali rate-limit radi | spoof prošla I rate-limit zaobiđen |
| nginx rate limit | 10 brzih POST → 429 nakon ~5-tog | 429 ne dosljedno | nikad 429 |
| nginx HSTS | header prisutan, max-age=300 → 24h test → 31536000 | header max-age=300 testno | header missing ili 31536000 odmah |
| Baseline A→B perf | p95 delta ≤ 20%, error rate ≤ 0.01 | p95 delta 20-50% | p95 delta > 50% ILI error > 1% |
| FE memory monitor | Day 3: RSS rast < 30 MB/dan | RSS rast 30-50 MB/dan | RSS > 50 MB/dan ILI swap > 80% ILI D state |

**Go-live odluka:**
- **Sve 🟢 → GO** (deploy ready, formalizirati Phase 7 SUMMARY.md)
- **1 🟡 → GO sa scheduled follow-up** (track u REGISTER kao OPS-VERIFY, deploy nije blokiran)
- **≥2 🟡 ILI bilo koje 🔴 → NO-GO** (vrati se na blocker, ne idi naprijed)

---

## 8. Risk register

| Rizik | Vjerojatnost | Impact | Mitigation | Detection |
|---|---|---|---|---|
| NauSys HTTPS endpoint ne podržava sve operacije | LOW (Mario potvrdio HTTPS) | HIGH | 30min smoke window prije sync-a; rollback ready | `journalctl -u boat4you -f` u smoke prozoru |
| nginx `limit_req` blokira legitimne mobilne klijente | MED | MED | `burst=10 nodelay` permissive; monitor 429 rate prvih 24h | `grep 429 /var/log/nginx/access.log \| wc -l` |
| HSTS max-age=300 lockira test usere u HTTPS-only | LOW | LOW | 300s = 5min, sami se pomire | N/A |
| Baseline A udari peak hours | MED | LOW | Schedule low-traffic prozor (Sb ujutro) | Operator schedule |
| Mary tijekom monitoring-a otkrije pravi memory leak bez tooling-a | MED | HIGH | Pre-install `clinic.js` na cusma1 (bonus step) | RSS rast > 50 MB/dan |
| Server pukne usred Baseline A (4GB RAM tight) | LOW (samo 20 RPS) | MED | Konzervativni rate; ako FE 502 — STOP baseline | k6 error rate |
| Mario solo dev, deploy fails u 22h | OPS reality | HIGH | Schedule deploy u radno vrijeme | N/A — schedule discipline |
| `api.boat4you.com` config nije gdje očekujemo | LOW | LOW | Lookup u §1 prije izvođenja | `grep -l api.boat4you.com` |

**Escalation flow ako uđemo u crveno:**
1. Rollback current step (komanda već poznata)
2. Verify rollback success (smoke test isti scenario)
3. Update REGISTER s findingom što se desilo
4. Pauziraj dalje fix-eve, dijagnosticiraj
5. Replaniraj sequence

---

## 9. Phase 7 closure protocol

Po prethodnom spec-u (Phase 7 = final verification + SUMMARY.md). Specifični deliverables ovog spec-a:

### Files koji se kreiraju/ažuriraju:

| File | Action | When |
|---|---|---|
| `docs/superpowers/specs/2026-05-26-boat4you-prod-readiness-design.md` | CREATE (ovaj dokument) | Sad |
| `docs/superpowers/findings/phase-7-prod-readiness.md` | CREATE | Tijekom izvođenja: F7-FE-001..F7-FE-004 + per-step verification rezultati |
| `docs/superpowers/findings/REGISTER.md` | UPDATE | Svaki fix premjestiti iz OPEN u FIXED s commit hash-om |
| `docs/superpowers/perf/baseline-a-<ts>.{json,txt}` | CREATE | Step 2 |
| `docs/superpowers/perf/baseline-b-<ts>.{json,txt}` | CREATE | Step 6 |
| `docs/superpowers/perf/baseline.js` | CREATE | Pre Step 2 |
| `docs/superpowers/findings/SUMMARY.md` | CREATE | Po završetku, kombinira sve faze |

### Commit poruke (per spec section 6.4):

| Step | Commit message |
|---|---|
| 1a F2-043 verify | `[F2-043] verify FLYWAY_TARGET_VERSION env in prod` (može biti samo REGISTER update + log u finding-u) |
| 1b F6-003 cleanup | `[F6-003] remove boat4you-ws-perf-update.tar.gz from repo root` (+ `.gitignore` update) |
| 3 NauSys HTTPS + JVM OOM | `[F1-037][F3-003][F3-009][F6-011] NauSys https env + JVM heap dump flags` (ops-side, REGISTER update with operational note) |
| 4 nginx batch | `[F1-003][F1-022][F1-053] nginx auth rate-limit + XFF strip + HSTS staged` (ops-side, document config diff in finding) |
| 2/6 baseline | `[F7-perf] capture baseline-A and baseline-B for prod-readiness deploy` |
| 7 monitoring | `[F7-FE-002] install nextapp memory cron logger` |

### Push policy

**Push na origin tek nakon eksplicitnog OK-a po push-u** (kao i u prethodnim fazama). Force-push: nikad bez "force OK" za specifični kontekst.

---

## 10. Reference

- Prethodni spec: [`docs/superpowers/specs/2026-05-07-boat4you-prod-review-design.md`](./2026-05-07-boat4you-prod-review-design.md)
- REGISTER: [`docs/superpowers/findings/REGISTER.md`](../findings/REGISTER.md)
- Phase 1: [`docs/superpowers/findings/phase-1-boundary.md`](../findings/phase-1-boundary.md)
- Phase 4 (memory leak F4-009 context): [`docs/superpowers/findings/phase-4-jobs-native.md`](../findings/phase-4-jobs-native.md)
- Memory rule: serial diagnostics only (network adapter crash mitigation)
- Fix policy: hibridna — LOW trivijalni odmah, MED/HIGH eksplicitni OK (per `feedback_review_workflow.md`)

---

**Status:** OPEN za execution. Sljedeći korak: izvoditi Step 1 — VERIFY (read-only, no risk).
