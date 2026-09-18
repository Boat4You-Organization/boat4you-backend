#!/usr/bin/env python3
"""Idempotent nginx edits for the 18.9.2026 TLS hygiene pass.
usage: tls_edit.py <mode> <conf> [out]   modes: www80 (cusma1) | http2 (cusma2) | hsts | hsts-nosub (cusma5 sister vhost)
Writes to <out> (default: in place). Exits 3 on any failed assertion, 0 when applied or already applied."""
import re, sys
mode, path = sys.argv[1], sys.argv[2]
out = sys.argv[3] if len(sys.argv) > 3 else path
c = open(path).read()

def fail(msg):
    print('ASSERT FAILED:', msg); sys.exit(3)

if mode == 'www80':
    if 'if ($host = www.boat4you.com)' in c:
        print('already applied'); sys.exit(0)
    anchor = 'server {\n    if ($host = boat4you.com) {'
    if c.count(anchor) != 1: fail('apex port-80 block anchor')
    block = ('# http://www had NO port-80 server block, so it fell through to the default server and\n'
             '# answered 404 instead of redirecting (found 18.9.2026). Same if/return shape certbot writes\n'
             '# for the other hosts; [::]:80 is listed too so an IPv6 http-01 challenge cannot fall through\n'
             '# to the default server the day this zone gets an AAAA record.\n'
             'server {\n'
             '    if ($host = www.boat4you.com) {\n'
             '        return 301 https://$host$request_uri;\n'
             '    } # managed by Certbot\n\n'
             '    server_name www.boat4you.com;\n\n'
             '    listen 80;\n'
             '    listen [::]:80;\n'
             '    return 404; # managed by Certbot\n'
             '}\n\n')
    c = c.replace(anchor, block + anchor, 1)

elif mode == 'http2':
    if 'listen 443 ssl http2;' in c:
        print('already applied'); sys.exit(0)
    old = '    listen 443 ssl; # managed by Certbot'
    if c.count(old) != 1: fail('listen 443 line')
    # nginx 1.24: `listen ... http2` is the valid form (the separate `http2 on;` directive is 1.25.1+).
    c = c.replace(old, '    listen 443 ssl http2; # managed by Certbot', 1)

elif mode in ('hsts', 'hsts-nosub'):
    sub = mode == 'hsts'
    HSTS = 'add_header Strict-Transport-Security "max-age=31536000%s" always;' % ('; includeSubDomains' if sub else '')
    changed = 0
    # 1) main www 443 server block: right after its server_name line (the file's first block)
    m = re.match(r'server \{\n(    server_name www\.[a-z0-9.-]+;\n)', c)
    if not m: fail('first block is not the www 443 server')
    first_block_end = c.find('\nserver {', 1)
    if 'Strict-Transport-Security' not in c[:c.find('    location ')]:
        if sub:
            note = ('    # HSTS (18.9.2026). includeSubDomains is safe here: every subdomain of this zone (wp, mail,\n'
                    '    # webmail, cpanel, autodiscover) was verified to serve a certificate VALID FOR ITS OWN NAME,\n'
                    '    # and the daily cert_expiry_monitor on cusma3 watches all of them.\n')
        else:
            note = ('    # HSTS (18.9.2026) WITHOUT includeSubDomains on purpose: webmail./cpanel./autodiscover. of this\n'
                    '    # zone serve a certificate for a DIFFERENT name (cPanel AutoSSL does not cover them), so\n'
                    '    # includeSubDomains would hard-fail them in browsers for a year. Add it once AutoSSL covers them.\n')
        c = c.replace(m.group(1), m.group(1) + note + '    ' + HSTS + '\n', 1); changed += 1
    # 2) the /wp-content/ location defines its own add_header lines, which REPLACE the server-level list
    loc = '        add_header X-Cache-Status $upstream_cache_status;\n'
    if c.count(loc) != 1: fail('wp-content add_header anchor')
    i = c.find(loc)
    if 'Strict-Transport-Security' not in c[c.rfind('location ', 0, i):i]:
        c = c.replace(loc, '        ' + HSTS + '\n' + loc, 1); changed += 1
    # 3) apex 443 block (only 301s to www) — `always` makes add_header apply to the redirect too
    m2 = re.search(r'(server \{\n    listen 443 ssl http2;\n    server_name [a-z0-9.-]+;\n)', c)
    if not m2: fail('apex 443 block')
    if 'Strict-Transport-Security' not in c[m2.end():c.find('}', m2.end())]:
        c = c.replace(m2.group(1), m2.group(1) + '    ' + HSTS + '\n', 1); changed += 1
    if c.count('Strict-Transport-Security') != 3: fail('expected exactly 3 HSTS lines, found %d' % c.count('Strict-Transport-Security'))
    if changed == 0:
        print('already applied'); sys.exit(0)
else:
    fail('unknown mode')
open(out, 'w').write(c)
print('ok:', mode)
