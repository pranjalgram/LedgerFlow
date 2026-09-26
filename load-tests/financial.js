import http from 'k6/http';
import { check, sleep } from 'k6';
import { randomBytes } from 'k6/crypto';

const base = __ENV.BASE_URL || 'http://host.docker.internal:8088';
const key = () => Array.from(new Uint8Array(randomBytes(16)), b => b.toString(16).padStart(2, '0')).join('');
export const options = {
  vus: Number(__ENV.VUS || 1), duration: __ENV.DURATION || '30s',
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(50)', 'p(95)', 'p(99)'],
  thresholds: { http_req_failed: ['rate<0.01'], checks: ['rate>0.99'] },
};
function send(method, path, data, session, name) {
  const headers = { 'Content-Type': 'application/json' };
  if (session) { headers.Authorization = `Bearer ${session.token}`; headers['X-Merchant-Id'] = session.merchant; }
  if (method === 'POST') headers['Idempotency-Key'] = key();
  return http.request(method, base + path, data === null ? null : JSON.stringify(data), { headers, timeout: '10s', tags: { name } });
}
function requireStatus(response, status) {
  if (response.status !== status) throw new Error(`Setup failed with HTTP ${response.status}; verify demo profile and service availability`);
  return response.json();
}
export function setup() {
  const email = `load-${key()}@example.test`, password = `Load-${key()}`;
  const registration = requireStatus(send('POST', '/api/v1/auth/register', { email, password, merchantName: 'Load test merchant' }, null, 'register'), 201);
  const login = requireStatus(send('POST', '/api/v1/auth/login', { email, password }, null, 'login'), 200);
  const session = { token: login.accessToken, merchant: registration.merchantId };
  const source = requireStatus(send('POST', '/api/v1/wallets', { label: 'Load customer', kind: 'CUSTOMER', currency: 'INR' }, session, 'create-wallet'), 201).id;
  const destination = requireStatus(send('POST', '/api/v1/wallets', { label: 'Load settlement', kind: 'SETTLEMENT', currency: 'INR' }, session, 'create-wallet'), 201).id;
  requireStatus(send('POST', `/api/v1/wallets/${source}/funding`, { amount: 100000000, currency: 'INR' }, session, 'fund'), 201);
  return { ...session, source, destination };
}
export default function (session) {
  const workflow = __ITER % 4;
  if (workflow === 0) {
    const response = send('GET', `/api/v1/wallets/${session.source}/balance`, null, session, 'balance');
    check(response, { 'balance 200': r => r.status === 200 });
  } else if (workflow === 1) {
    const response = send('GET', `/api/v1/wallets/${session.source}/transactions`, null, session, 'history');
    check(response, { 'history 200': r => r.status === 200 });
  } else if (workflow === 2) {
    const response = send('POST', '/api/v1/transfers', { sourceWalletId: session.source, destinationWalletId: session.destination, amount: 1, currency: 'INR' }, session, 'transfer');
    check(response, { 'transfer 201': r => r.status === 201 });
  } else {
    const response = send('POST', '/api/v1/payments', { amount: 1, currency: 'INR', customerWalletId: session.source, reference: `LOAD-${key()}`, metadata: {} }, session, 'payment-create');
    if (check(response, { 'payment created': r => r.status === 201 })) {
      const confirmed = send('POST', `/api/v1/payments/${response.json().id}/confirm`, {}, session, 'payment-confirm');
      check(confirmed, { 'payment succeeded': r => r.status === 200 && r.json().status === 'SUCCEEDED' });
    }
  }
  sleep(Number(__ENV.PAUSE_SECONDS || 1));
}
