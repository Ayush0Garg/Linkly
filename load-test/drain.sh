#!/usr/bin/env bash
# How fast can ONE app instance's click consumer store a backlog, with nothing else competing for the CPU?
#
#   load-test/drain.sh [clicks=150000]
#
# Method: start the app, create an account and 200 links, stop the app; write N click entries straight into the Redis
# stream (hot links get most of them, like real traffic); start the app again and watch the stream empty. The result is
# clicks stored per second, plus where each batch spends its time (the clicks.stream.stage timers).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"; cd "$ROOT"
N="${1:-150000}"; LT_REDIS_PORT="${LT_REDIS_PORT:-6393}"; export REDIS_PORT="$LT_REDIS_PORT"
STAMP="$(date +%Y%m%d-%H%M%S)"; OUT="load-test/results/$STAMP-drain"; mkdir -p "$OUT"
JAR="backend/target/shortener-0.0.1-SNAPSHOT.jar"; DATA="$(mktemp -d)"; BASE="http://localhost:8080"
CLI="${LINKLY_HOME:-$HOME/.local/share/linkly}/redis-env/bin/redis-cli -p $LT_REDIS_PORT"
args=(--spring.profiles.active=embedded "--app.embedded-postgres.data-dir=$DATA/pg" --app.embedded-postgres.port=5434 "--app.redis.url=redis://localhost:$LT_REDIS_PORT")
[ -f backend/.dev-data/geoip/dbip.mmdb ] && args+=(--app.analytics.geoip-db=backend/.dev-data/geoip/dbip.mmdb)
scripts/dev-redis.sh start >/dev/null; $CLI flushall >/dev/null
PID=""; cleanup() { [ -n "$PID" ] && kill "$PID" 2>/dev/null || true; sleep 3; scripts/dev-redis.sh stop >/dev/null 2>&1 || true; rm -rf "$DATA"; }; trap cleanup EXIT
start_app() { java -Xms1g -Xmx1g -jar "$JAR" "${args[@]}" > "$1" 2>&1 & PID=$!; for _ in $(seq 1 90); do curl -sf -o /dev/null "$BASE/actuator/health" && break; sleep 1; done; }
stop_app() { kill "$PID"; for _ in $(seq 1 30); do kill -0 "$PID" 2>/dev/null || break; sleep 1; done; PID=""; }

start_app "$OUT/backend-setup.log"
J="$(mktemp)"; curl -s -c "$J" -o /dev/null "$BASE/api/auth/csrf"; X="$(awk '/XSRF/{print $7}' "$J")"
curl -s -b "$J" -c "$J" -o /dev/null -H "X-XSRF-TOKEN: $X" -H 'Content-Type: application/json' -d '{"email":"drain@example.com","password":"correct horse battery"}' "$BASE/api/auth/register"
FIRST=""; for i in $(seq 1 200); do
  id=$(curl -s -b "$J" -H "X-XSRF-TOKEN: $X" -H 'Content-Type: application/json' -d "{\"longUrl\":\"https://example.com/d/$i\"}" "$BASE/api/links" | python3 -c "import json,sys;print(json.load(sys.stdin)['id'])")
  [ -z "$FIRST" ] && FIRST=$id
done
stop_app
python3 - "$FIRST" "$N" "$OUT/backlog.resp" <<'PY'
import random, sys, time, uuid, hashlib
first, n, out = int(sys.argv[1]), int(sys.argv[2]), sys.argv[3]
random.seed(11); now = int(time.time() * 1000)
agents = ['Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36',
 'Mozilla/5.0 (iPhone; CPU iPhone OS 17_2 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.2 Mobile/15E148 Safari/604.1',
 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.2 Safari/605.1.15',
 'Mozilla/5.0 (X11; Linux x86_64; rv:121.0) Gecko/20100101 Firefox/121.0',
 'Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)']
countries = ['IN','US','GB','DE','BR','FR','CA','AU']; refs = ['','','google.com','t.co','reddit.com']
def resp(*parts):
    b = [f'*{len(parts)}\r\n'.encode()]
    for p in parts:
        p = p if isinstance(p, bytes) else str(p).encode()
        b.append(f'${len(p)}\r\n'.encode() + p + b'\r\n')
    return b''.join(b)
with open(out, 'wb') as f:
    for _ in range(n):
        link = first + int(200 * random.random() ** 3)            # skewed: a few links take most of the traffic
        at = now - random.randint(0, 3 * 86400 * 1000)
        vh = hashlib.md5(str(random.random()).encode()).hexdigest()
        parts = ['XADD', 'linkly:clicks', '*', 'v', '1', 'id', str(uuid.uuid4()), 'link', link, 'at', at, 'vh', vh,
                 'c', random.choice(countries), 'ua', random.choice(agents)]
        r = random.choice(refs)
        if r: parts += ['ref', r]
        f.write(resp(*parts))
PY
$CLI --pipe < "$OUT/backlog.resp" | tail -1
rm -f "$OUT/backlog.resp"   # 50 MB of generated input: not worth keeping
echo "backlog in Redis: $($CLI xlen linkly:clicks) entries"
T0=$(date +%s.%N); start_app "$OUT/backend.log"
STARTED=$(date +%s.%N)
for _ in $(seq 1 3000); do
  L=$($CLI xlen linkly:clicks); echo "$(date +%s.%N) $L" >> "$OUT/samples.txt"
  [ "$L" = "0" ] && break; sleep 0.5
done
END=$(date +%s.%N)
curl -sf "$BASE/actuator/prometheus" > "$OUT/metrics-after.txt"
python3 - "$OUT" "$N" "$STARTED" "$END" <<'PY'
import re, sys
from pathlib import Path
out, n, started, end = Path(sys.argv[1]), int(sys.argv[2]), float(sys.argv[3]), float(sys.argv[4])
samples = [tuple(map(float, l.split())) for l in (out / 'samples.txt').read_text().splitlines() if l.strip()]
# the consumer starts a moment after the app is up: measure from the first sample where the backlog starts to fall
first = next((t for t, left in samples if left < n), samples[0][0])
elapsed = end - first
m = {}
for line in (out / 'metrics-after.txt').read_text().splitlines():
    g = re.match(r'^([a-z_]+(?:\{[^}]*\})?) ([0-9.eE+-]+)$', line)
    if g: m[g.group(1)] = float(g.group(2))
stored = m.get('clicks_written_total', 0)
# Wall-clock timing is unreliable here (the app is already draining by the time the first sample is taken), so the
# headline is the consumer's own busy time: the sum of what its read / parse / write / ack stages measured.
busy = sum(m.get(f'clicks_stream_stage_seconds_sum{{stage="{s}"}}', 0) for s in ('read', 'parse', 'write', 'ack'))
lines = [f"### drain / {n:,} clicks", "",
         f"- stored {stored:,.0f} of {n:,} clicks with {busy:.1f} s of consumer work: **{stored / busy:,.0f} clicks/s** per consumer thread",
         f"- (wall clock from the first sample: {elapsed:.1f} s, a head start of unknown size, so not used)",
         f"- duplicates recognised: {m.get('clicks_duplicates_total', 0):.0f}, lost: {m.get('clicks_lost_total', 0):.0f}, dead-lettered: {m.get('clicks_stream_dead_total', 0):.0f}", "",
         "| stage | batches | mean ms per batch | total s |", "|---|---:|---:|---:|"]
for stage in ('read', 'parse', 'write', 'ack'):
    c = m.get(f'clicks_stream_stage_seconds_count{{stage="{stage}"}}', 0); s = m.get(f'clicks_stream_stage_seconds_sum{{stage="{stage}"}}', 0)
    lines.append(f"| {stage} | {c:.0f} | {s / c * 1000 if c else 0:.1f} | {s:.1f} |")
(out / 'summary.md').write_text('\n'.join(lines) + '\n'); print('\n'.join(lines))
PY
echo "results in $OUT"
