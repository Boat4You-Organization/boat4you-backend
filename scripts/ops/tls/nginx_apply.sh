#!/bin/bash
# usage (as root): nginx_apply.sh <sha256-of-editor> <tag> <mode>:<conf> [<mode>:<conf> ...]
# Backs up OUTSIDE nginx's include paths, edits, nginx -t, reload; restores every file on ANY failure.
set -u
SHA=$1; TAG=$2; shift 2
ED=/tmp/tls_edit.py
echo "$SHA  $ED" | sha256sum -c --status || { echo "editor checksum mismatch - refusing to run it as root"; exit 2; }
install -m 700 -o root -g root "$ED" /root/tls_edit.py; ED=/root/tls_edit.py
BDIR=/var/backups/nginx/$TAG; mkdir -p "$BDIR"
for e in "$@"; do f=${e#*:}; cp -p "$f" "$BDIR/$(basename "$f")" || exit 1; done
rollback() { for e in "$@"; do f=${e#*:}; cp -p "$BDIR/$(basename "$f")" "$f"; done; echo "ROLLED BACK from $BDIR"; }
for e in "$@"; do m=${e%%:*}; f=${e#*:}; printf '%s ' "$(basename "$f")"; python3 "$ED" "$m" "$f" || { rollback "$@"; exit 1; }; done
if ! T=$(nginx -t 2>&1); then echo "$T" | tail -3; rollback "$@"; nginx -t 2>&1 | tail -1; exit 1; fi
echo "$T" | tail -1
if systemctl reload nginx; then echo "RELOADED (nginx $(systemctl is-active nginx)); backups in $BDIR"; else echo "RELOAD FAILED"; rollback "$@"; systemctl reload nginx; exit 1; fi
rm -f /root/tls_edit.py
