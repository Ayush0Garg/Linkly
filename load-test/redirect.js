// Redirect load test: how much traffic can GET /{code} take, and what does it cost in latency?
//
// One run walks through increasing request rates (constant arrival rate: the generator sends N requests per second
// whether or not the server keeps up, so a slow server shows up as latency and dropped requests, not as a politely
// slowed-down test). Afterwards it checks the bookkeeping: did every redirect that was answered end up as a stored click?
//
//   k6 run -e BASE=http://localhost:8080 -e OUT=result.json load-test/redirect.js
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Gauge } from 'k6/metrics';

const BASE = __ENV.BASE || 'http://localhost:8080';
const LINKS = Number(__ENV.LINKS || 1000);
const RATES = (__ENV.RATES || '500,1000,2000,3000,4000,5000').split(',').map(Number);
const STEP = Number(__ENV.STEP_SECONDS || 20);
const GAP = Number(__ENV.GAP_SECONDS || 8);
const WARMUP = Number(__ENV.WARMUP_SECONDS || 15);
const DRAIN = Number(__ENV.DRAIN_SECONDS || 60);
const OUT = __ENV.OUT || 'redirect-summary.json';

const redirectsOk = new Counter('redirects_ok');
const storedClicks = new Gauge('stored_clicks');

const scenarios = {
  warmup: { executor: 'constant-arrival-rate', rate: 300, timeUnit: '1s', duration: `${WARMUP}s`, preAllocatedVUs: 20, maxVUs: 200, exec: 'redirect' },
};
const thresholds = {};
RATES.forEach((rate, i) => {
  const name = `rps_${rate}`;
  scenarios[name] = {
    executor: 'constant-arrival-rate', rate, timeUnit: '1s', duration: `${STEP}s`,
    startTime: `${WARMUP + 5 + i * (STEP + GAP)}s`, preAllocatedVUs: Math.max(20, Math.ceil(rate / 40)), maxVUs: 3000, exec: 'redirect',
  };
  // a threshold on a tagged submetric is what makes k6 report that submetric (so the per-rate numbers exist)
  thresholds[`http_req_duration{scenario:${name}}`] = ['max>=0'];
  thresholds[`http_req_failed{scenario:${name}}`] = ['rate>=0'];
  thresholds[`http_reqs{scenario:${name}}`] = ['count>=0'];
  thresholds[`dropped_iterations{scenario:${name}}`] = ['count>=0'];
  thresholds[`redirects_ok{scenario:${name}}`] = ['count>=0'];
});

export const options = {
  scenarios,
  thresholds,
  discardResponseBodies: true,
  setupTimeout: '180s',
  teardownTimeout: `${DRAIN + 60}s`,
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'p(99.9)', 'max'],
};

const AGENTS = [
  'Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36',
  'Mozilla/5.0 (iPhone; CPU iPhone OS 17_2 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.2 Mobile/15E148 Safari/604.1',
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
  'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.2 Safari/605.1.15',
  'Mozilla/5.0 (X11; Linux x86_64; rv:121.0) Gecko/20100101 Firefox/121.0',
  'Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)',
];
const REFERERS = ['', '', '', 'https://www.google.com/search?q=x', 'https://t.co/abc', 'https://www.reddit.com/r/java/'];

const r = (n) => Math.floor(Math.random() * n);

export function setup() {
  const jar = http.cookieJar();
  http.get(`${BASE}/api/auth/csrf`);
  const xsrf = () => (jar.cookiesForURL(BASE)['XSRF-TOKEN'] || [''])[0];
  // bodies are discarded globally (to keep the generator light); setup and teardown need theirs
  const json = (token) => ({ headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': token }, responseType: 'text' });
  const reg = http.post(`${BASE}/api/auth/register`, JSON.stringify({ email: `load${Date.now()}@example.com`, password: 'correct horse battery' }), json(xsrf()));
  if (reg.status !== 201) throw new Error(`register failed: ${reg.status} ${reg.body}`);

  const codes = [];
  for (let i = 0; i < LINKS; i++) {
    const res = http.post(`${BASE}/api/links`, JSON.stringify({ longUrl: `https://example.com/landing/${i}?utm_source=load` }), json(xsrf()));
    if (res.status !== 201) throw new Error(`create link ${i} failed: ${res.status} ${res.body}`);
    codes.push(res.json('code'));
  }
  // the access cookie is scoped to /api (so redirects never carry credentials): ask for the cookies that /api would receive
  const cookies = jar.cookiesForURL(`${BASE}/api/`);
  return { codes, cookie: `access_token=${cookies['access_token'][0]}` };
}

export function redirect(data) {
  // Traffic is heavily skewed in real life: a few links get most clicks. u^3 puts ~46% of requests on the top 10% of links.
  const code = data.codes[Math.floor(data.codes.length * Math.pow(Math.random(), 3))];
  const res = http.get(`${BASE}/${code}`, {
    redirects: 0,
    headers: {
      'User-Agent': AGENTS[r(AGENTS.length)],
      // the app trusts X-Forwarded-For from loopback, so each request looks like a different visitor (and gets a country)
      'X-Forwarded-For': `${1 + r(222)}.${r(256)}.${r(256)}.${1 + r(254)}`,
      ...(REFERERS[r(REFERERS.length)] ? { Referer: REFERERS[r(REFERERS.length)] } : {}),
    },
    tags: { name: 'redirect' },
  });
  if (check(res, { 'is a 302': (x) => x.status === 302 })) redirectsOk.add(1);
}

export function teardown(data) {
  // Ask the app how many clicks it holds (humans + bots, all of this user's links) until the number stops growing:
  // that is the moment the click pipeline has caught up. DRAIN_SECONDS is the longest we are willing to wait.
  let last = -1;
  let waited = 0;
  while (waited <= DRAIN) {
    const res = http.get(`${BASE}/api/stats/overview?days=7`, { headers: { Cookie: data.cookie }, responseType: 'text' });
    if (res.status === 200) {
      const totals = res.json('totals');
      const now = totals.clicks + totals.botClicks;
      if (now === last) break;
      last = now;
    }
    sleep(2);
    waited += 2;
  }
  if (last >= 0) storedClicks.add(last);
  console.log(`click pipeline settled after ~${waited}s of waiting; ${last} clicks stored`);
}

export function handleSummary(data) {
  const v = (name) => (data.metrics[name] ? data.metrics[name].values : undefined);
  const steps = RATES.map((rate) => {
    const t = v(`http_req_duration{scenario:rps_${rate}}`) || {};
    const sent = (v(`http_reqs{scenario:rps_${rate}}`) || {}).count || 0;
    return {
      targetRps: rate,
      achievedRps: Math.round((sent / STEP) * 10) / 10,
      requests: sent,
      ok302: (v(`redirects_ok{scenario:rps_${rate}}`) || {}).count || 0,
      failedPct: Math.round(((v(`http_req_failed{scenario:rps_${rate}}`) || {}).rate || 0) * 10000) / 100,
      droppedIterations: (v(`dropped_iterations{scenario:rps_${rate}}`) || {}).count || 0,
      latencyMs: { avg: t.avg, p50: t.med, p90: t['p(90)'], p95: t['p(95)'], p99: t['p(99)'], p999: t['p(99.9)'], max: t.max },
    };
  });
  const result = {
    stepSeconds: STEP, links: LINKS, steps,
    totalRedirectsAnswered: (v('redirects_ok') || {}).count || 0,
    clicksStoredAfterDrain: (v('stored_clicks') || {}).value,
  };
  return { [OUT]: JSON.stringify(result, null, 2) };
}
