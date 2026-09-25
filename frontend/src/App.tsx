import { lazy, Suspense, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Link, NavLink, Route, Routes } from 'react-router-dom';
const PaymentChart = lazy(() => import('./PaymentChart'));
import { SessionProvider, useSession } from './api';
import { CommandForm, Detail, ErrorMessage, Heading, ResourceTable, date, money, text, type Row } from './components';
import { Ledger, LedgerDetail, PaymentDetail, Payments, RefundDetail, Refunds, TransferDetail, Transfers, WalletDetail, Wallets } from './financial';
import { ApiKeys, DeliveryDetail, Settings, Webhooks } from './developers';
import { Operations, ReconciliationDetail } from './operations';

function Readiness() {
  const health = useQuery({ queryKey: ['readiness'], retry: false, queryFn: async () => {
    const response = await fetch('/actuator/health/readiness', { signal: AbortSignal.timeout(5000) });
    const value: unknown = await response.json();
    if (!response.ok || typeof value !== 'object' || value === null || !('status' in value) || value.status !== 'UP') throw new Error('Not ready');
    return 'Ready';
  }, refetchInterval: 30_000 });
  return <section className="status-panel"><div><p className="eyebrow">API & database readiness</p><span role="status" className={`status ${health.isSuccess ? 'up' : ''}`}>{health.isPending ? 'Checking connection…' : health.isError ? 'Unavailable' : 'Ready'}</span></div><button className="quiet" onClick={() => { void health.refetch(); }} disabled={health.isFetching}>Refresh status</button>{health.isError && <p role="alert" className="error">Cannot reach a ready backend. Start PostgreSQL and the API, then refresh.</p>}</section>;
}
function Login() {
  const [register, setRegister] = useState(false); const { login } = useSession();
  return <div className="auth-shell"><section className="auth-story"><Link className="brand" to="/"><span className="brand-icon">L</span>LedgerFlow</Link><p className="eyebrow">Every movement accounted for</p><h1>Your financial operations,<br />in one clear view.</h1><p>Payments, wallets, and an immutable double-entry ledger. A simulated environment for dependable financial workflows.</p><div className="ledger-mark" aria-hidden="true"><span>DR</span><i>=</i><span>CR</span></div><span className="badge">SIMULATED FUNDS ONLY</span></section><section className="auth-content">
    <CommandForm key={String(register)} publicForm title={register ? 'Create your workspace' : 'Welcome back'} label={register ? 'Create account' : 'Sign in'} fields={[{ name: 'email', label: 'Email', kind: 'email' }, { name: 'password', label: 'Password', kind: 'password' }, ...(register ? [{ name: 'merchantName', label: 'Merchant name' }] : [])]} submit={values => login(values.email ?? '', values.password ?? '', register ? values.merchantName : undefined)} />
    <button className="text-button" onClick={() => setRegister(!register)}>{register ? 'Already have an account? Sign in' : 'New to LedgerFlow? Create an account'}</button><Readiness /><p className="footnote">Tokens stay in memory. Reloading the page requires signing in again.</p>
  </section></div>;
}
function Overview() {
  return <><Heading title="Your money movements, at a glance." description="Live merchant totals · INR · simulated funds" />
    <Detail path="/api/v1/overview">{row => <><div className="metrics"><section className="metric featured"><p>Captured payment volume</p><strong>{money(row.capturedMinor)}</strong><small>{text(row, 'succeededCount')} successful payments</small></section><section className="metric"><p>Wallet balances</p><strong>{money(row.walletBalanceMinor)}</strong><small>Customer + settlement liabilities</small></section><section className="metric"><p>Refunded</p><strong>{money(row.refundedMinor)}</strong><small>Compensating ledger movements</small></section><section className="metric"><p>Payments</p><strong>{text(row, 'paymentCount')}</strong><small>{text(row, 'failedCount')} failed</small></section></div>
      <section className="panel"><h2>Payment activity</h2><p className="muted">Created payments per day · last 14 UTC days</p><Suspense fallback={<p>Loading chart…</p>}><PaymentChart data={row.daily as Row[]} /></Suspense></section></>}</Detail>
    <ResourceTable path="/api/v1/payments?limit=5" title="Recent payments" columns={[{ key: 'reference', label: 'Reference', render: row => <Link to={`/payments/${text(row, 'id')}`}>{text(row, 'reference')}</Link> }, { key: 'amount', label: 'Amount', render: row => money(row.amount) }, { key: 'status', label: 'Status' }, { key: 'createdAt', label: 'Created', render: row => date(row.createdAt) }]} /><Readiness />
  </>;
}
const navigation = [['/', 'Overview'], ['/wallets', 'Wallets'], ['/payments', 'Payments'], ['/transfers', 'Transfers'], ['/refunds', 'Refunds'], ['/ledger', 'Ledger'], ['/api-keys', 'API keys'], ['/webhooks', 'Webhooks'], ['/operations', 'Operations'], ['/settings', 'Settings']];
function Workspace() {
  const { tokens, merchants, merchant, select, logout } = useSession(); const [logoutError, setLogoutError] = useState<unknown>(null);
  if (!tokens) return <><Login /><ErrorMessage error={logoutError} /></>;
  return <div className="app-shell"><aside><Link className="brand" to="/"><span className="brand-icon">L</span>LedgerFlow</Link><p className="nav-label">Workspace</p><nav aria-label="Main navigation">{navigation.map(([path, label]) => <NavLink key={path} className="nav-link" end={path === '/'} to={path!}>{label}</NavLink>)}</nav><div className="sidebar-note"><span className="dot" /> Simulated funds<p>Every movement has a ledger record.</p></div></aside><main><header><label className="merchant-switch">Merchant<select aria-label="Select merchant" value={merchant?.id ?? ''} onChange={event => select(event.target.value)}>{merchants.map(value => <option key={value.id} value={value.id}>{value.displayName}</option>)}</select></label><div className="row-actions"><span className="badge">{merchant?.role} · TEST MODE</span><button className="quiet" onClick={() => { void logout().catch(() => setLogoutError(new Error('Signed out locally. Session revocation could not be confirmed because the server was unreachable.'))); }}>Sign out</button></div></header><div className="content" key={merchant?.id}><Routes>
    <Route path="/" element={<Overview />} /><Route path="/wallets" element={<Wallets />} /><Route path="/wallets/:id" element={<WalletDetail />} /><Route path="/payments" element={<Payments />} /><Route path="/payments/:id" element={<PaymentDetail />} /><Route path="/transfers" element={<Transfers />} /><Route path="/transfers/:id" element={<TransferDetail />} /><Route path="/refunds" element={<Refunds />} /><Route path="/refunds/:id" element={<RefundDetail />} /><Route path="/ledger" element={<Ledger />} /><Route path="/ledger/:id" element={<LedgerDetail />} /><Route path="/api-keys" element={<ApiKeys />} /><Route path="/webhooks" element={<Webhooks />} /><Route path="/webhooks/deliveries/:id" element={<DeliveryDetail />} /><Route path="/operations" element={<Operations />} /><Route path="/operations/reconciliation/:id" element={<ReconciliationDetail />} /><Route path="/settings" element={<Settings />} /><Route path="*" element={<section><h1>Page not found</h1><Link to="/">Return to overview</Link></section>} />
  </Routes></div></main></div>;
}
export function App() { return <SessionProvider><Workspace /></SessionProvider>; }
