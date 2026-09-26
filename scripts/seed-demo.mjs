import { randomUUID, randomBytes } from 'node:crypto';
import { mkdir, writeFile } from 'node:fs/promises';

const base = process.env.BASE_URL ?? 'http://localhost:8088';
const run = randomUUID();
const directory = new URL('../.local-secrets/', import.meta.url);
const file = new URL(`demo-${run}.json`, directory);
const users = ['owner', 'developer', 'viewer'].map(role => ({ role, email: `${role}-${run}@example.test`, password: randomBytes(24).toString('base64url') }));
const state = { base, run, users, commands: [], resources: {} };
await mkdir(directory, { recursive: true });
await writeFile(file, JSON.stringify(state, null, 2), { flag: 'wx', mode: 0o600 });
const save = () => writeFile(file, JSON.stringify(state, null, 2), { mode: 0o600 });
async function api(method, path, body, session, command = false) {
  const headers = { 'Content-Type': 'application/json' };
  if (session) { headers.Authorization = `Bearer ${session.token}`; headers['X-Merchant-Id'] = session.merchant; }
  if (command) {
    headers['Idempotency-Key'] = randomUUID();
    state.commands.push({ method, path, body, key: headers['Idempotency-Key'] }); await save();
  }
  const response = await fetch(base + '/api/v1' + path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(30_000) });
  if (!response.ok) throw new Error(`${method} ${path} returned ${response.status}; inspect the dashboard. Financial retry keys are retained in the ignored seed file.`);
  return response.status === 204 ? undefined : response.json();
}
for (const user of users) {
  const result = await api('POST', '/auth/register', { email: user.email, password: user.password, merchantName: user.role === 'owner' ? 'Acme Commerce' : `${user.role} personal sandbox` });
  user.userId = result.userId; user.merchantId = result.merchantId; await save();
}
const owner = users[0];
const login = await api('POST', '/auth/login', { email: owner.email, password: owner.password });
const session = { token: login.accessToken, merchant: owner.merchantId };
for (const user of users.slice(1)) await api('POST', '/merchant/members', { email: user.email, role: user.role.toUpperCase() }, session);
const customer = await api('POST', '/wallets', { label: 'Priya customer wallet', kind: 'CUSTOMER', currency: 'INR', customerId: `customer-${run}` }, session);
const settlement = await api('POST', '/wallets', { label: 'Acme settlement', kind: 'SETTLEMENT', currency: 'INR' }, session);
state.resources.customer = customer.id; state.resources.settlement = settlement.id; await save();
await api('POST', `/wallets/${customer.id}/funding`, { amount: 500000, currency: 'INR' }, session, true);
state.resources.transfer = await api('POST', '/transfers', { sourceWalletId: customer.id, destinationWalletId: settlement.id, amount: 10000, currency: 'INR' }, session, true);
state.resources.apiKey = await api('POST', '/api-keys', { name: 'Demo integration', canWrite: true }, session);
if (process.env.DEMO_WEBHOOK_URL) state.resources.webhook = await api('POST', '/webhooks', { url: process.env.DEMO_WEBHOOK_URL, subscriptions: ['payment.succeeded', 'refund.succeeded'] }, session);
await save();
const integration = { token: state.resources.apiKey.secret, merchant: owner.merchantId };
async function payment(reference, amount) {
  const created = await api('POST', '/payments', { amount, currency: 'INR', customerWalletId: customer.id, reference, metadata: { demoRun: run } }, integration, true);
  const completed = await api('POST', `/payments/${created.id}/confirm`, {}, integration, true);
  state.resources[reference] = completed; await save(); return completed;
}
await payment('ORDER-SUCCEEDED', 49900);
const partial = await payment('ORDER-PARTIAL', 29900);
await api('POST', `/payments/${partial.id}/refunds`, { amount: 9900, currency: 'INR' }, session, true);
const full = await payment('ORDER-REFUNDED', 19900);
await api('POST', `/payments/${full.id}/refunds`, { currency: 'INR' }, session, true);
await payment('ORDER-FAILED', 900000);
for (const [reference, status] of Object.entries({ 'ORDER-SUCCEEDED': 'SUCCEEDED', 'ORDER-PARTIAL': 'PARTIALLY_REFUNDED', 'ORDER-REFUNDED': 'REFUNDED', 'ORDER-FAILED': 'FAILED' })) {
  state.resources[reference] = await api('GET', `/payments/${state.resources[reference].id}`, undefined, session);
  if (state.resources[reference].status !== status) throw new Error(`Unexpected demo payment state: ${reference}`);
}
state.resources.reconciliation = await api('POST', '/reconciliation-runs', {}, session);
for (let attempt = 0; attempt < 30; attempt++) {
  state.resources.reconciliation = await api('GET', `/reconciliation-runs/${state.resources.reconciliation.id}`, undefined, session);
  if (state.resources.reconciliation.status === 'PASSED') break;
  if (['FAILED', 'DISCREPANCIES'].includes(state.resources.reconciliation.status)) throw new Error('Demo reconciliation did not pass; investigate the stored report');
  await new Promise(resolve => setTimeout(resolve, 1000));
}
if (state.resources.reconciliation.status !== 'PASSED') throw new Error('Reconciliation still pending; inspect the dashboard');
await save();
console.log(`Created Acme Commerce with balanced demo transactions. Credentials and one-time secrets are in ${file.pathname}; no secrets printed.`);
console.log('Use the owner/developer/viewer credentials from that local file. Select Acme Commerce for shared access.');
