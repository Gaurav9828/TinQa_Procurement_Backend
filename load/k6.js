/**
 * Load, stress, spike and soak tests for the Procurement API (k6: https://k6.io). Run against a deployed environment,
 * never from the build (the build's own concurrency check is LoadSmokeIntegrationTest):
 *
 *   load/run.sh smoke  https://uat-procurement.example.com/api
 *   PROFILE=stress BASE_URL=https://uat-procurement.example.com/api K6_USERNAME=... K6_PASSWORD=... k6 run load/k6.js
 *
 * Profiles: smoke (1 user, 30 s) | load (40 users, ~8 min) | stress (up to 200 users) | spike (5 -> 200 users in
 * 10 s) | soak (20 users, 1 h). Each fails (exit code 99) when its thresholds are broken.
 * K6_USERNAME / K6_PASSWORD: an admin account for the test (required; use a dedicated read-only designation).
 * A production URL is refused unless ALLOW_PRODUCTION=yes.
 *
 * While it runs, watch the API's "API budget" warnings, its periodic "API calls since the last summary" log lines and
 * /actuator/metrics/api.db.statements (SQL statements per request, by endpoint).
 */
import http from 'k6/http';
import { check, group, sleep } from 'k6';

const BASE_URL = (__ENV.BASE_URL || 'http://localhost:9090/api').replace(/\/$/, '');
const PROFILE = __ENV.PROFILE || 'smoke';

if (/prod|www\.|tinqa\.com/i.test(BASE_URL) && __ENV.ALLOW_PRODUCTION !== 'yes') {
  throw new Error(`Refusing to load-test ${BASE_URL}: it looks like production. Set ALLOW_PRODUCTION=yes if that is intended.`);
}
if (!__ENV.K6_USERNAME || !__ENV.K6_PASSWORD) throw new Error('Set K6_USERNAME and K6_PASSWORD (an admin account for the test)');

const PROFILES = {
  smoke: { vus: 1, duration: '30s' },
  load: { stages: [{ duration: '2m', target: 40 }, { duration: '5m', target: 40 }, { duration: '1m', target: 0 }] },
  stress: {
    stages: [
      { duration: '2m', target: 50 },
      { duration: '3m', target: 100 },
      { duration: '3m', target: 200 },
      { duration: '2m', target: 0 },
    ],
  },
  spike: {
    stages: [
      { duration: '30s', target: 5 },
      { duration: '10s', target: 200 },
      { duration: '1m', target: 200 },
      { duration: '10s', target: 5 },
      { duration: '1m', target: 5 },
    ],
  },
  soak: { vus: 20, duration: '1h' },
};

if (!PROFILES[PROFILE]) throw new Error(`Unknown PROFILE=${PROFILE} (${Object.keys(PROFILES).join(' | ')})`);
const overloaded = PROFILE === 'stress' || PROFILE === 'spike';

export const options = {
  ...PROFILES[PROFILE],
  thresholds: {
    // Under stress some slowness is expected, errors are not
    http_req_failed: ['rate<0.01'],
    http_req_duration: overloaded ? ['p(95)<1500', 'p(99)<3000'] : ['p(95)<500', 'p(99)<1000'],
    checks: ['rate>0.99'],
  },
  summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
};

/** Signs in once; every virtual user reuses the token (the sign-in rate limits stay untouched). */
export function setup() {
  const res = http.post(
    `${BASE_URL}/auth/admin/login`,
    JSON.stringify({ username: __ENV.K6_USERNAME, password: __ENV.K6_PASSWORD }),
    { headers: { 'Content-Type': 'application/json' } }
  );
  if (res.status !== 200) throw new Error(`Sign-in failed (${res.status}): check K6_USERNAME / K6_PASSWORD`);
  return { headers: { Authorization: `Bearer ${res.json('accessToken')}` } };
}

const ok = (name) => ({ [`${name} answers 200`]: (r) => r.status === 200 });

export default function (auth) {
  const get = (path, name) => http.get(`${BASE_URL}${path}`, { headers: auth.headers, tags: { name } });

  group('workspace', () => {
    check(get('/v1/access/me', 'GET /v1/access/me'), ok('access'));
    check(get('/v1/notifications/unread-count', 'GET /v1/notifications/unread-count'), ok('notifications'));
  });
  group('lists', () => {
    check(get('/v1/admin/items?page=0&size=20', 'GET /v1/admin/items'), ok('items'));
    check(get('/v1/admin/products?page=0&size=20', 'GET /v1/admin/products'), ok('products'));
    check(get('/v1/admin/dealers?page=0&size=20', 'GET /v1/admin/dealers'), ok('dealers'));
    check(get('/v1/orders', 'GET /v1/orders'), ok('orders'));
  });

  // A person reads the page before the next click
  sleep(1 + Math.random() * 2);
}
