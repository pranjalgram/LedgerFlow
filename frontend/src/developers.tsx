import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { useSession } from './api';
import { Action, Badge, CommandForm, ErrorMessage, Heading, ResourceTable, date, text, type Row } from './components';

function Secret({ value, dismiss }: { value: string; dismiss: () => void }) {
  const [error, setError] = useState<unknown>(null); const [copied, setCopied] = useState(false);
  return <section className="secret panel"><h2>Save your secret now</h2><p>It will not be shown again. Store it in your secret manager.</p><input aria-label="New secret" readOnly value={value} /><div className="row-actions"><button onClick={() => { void navigator.clipboard.writeText(value).then(() => setCopied(true)).catch(setError); }}>{copied ? 'Copied' : 'Copy secret'}</button><button className="quiet" onClick={dismiss}>I saved it — dismiss</button></div><ErrorMessage error={error} /></section>;
}
export function ApiKeys() {
  const { api } = useSession(); const [secret, setSecret] = useState('');
  return <><Heading title="API keys" description="Issue scoped integration credentials. Secrets are displayed only at creation." />{secret && <Secret value={secret} dismiss={() => setSecret('')} />}
    <ResourceTable path="/api/v1/api-keys" title="Credentials" columns={[{ key: 'name', label: 'Name' }, { key: 'prefix', label: 'Prefix' }, { key: 'canWrite', label: 'Scope', render: row => row.canWrite ? 'Read & write' : 'Read only' }, { key: 'createdAt', label: 'Created', render: row => date(row.createdAt) }, { key: 'lastUsedAt', label: 'Last used', render: row => date(row.lastUsedAt) }, { key: 'revokedAt', label: 'Status', render: row => <Badge value={row.revokedAt ? 'REVOKED' : 'ACTIVE'} /> }]} actions={row => <Action label="Revoke" path={`/api/v1/api-keys/${text(row, 'id')}`} method="DELETE" management disabled={!!row.revokedAt} confirm />} />
    <CommandForm title="Create API key" label="Create API key" management fields={[{ name: 'name', label: 'Key name' }, { name: 'scope', label: 'Scope', kind: 'select', options: ['Read only', 'Read & write'] }]} submit={values => api('/api/v1/api-keys', { method: 'POST', body: { name: values.name, canWrite: values.scope === 'Read & write' } })} onSuccess={result => setSecret(text(result as Row, 'secret'))} />
  </>;
}
export function Webhooks() {
  const { api } = useSession(); const [secret, setSecret] = useState('');
  return <><Heading title="Webhooks" description="Asynchronous signed events with durable retries and inspectable delivery attempts." />{secret && <Secret value={secret} dismiss={() => setSecret('')} />}
    <ResourceTable path="/api/v1/webhooks" title="Endpoints" columns={[{ key: 'url', label: 'Destination' }, { key: 'subscriptions', label: 'Subscriptions', render: row => (row.subscriptions as string[]).join(', ') }, { key: 'enabled', label: 'Status', render: row => <Badge value={row.enabled ? 'ACTIVE' : 'DISABLED'} /> }]} actions={row => <Action label="Disable" method="DELETE" path={`/api/v1/webhooks/${text(row, 'id')}`} management disabled={!row.enabled} confirm />} />
    <CommandForm title="Register endpoint" label="Register endpoint" management fields={[{ name: 'url', label: 'Endpoint URL', hint: 'Its exact origin and port must be approved by your operator.' }, { name: 'subscriptions', label: 'Event subscriptions', initial: 'payment.succeeded,refund.succeeded', hint: 'Comma-separated event types.' }]} submit={values => api('/api/v1/webhooks', { method: 'POST', body: { url: values.url, subscriptions: values.subscriptions?.split(',').map(value => value.trim()).filter(Boolean) } })} onSuccess={result => setSecret(text(result as Row, 'secret'))} />
    <ResourceTable path="/api/v1/webhook-deliveries" title="Delivery history" interval={10_000} columns={[{ key: 'id', label: 'Delivery', render: row => <Link to={`/webhooks/deliveries/${text(row, 'id')}`}>{text(row, 'id').slice(0, 8)}…</Link> }, { key: 'state', label: 'State', render: row => <Badge value={text(row, 'state')} /> }, { key: 'attempts', label: 'Attempts' }, { key: 'httpStatus', label: 'HTTP status' }, { key: 'error', label: 'Failure' }, { key: 'nextAttemptAt', label: 'Next eligible', render: row => ['PENDING', 'IN_FLIGHT'].includes(text(row, 'state')) ? date(row.nextAttemptAt) : '—' }]} />
  </>;
}
export function DeliveryDetail() {
  const { id } = useParams(); const [before, setBefore] = useState('2147483647');
  return <><Heading title="Webhook attempts" description={id ?? ''}><Action label="Replay delivery" path={`/api/v1/webhook-deliveries/${id}/replay`} management confirm /></Heading><p>Replay is available for completed deliveries to active endpoints. The event ID remains unchanged.</p>
    <ResourceTable key={before} path={`/api/v1/webhook-deliveries/${id}/attempts?before=${before}`} title="Completed attempts" columns={[{ key: 'attempt', label: 'Attempt' }, { key: 'httpStatus', label: 'HTTP status' }, { key: 'error', label: 'Failure' }, { key: 'durationMs', label: 'Duration (ms)' }, { key: 'completedAt', label: 'Completed', render: row => date(row.completedAt) }]} actions={row => <button className="quiet" onClick={() => setBefore(text(row, 'attempt'))}>Earlier attempts</button>} />
  </>;
}
export function Settings() {
  const { api, merchant } = useSession();
  return <><Heading title="Merchant settings" description={`${merchant?.displayName ?? ''} · ${merchant?.id ?? ''}`} />
    <ResourceTable path="/api/v1/merchant/members" title="Members" columns={[{ key: 'userId', label: 'User UUID' }, { key: 'role', label: 'Role' }]} actions={row => <Action label="Remove" path={`/api/v1/merchant/members/${text(row, 'userId')}`} method="DELETE" management confirm />} />
    <CommandForm title="Add existing user" label="Add member" management fields={[{ name: 'email', label: 'User email', kind: 'email' }, { name: 'role', label: 'Role', kind: 'select', options: ['VIEWER', 'DEVELOPER', 'ADMIN', 'OWNER'] }]} submit={values => api('/api/v1/merchant/members', { method: 'POST', body: values })} />
    <CommandForm title="Change member role" label="Update role" management fields={[{ name: 'userId', label: 'Member UUID', kind: 'uuid' }, { name: 'role', label: 'Role', kind: 'select', options: ['VIEWER', 'DEVELOPER', 'ADMIN', 'OWNER'] }]} submit={values => api(`/api/v1/merchant/members/${values.userId}`, { method: 'PATCH', body: { role: values.role } })} />
    <CommandForm title="Rename merchant" management fields={[{ name: 'displayName', label: 'Merchant name', initial: merchant?.displayName }]} submit={values => api('/api/v1/merchant', { method: 'PATCH', body: values })} />
  </>;
}
