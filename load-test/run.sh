#!/usr/bin/env bash
# Runs one configuration ("variant") of the server under load and records what happened.
#
#   load-test/run.sh <variant> [redirect|create ...]
#
# variants
#   redis-off         no Redis: every redirect reads Postgres, clicks use the in-process queue
#   redis-on          Redis cache for redirects + the durable click stream (the normal production setup)
#   cache-only        Redis cache, but clicks use the in-process queue (isolates what the stream costs)
#   redis-on-limits   as redis-on, with rate limiting switched on at limits too high to ever trigger (isolates its overhead)
#
# Tunables (env): RATES=500,1000,... STEP_SECONDS=20 LINKS=1000 DRAIN_SECONDS=60 (the longest to wait for the click pipeline to catch up)
# The generator (k6), the app, Postgres and Redis all share this one machine, which is why the report also records the CPU
# each of them used: that, unlike throughput, does not depend on how busy the generator was.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"; cd "$ROOT"
VARIANT="${1:?usage: load-test/run.sh <redis-off|redis-on|cache-only|redis-on-limits> [redirect|create ...]}"; shift || true
SCENARIOS=("${@:-redirect}")
STAMP="$(date +%Y%m%d-%H%M%S)"; OUT="load-test/results/$STAMP-$VARIANT"; mkdir -p "$OUT"
LT_REDIS_PORT="${LT_REDIS_PORT:-6393}"; export REDIS_PORT="$LT_REDIS_PORT"
K6="$(load-test/install-k6.sh)"
JAR="backend/target/shortener-0.0.1-SNAPSHOT.jar"
[ -f "$JAR" ] || (cd backend && ./mvnw -q -DskipTests package)
DATA="$(mktemp -d)"; BASE="http://localhost:8080"; HZ="$(getconf CLK_TCK)"

args=(--spring.profiles.active=embedded "--app.embedded-postgres.data-dir=$DATA/pg" --app.embedded-postgres.port=5434)
[ -f backend/.dev-data/geoip/dbip.mmdb ] && args+=(--app.analytics.geoip-db=backend/.dev-data/geoip/dbip.mmdb)
STARTED_REDIS=0
case "$VARIANT" in
  redis-off) ;;
  redis-on|cache-only|redis-on-limits)
    args+=("--app.redis.url=redis://localhost:$LT_REDIS_PORT")
    [ "$VARIANT" = cache-only ] && args+=(--app.analytics.stream.enabled=false)
    if [ "$VARIANT" = redis-on-limits ]; then
      args+=(--app.rate-limit.enabled=true)
      for p in auth-ip auth-email links-create qr redirect; do args+=("--app.rate-limit.policies.$p.limit=100000000" "--app.rate-limit.policies.$p.degraded-limit=100000000"); done
    fi
    scripts/dev-redis.sh start >/dev/null; STARTED_REDIS=1
    scripts/dev-redis.sh status >/dev/null || { echo "Redis is not answering on port $LT_REDIS_PORT" >&2; exit 1; } ;;
  *) echo "unknown variant: $VARIANT" >&2; exit 1 ;;
esac

BACKEND_PID=""
cleanup() {
  [ -n "$BACKEND_PID" ] && kill "$BACKEND_PID" 2>/dev/null || true
  sleep 3
  [ "$STARTED_REDIS" = 1 ] && scripts/dev-redis.sh stop >/dev/null 2>&1 || true
  rm -rf "$DATA"
}
trap cleanup EXIT

ticks() { local t=0 p; for p in "$@"; do [ -r "/proc/$p/stat" ] && t=$((t + $(awk '{print $14+$15}' "/proc/$p/stat"))); done; echo "$t"; }
pg_pids() { local pm; pm="$(head -1 "$DATA/pg/postmaster.pid" 2>/dev/null || true)"; [ -n "$pm" ] && echo "$pm $(pgrep -P "$pm" | tr '\n' ' ')"; }
redis_pid() { cat "${LINKLY_HOME:-$HOME/.local/share/linkly}/redis-$LT_REDIS_PORT.pid" 2>/dev/null || true; }

java -Xms1g -Xmx1g -jar "$JAR" "${args[@]}" > "$OUT/backend.log" 2>&1 &
BACKEND_PID=$!
for _ in $(seq 1 90); do curl -sf -o /dev/null "$BASE/actuator/health" && break; sleep 1; done
curl -sf "$BASE/actuator/health" | grep -q '"status"' || { echo "backend did not start, see $OUT/backend.log" >&2; exit 1; }

{
  echo "variant:   $VARIANT"; echo "started:   $(date -Is)"
  echo "machine:   $(nproc) cores, $(lscpu | awk -F: '/Model name/{gsub(/^ +/,"",$2); print $2}'), $(free -m | awk '/Mem:/{print $2}') MB RAM"
  echo "load avg:  $(cut -d' ' -f1-3 /proc/loadavg) (before the run)"
  echo "java:      $(java -version 2>&1 | head -1)   flags: -Xms1g -Xmx1g"
  echo "k6:        $($K6 version | head -1)"
  echo "app args:  ${args[*]}"
} > "$OUT/environment.txt"

for scenario in "${SCENARIOS[@]}"; do
  J0=$(ticks "$BACKEND_PID"); P0=$(ticks $(pg_pids)); R0=$(ticks $(redis_pid)); T0=$(date +%s)
  curl -sf "$BASE/actuator/prometheus" > "$OUT/$scenario-metrics-before.txt" || true
  "$K6" run --quiet -e BASE="$BASE" -e OUT="$OUT/$scenario-summary.json" "load-test/$scenario.js" > "$OUT/$scenario-k6.log" 2>&1 || echo "k6 reported a failed threshold or error (see $OUT/$scenario-k6.log)"
  curl -sf "$BASE/actuator/prometheus" > "$OUT/$scenario-metrics-after.txt" || true
  J1=$(ticks "$BACKEND_PID"); P1=$(ticks $(pg_pids)); R1=$(ticks $(redis_pid)); T1=$(date +%s)
  printf '{"wallSeconds": %d, "cpuSeconds": {"app": %s, "postgres": %s, "redis": %s}}\n' "$((T1-T0))" \
    "$(awk "BEGIN{print ($J1-$J0)/$HZ}")" "$(awk "BEGIN{print ($P1-$P0)/$HZ}")" "$(awk "BEGIN{print ($R1-$R0)/$HZ}")" > "$OUT/$scenario-server.json"
  python3 load-test/summarize.py "$OUT" "$scenario" | tee "$OUT/$scenario-summary.md"
done
echo "results in $OUT"
