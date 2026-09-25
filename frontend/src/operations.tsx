import { Link, useParams } from 'react-router-dom';
import { Action, Badge, Detail, Facts, Heading, ResourceTable, date, text } from './components';

export function Operations() {
  return <><Heading title="Operations" description="Reconcile financial records and inspect asynchronous work."><Action label="Start reconciliation" path="/api/v1/reconciliation-runs" management /></Heading>
    <Detail path="/api/v1/operations/health">{row => <div className="metrics">{[{ key: 'outboxPending', label: 'Unpublished events' }, { key: 'outboxBlocked', label: 'Blocked events' }, { key: 'failedWebhooks', label: 'Failed deliveries' }, { key: 'deadLetters', label: 'Dead letters' }].map(value => <section className="metric" key={value.key}><p>{value.label}</p><strong>{text(row, value.key)}</strong></section>)}</div>}</Detail>
    <ResourceTable path="/api/v1/reconciliation-runs" title="Reconciliation runs" interval={5000} columns={[{ key: 'id', label: 'Run', render: row => <Link to={`/operations/reconciliation/${text(row, 'id')}`}>{text(row, 'id').slice(0, 8)}…</Link> }, { key: 'status', label: 'Result', render: row => <Badge value={text(row, 'status')} /> }, { key: 'recordsProcessed', label: 'Records checked' }, { key: 'discrepancies', label: 'Findings' }, { key: 'durationMs', label: 'Duration (ms)' }, { key: 'createdAt', label: 'Requested', render: row => date(row.createdAt) }]} />
    <ResourceTable path="/api/v1/operations/outbox" title="Pending outbox events" interval={10_000} columns={[{ key: 'id', label: 'Event UUID' }, { key: 'type', label: 'Type' }, { key: 'state', label: 'State' }, { key: 'attempts', label: 'Attempts' }, { key: 'error', label: 'Failure' }]} />
    <ResourceTable path="/api/v1/operations/dead-letters" title="Dead-letter records" columns={[{ key: 'eventId', label: 'Event UUID' }, { key: 'topic', label: 'Topic' }, { key: 'partition', label: 'Partition' }, { key: 'offset', label: 'Offset' }, { key: 'receivedAt', label: 'Received', render: row => date(row.receivedAt) }]} />
    <p className="footnote">Dead-letter recovery requires operator tooling. Unknown-tenant poison messages are visible only to platform operators.</p>
    <ResourceTable path="/api/v1/audit-events" title="Audit history" columns={[{ key: 'action', label: 'Operation' }, { key: 'actorId', label: 'Actor' }, { key: 'resourceId', label: 'Resource' }, { key: 'correlationId', label: 'Request ID' }, { key: 'occurredAt', label: 'Time', render: row => date(row.occurredAt) }]} />
  </>;
}
export function ReconciliationDetail() {
  const { id } = useParams();
  return <><Heading title="Reconciliation report" description={id ?? ''} /><Detail path={`/api/v1/reconciliation-runs/${id}`} interval={5000}>{row => <section className="panel"><Facts row={row} fields={[{ key: 'status', label: 'Result', render: value => <Badge value={text(value, 'status')} /> }, { key: 'recordsProcessed', label: 'Records checked' }, { key: 'discrepancies', label: 'Findings' }, { key: 'snapshotAt', label: 'Snapshot', render: value => date(value.snapshotAt) }, { key: 'durationMs', label: 'Scan duration (ms)' }, { key: 'error', label: 'Failure' }]} /></section>}</Detail>
    <ResourceTable path={`/api/v1/reconciliation-runs/${id}/discrepancies`} title="Discrepancies" interval={5000} columns={[{ key: 'code', label: 'Invariant' }, { key: 'resourceId', label: 'Resource' }, { key: 'expected', label: 'Expected' }, { key: 'actual', label: 'Observed' }]} />
    <p className="footnote">Reports never repair financial data. Investigate discrepancies and use reviewed compensating transactions.</p>
  </>;
}
