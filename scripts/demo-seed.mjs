/**
 * Fills the LOCAL dev database with realistic click history so the dashboard has something to show.
 *
 *   1. Start the backend:      ./mvnw spring-boot:run -Dspring-boot.run.profiles=embedded,local
 *   2. Create an account and a few links (in the UI, or through the API)
 *   3. Run, from this folder:  npm install && node demo-seed.mjs
 *
 * It connects to the embedded Postgres on localhost:5433, so it only works with the `embedded,local` profile while the
 * backend is running. It backdates your links and adds ~45 days of weekday-shaped traffic (countries, devices,
 * browsers, referrers, some bots). Run it once: it refuses if history already exists unless you pass --force.
 * Add --dry-run to see what it would do without writing anything.
 */
import crypto from 'node:crypto';
import pg from 'pg';

const DRY = process.argv.includes('--dry-run');
const FORCE = process.argv.includes('--force');
const db = new pg.Client({ host: 'localhost', port: 5433, user: 'postgres', database: 'postgres' });

try {
  await db.connect();
} catch (e) {
  console.error('Could not connect to the dev database on localhost:5433.\nIs the backend running with the "embedded,local" profiles?');
  process.exit(1);
}

const { rows: links } = await db.query('SELECT id FROM links ORDER BY id');
if (links.length === 0) {
  console.error('There are no links yet. Create a few in the app first, then run this again.');
  process.exit(1);
}
const { rows: [{ count: history }] } = await db.query("SELECT count(*)::int FROM click_events WHERE clicked_at < date_trunc('day', now())");
if (history > 0 && !FORCE) {
  console.error(`The database already has ${history} historical clicks. Running again would double them. Use --force to add anyway.`);
  process.exit(1);
}

// Deterministic, so two runs on fresh databases look the same.
let seed = 20261014;
const rnd = () => ((seed = (seed * 1664525 + 1013904223) >>> 0) / 2 ** 32);
const pick = (pairs) => { let r = rnd() * pairs.reduce((s, p) => s + p[1], 0); for (const [v, w] of pairs) { if ((r -= w) <= 0) return v; } return pairs[0][0]; };

const COUNTRIES = [['IN', 38], ['US', 22], ['GB', 9], ['DE', 8], ['BR', 6], ['FR', 5], ['CA', 4], ['AU', 3], [null, 5]];
const DEVICES = [['mobile', 58], ['desktop', 36], ['tablet', 6]];
const BROWSERS = [['Chrome', 55], ['Safari', 25], ['Firefox', 8], ['Edge', 7], ['Samsung Internet', 3], [null, 2]];
const OSES = {
  mobile: [['Android', 62], ['iOS', 38]], tablet: [['iOS', 60], ['Android', 40]],
  desktop: [['Windows', 52], ['macOS', 30], ['Linux', 14], [null, 4]],
};
const REFERRERS = [[null, 40], ['google.com', 18], ['t.co', 12], ['reddit.com', 8], ['news.ycombinator.com', 6], ['linkedin.com', 6], ['facebook.com', 5], ['mail.google.com', 3], ['duckduckgo.com', 2]];
// Age, daily volume and trend per link (cycled if you have more than four).
const SHAPES = [{ age: 44, base: 34, trend: 0.012 }, { age: 30, base: 26, trend: 0.03 }, { age: 18, base: 15, trend: -0.02 }, { age: 9, base: 22, trend: 0 }];

const now = new Date();
const dayStart = (ago) => new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), now.getUTCDate() - ago));
const months = new Set();
const events = [];
const daily = new Map();

links.forEach(({ id }, idx) => {
  const shape = SHAPES[idx % SHAPES.length];
  for (let ago = shape.age; ago >= 1; ago--) {
    const day = dayStart(ago);
    const weekend = [0, 6].includes(day.getUTCDay()) ? 0.55 : 1;
    let n = shape.base * weekend * (1 + shape.trend * (shape.age - ago)) * (0.7 + rnd() * 0.6);
    if (idx % SHAPES.length === 3 && ago === 4) n *= 4.5; // one busy day
    n = Math.max(0, Math.round(n));
    const visitors = Math.max(1, Math.round(n * 0.72));
    for (let i = 0; i < n; i++) {
      const at = new Date(day.getTime() + Math.floor(rnd() * 86_400_000));
      const bot = rnd() < 0.07;
      const device = bot ? 'bot' : pick(DEVICES);
      events.push({
        id, at, bot, country: pick(COUNTRIES), device,
        browser: bot ? null : pick(BROWSERS), os: bot ? null : pick(OSES[device]),
        referrer: bot ? null : pick(REFERRERS),
        visitor: crypto.createHash('md5').update(`${day.toISOString().slice(0, 10)}:${Math.floor(rnd() * visitors)}`).digest('hex'),
      });
      months.add(at.toISOString().slice(0, 7));
      const key = `${id}|${day.toISOString().slice(0, 10)}`;
      const counts = daily.get(key) ?? [0, 0];
      counts[bot ? 1 : 0]++;
      daily.set(key, counts);
    }
  }
});

console.log(`${links.length} link(s) -> ${events.length} clicks over ${daily.size} link-days${DRY ? ' (dry run, nothing written)' : ''}`);
if (DRY) process.exit(0);

// Same DDL the app's PartitionManager runs.
for (const m of months) {
  const [y, mo] = m.split('-').map(Number);
  const from = new Date(Date.UTC(y, mo - 1, 1)).toISOString().slice(0, 10);
  const to = new Date(Date.UTC(y, mo, 1)).toISOString().slice(0, 10);
  await db.query(`CREATE TABLE IF NOT EXISTS click_events_y${y}m${String(mo).padStart(2, '0')} PARTITION OF click_events FOR VALUES FROM ('${from} 00:00:00+00') TO ('${to} 00:00:00+00')`);
}
for (let i = 0; i < events.length; i += 400) {
  const chunk = events.slice(i, i + 400);
  const params = [];
  const values = chunk.map((e, j) => {
    params.push(e.id, e.at.toISOString(), e.country, e.device, e.browser, e.os, e.referrer, e.visitor, e.bot);
    const o = j * 9;
    return `($${o + 1},$${o + 2},$${o + 3},$${o + 4},$${o + 5},$${o + 6},$${o + 7},$${o + 8},$${o + 9})`;
  });
  await db.query(`INSERT INTO click_events (link_id, clicked_at, country, device, browser, os, referrer_host, visitor_hash, bot) VALUES ${values.join(',')}`, params);
}
for (const [key, [clicks, bots]] of daily) {
  const [id, day] = key.split('|');
  await db.query(
    `INSERT INTO link_daily_stats (link_id, day, clicks, bot_clicks) VALUES ($1,$2,$3,$4)
     ON CONFLICT (link_id, day) DO UPDATE SET clicks = link_daily_stats.clicks + $3, bot_clicks = link_daily_stats.bot_clicks + $4`,
    [id, day, clicks, bots]);
}
for (const [idx, { id }] of links.entries()) {
  await db.query("UPDATE links SET created_at = now() - ($2 || ' days')::interval WHERE id = $1", [id, String(SHAPES[idx % SHAPES.length].age)]);
}
await db.end();
console.log('Done. Open the dashboard and pick 30 or 90 days.');
