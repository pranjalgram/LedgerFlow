import { useQuery } from '@tanstack/react-query';
import { Link, Route, Routes } from 'react-router-dom';

async function readiness(): Promise<string> {
  const response = await fetch('/actuator/health/readiness', {
    signal: AbortSignal.timeout(5_000),
    headers: { Accept: 'application/json' },
  });
  if (!response.ok) throw new Error('The service is not ready to accept requests.');
  const body: unknown = await response.json();
  if (typeof body !== 'object' || body === null || !('status' in body) || body.status !== 'UP') {
    throw new Error('The service is not ready to accept requests.');
  }
  return body.status;
}

function Overview() {
  const health = useQuery({ queryKey: ['readiness'], queryFn: readiness, refetchInterval: 30_000 });
  return (
    <>
      <div className="page-heading"><div><p className="eyebrow">Merchant operations</p><h1>A clear view of every movement.</h1></div><span className="environment">Simulation environment</span></div>
      <section className="hero">
        <div><p className="eyebrow">LedgerFlow / Foundation</p><h2>Built around the ledger.</h2><p>Wallets, payments, and transfers will share one balanced financial record. This workspace currently exposes service readiness while the financial modules are being implemented.</p></div>
        <div className="ledger-mark" aria-hidden="true"><span>DR</span><i>=</i><span>CR</span></div>
      </section>
      <section className="status-panel" aria-labelledby="service-heading">
        <div><p className="eyebrow">Service status</p><h2 id="service-heading">API & database readiness</h2><p>Live check from the backend. Refreshed every 30 seconds.</p></div>
        <div className="status-action"><span role="status" className={`status ${health.isSuccess ? 'up' : ''}`}>{health.isPending ? 'Checking connection…' : health.isError ? 'Unavailable' : 'Ready'}</span><button onClick={() => { void health.refetch(); }} disabled={health.isFetching}>Refresh status</button></div>
        {health.isError && <p className="error" role="alert">Cannot reach a ready backend. Start PostgreSQL and the API, then refresh.</p>}
      </section>
      <p className="footnote">Simulated funds only. No real payments are processed.</p>
    </>
  );
}

export function App() {
  return (
    <div className="app-shell">
      <aside><Link className="brand" to="/"><span className="brand-icon">L</span>LedgerFlow</Link><p className="nav-label">Workspace</p><nav aria-label="Main navigation"><Link className="nav-link" to="/">Overview <span>↗</span></Link></nav><div className="sidebar-note"><span className="dot" /> Development workspace<p>Financial modules are not available yet.</p></div></aside>
      <main><header><span>Workspace / Overview</span><span className="badge">TEST MODE</span></header><div className="content"><Routes><Route path="/" element={<Overview />} /><Route path="*" element={<section><h1>Page not found</h1><Link to="/">Return to overview</Link></section>} /></Routes></div></main>
    </div>
  );
}
