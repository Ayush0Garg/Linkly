// Link creation load test: the write path (validation, id from the sequence, insert, cache eviction).
//   k6 run -e BASE=http://localhost:8080 -e OUT=result.json load-test/create.js
import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

const BASE = __ENV.BASE || 'http://localhost:8080';
const RATES = (__ENV.RATES || '25,50,100,200,300').split(',').map(Number);
const STEP = Number(__ENV.STEP_SECONDS || 20);
const GAP = Number(__ENV.GAP_SECONDS || 8);
const OUT = __ENV.OUT || 'create-summary.json';
const created = new Counter('links_created');

const scenarios = {};
const thresholds = {};
RATES.forEach((rate, i) => {
  const name = `rps_${rate}`;
  scenarios[name] = { executor: 'constant-arrival-rate', rate, timeUnit: '1s', duration: `${STEP}s`, startTime: `${i * (STEP + GAP)}s`, preAllocatedVUs: Math.max(10, rate), maxVUs: 1000, exec: 'create' };
  thresholds[`http_req_duration{scenario:${name}}`] = ['max>=0'];
  thresholds[`http_req_failed{scenario:${name}}`] = ['rate>=0'];
  thresholds[`http_reqs{scenario:${name}}`] = ['count>=0'];
  thresholds[`links_created{scenario:${name}}`] = ['count>=0'];
});
export const options = { scenarios, thresholds, discardResponseBodies: true, summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'p(99.9)', 'max'] };

export function setup() {
  const jar = http.cookieJar();
  http.get(`${BASE}/api/auth/csrf`);
  const token = (jar.cookiesForURL(BASE)['XSRF-TOKEN'] || [''])[0];
  const reg = http.post(`${BASE}/api/auth/register`, JSON.stringify({ email: `create${Date.now()}@example.com`, password: 'correct horse battery' }),
    { headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': token } });
  if (reg.status !== 201) throw new Error(`register failed: ${reg.status} ${reg.body}`);
  // the access cookie is scoped to /api: ask for the cookies that /api would receive
  return { cookie: `access_token=${jar.cookiesForURL(`${BASE}/api/`)['access_token'][0]}; XSRF-TOKEN=${token}`, token };
}

export function create(data) {
  const res = http.post(`${BASE}/api/links`, JSON.stringify({ longUrl: `https://example.com/p/${__VU}-${__ITER}-${Date.now()}` }),
    { headers: { 'Content-Type': 'application/json', Cookie: data.cookie, 'X-XSRF-TOKEN': data.token }, tags: { name: 'create' } });
  if (check(res, { 'is 201': (x) => x.status === 201 })) created.add(1);
}

export function handleSummary(data) {
  const v = (name) => (data.metrics[name] ? data.metrics[name].values : undefined);
  const steps = RATES.map((rate) => {
    const t = v(`http_req_duration{scenario:rps_${rate}}`) || {};
    const sent = (v(`http_reqs{scenario:rps_${rate}}`) || {}).count || 0;
    return {
      targetRps: rate, achievedRps: Math.round((sent / STEP) * 10) / 10, requests: sent,
      created: (v(`links_created{scenario:rps_${rate}}`) || {}).count || 0,
      failedPct: Math.round(((v(`http_req_failed{scenario:rps_${rate}}`) || {}).rate || 0) * 10000) / 100,
      latencyMs: { avg: t.avg, p50: t.med, p90: t['p(90)'], p95: t['p(95)'], p99: t['p(99)'], max: t.max },
    };
  });
  return { [OUT]: JSON.stringify({ stepSeconds: STEP, steps }, null, 2) };
}
