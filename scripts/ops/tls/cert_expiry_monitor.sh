#!/bin/bash
# TLS certificate monitor (cusma3). Created 18.9.2026.
# WHY: Let's Encrypt stopped sending expiry-warning e-mails in June 2025, so a certbot / cPanel
# AutoSSL renewal that fails silently would only be noticed when a site goes down.
# Checks, per public hostname: certificate received, chain trusted, NAME MATCHES, days left.
# Threshold is relative to the certificate's own lifetime (alert under 1/4 of it left), because
# lifetimes are being cut (90 d today, 47 d by 2029) and a fixed day count would go stale:
# LE renews with 1/3 left, so under 1/4 means renewal has already been failing for days.
# Silent when healthy. A weekly heartbeat (elapsed-time based, not weekday based) proves the
# monitor itself is alive. Exit code is non-zero when an alert could NOT be delivered, so
# `systemctl --failed` shows it - silence must never mean "healthy" by accident.
# Manual run:  cert_expiry_monitor.sh --test   (always mails the current table)
set -u
ENV_FILE=/home/cusma3/boat4you/boat4youscheduler_vars.env
STATE_DIR=/home/cusma3/bin
TO="mkuzmani@gmail.com"
HEARTBEAT_DAYS=7
# host[:port] - port defaults to 443. Every public hostname we serve (each verified 18.9.2026 to
# present a certificate valid for its own name), plus the mail host on SMTPS.
# NOT listed on purpose: webmail./cpanel./autodiscover.croatia-yachting.com - cPanel AutoSSL does
# not cover them (they serve another name's certificate). Add them once that is fixed.
HOSTS="www.boat4you.com boat4you.com api.boat4you.com admin.boat4you.com wp.boat4you.com mail.boat4you.com webmail.boat4you.com cpanel.boat4you.com autodiscover.boat4you.com
www.europe-yachts.com europe-yachts.com wp.europe-yachts.com mail.europe-yachts.com webmail.europe-yachts.com cpanel.europe-yachts.com autodiscover.europe-yachts.com
www.croatia-yachting.com croatia-yachting.com wp.croatia-yachting.com mail.croatia-yachting.com
www.catamaran-croatia-charter.com catamaran-croatia-charter.com wp.catamaran-croatia-charter.com mail.catamaran-croatia-charter.com webmail.catamaran-croatia-charter.com cpanel.catamaran-croatia-charter.com autodiscover.catamaran-croatia-charter.com
www.catamaran-charter-greece.com catamaran-charter-greece.com wp.catamaran-charter-greece.com mail.catamaran-charter-greece.com webmail.catamaran-charter-greece.com cpanel.catamaran-charter-greece.com autodiscover.catamaran-charter-greece.com
www.catamarancharteritaly.com catamarancharteritaly.com wp.catamarancharteritaly.com mail.catamarancharteritaly.com webmail.catamarancharteritaly.com cpanel.catamarancharteritaly.com autodiscover.catamarancharteritaly.com
www.catamaran-charter-caribbean.com catamaran-charter-caribbean.com wp.catamaran-charter-caribbean.com mail.catamaran-charter-caribbean.com webmail.catamaran-charter-caribbean.com cpanel.catamaran-charter-caribbean.com autodiscover.catamaran-charter-caribbean.com
www.adriapixel.com adriapixel.com
cusmanich.hr www.cusmanich.hr
mail.boat4you.com:465"

get() { grep "^$1=" "$ENV_FILE" | cut -d= -f2- | sed "s/^'//;s/'\$//"; }
SMTP_USER=$(get MAIL_SERVER_USERNAME)
SMTP_PASS=$(get MAIL_SERVER_PASSWORD)
SMTP_HOST=$(get MAIL_SERVER_HOST)
SMTP_PORT=$(get MAIL_SERVER_PORT)

# Prints "<days>|<lifetime days>|<notAfter>|<verify>" or "ERR|<reason>" for one host[:port].
probe() {
  local hp="$1" host port out end start verify end_s start_s now_s
  host="${hp%%:*}"; port="${hp##*:}"; [ "$port" = "$hp" ] && port=443
  out=$(echo | timeout 10 openssl s_client -connect "$host:$port" -servername "$host" -verify_hostname "$host" 2>&1)
  end=$(printf '%s\n' "$out" | openssl x509 -noout -enddate 2>/dev/null | cut -d= -f2)
  [ -z "$end" ] && { echo "ERR|no certificate received (connect/handshake failed)"; return; }
  start=$(printf '%s\n' "$out" | openssl x509 -noout -startdate 2>/dev/null | cut -d= -f2)
  verify=$(printf '%s\n' "$out" | grep -m1 "Verify return code" | sed 's/.*Verify return code: //')
  end_s=$(date -d "$end" +%s 2>/dev/null) || { echo "ERR|cannot parse notAfter '$end'"; return; }
  start_s=$(date -d "$start" +%s 2>/dev/null) || start_s=$end_s
  now_s=$(date +%s)
  echo "$(( (end_s - now_s) / 86400 ))|$(( (end_s - start_s) / 86400 ))|$end|${verify:-unknown}"
}

NL=$'\n'
problems=""; table=""; soonest=""; soonest_host="n/a"; checked=0; total=$(echo $HOSTS | wc -w)

send_mail() {
  local subj="$1" body="$2" try
  for try in 1 2 3; do
    if printf 'From: %s\nTo: %s\nSubject: %s\nDate: %s\nMessage-ID: <%s.%s@cusma3.boat4you.com>\nMIME-Version: 1.0\nContent-Type: text/plain; charset=UTF-8\nContent-Transfer-Encoding: 8bit\n\n%s\n' \
        "$SMTP_USER" "$TO" "$subj" "$(date -R)" "$(date +%s)" "$$" "$body" \
      | curl -sS --max-time 60 --ssl-reqd "smtps://$SMTP_HOST:$SMTP_PORT" \
          --mail-from "$SMTP_USER" --mail-rcpt "$TO" -T - \
          -K <(printf 'user = "%s:%s"\n' "$SMTP_USER" "$SMTP_PASS"); then   # -K: password stays off argv (ps)
      return 0
    fi
    sleep 30
  done
  return 1
}

FOOT="Monitor: /home/cusma3/bin/cert_expiry_monitor.sh (cert-expiry-monitor.timer, daily 08:31 UTC). Alarm: manje od 1/4 trajanja certifikata, neispravno ime ili lanac, host nedostupan.
Obnova: certbot.timer na cusma1/cusma2/cusma5 (sudo certbot renew --dry-run); wp.*, mail.*, webmail.*, cpanel.*, autodiscover.* = cPanel AutoSSL na server.cusmanich.hr."

# If the unit is stopped mid-run (timeout), still say what was found instead of dying silently.
on_term() {
  send_mail "[TLS ALERT] monitor prekinut nakon $checked/$total hostova" "Monitor je prekinut prije kraja (timeout?). Do tada pronadjeno:${NL}${problems:-(nista)}${NL}${table}${NL}$FOOT"
  exit 2
}
trap on_term TERM INT

for hp in $HOSTS; do
  r=$(probe "$hp")
  # one retry: a single dropped connection must not page anyone
  case "$r" in ERR*) sleep 5; r=$(probe "$hp");; esac
  checked=$((checked + 1))
  case "$r" in
    ERR*) problems="$problems- $hp: ${r#ERR|}${NL}"; table="$table$(printf '%-46s %s' "$hp" "${r#ERR|}")${NL}";;
    *)
      IFS='|' read -r days life end verify <<<"$r"
      warn=$(( life / 4 )); [ "$warn" -lt 2 ] && warn=2
      table="$table$(printf '%-46s %4s d od %3s  (%s)  %s' "$hp" "$days" "$life" "$end" "$verify")${NL}"
      if [ -z "$soonest" ] || [ "$days" -lt "$soonest" ]; then soonest=$days; soonest_host=$hp; fi
      [ "$days" -lt "$warn" ] && problems="$problems- $hp: certifikat istjece za $days dana ($end), prag $warn d - obnova NE radi${NL}"
      case "$verify" in "0 (ok)") ;; *) problems="$problems- $hp: provjera certifikata nije prosla: $verify${NL}";; esac
      ;;
  esac
done
trap - TERM INT

rc=0
HB="$STATE_DIR/cert_expiry_monitor.heartbeat"
hb_age=$(( ( $(date +%s) - $(stat -c %Y "$HB" 2>/dev/null || echo 0) ) / 86400 ))
if [ -n "$problems" ]; then
  send_mail "[TLS ALERT] problem s certifikatom" "Problemi:${NL}$problems${NL}Svi hostovi:${NL}$table${NL}$FOOT" || rc=1
elif [ "${1:-}" = "--test" ] || [ "$hb_age" -ge "$HEARTBEAT_DAYS" ]; then
  if send_mail "[TLS OK] heartbeat - $checked hostova, najblizi istek ${soonest:-n/a} d ($soonest_host)" "Svi certifikati su valjani.${NL}${NL}$table${NL}$FOOT"; then
    touch "$HB"
  else rc=1; fi
fi
# machine-readable last result for whoever looks on the box
printf '%s\n%s' "$(date -u +%FT%TZ) checked=$checked/$total soonest=${soonest:-n/a} host=$soonest_host problems=$([ -n "$problems" ] && echo yes || echo no) mail_rc=$rc" "$table" > "$STATE_DIR/cert_expiry_monitor.last" 2>/dev/null
[ "$rc" -ne 0 ] && echo "ALERT MAIL COULD NOT BE DELIVERED" >&2
exit $rc
