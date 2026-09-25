import { useRef, useState, type ReactNode } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { Link } from 'react-router-dom';
import { ApiError, useSession } from './api';

export type Row = Record<string, unknown>;
export const text = (row: Row, key: string) => row[key] === null || row[key] === undefined ? '' : String(row[key]);
export function money(value: unknown): string {
  if (value === null || value === undefined || value === '') return '—';
  const amount = BigInt(String(value)); const absolute = amount < 0n ? -amount : amount;
  return `${amount < 0n ? '−' : ''}₹${(absolute / 100n).toLocaleString('en-IN')}.${(absolute % 100n).toString().padStart(2, '0')}`;
}
export const date = (value: unknown) => value ? new Date(String(value)).toLocaleString() : '—';
export function ErrorMessage({ error }: { error: unknown }) {
  return error ? <p role="alert" className="error">{error instanceof Error ? error.message : 'The request could not be completed.'}{error instanceof ApiError && error.requestId && <small>Request {error.requestId}</small>}</p> : null;
}
export function Heading({ title, description, children }: { title: string; description: string; children?: ReactNode }) {
  return <div className="page-heading"><div><p className="eyebrow">Merchant workspace</p><h1>{title}</h1><p>{description}</p></div>{children}</div>;
}
export function Badge({ value }: { value: string }) { return <span className={`status status-${value.toLowerCase()}`}>{value.replaceAll('_', ' ')}</span>; }
export function IdLink({ id, to }: { id: string; to: string }) { return <Link className="id-link" title={id} to={to}>{id.slice(0, 8)}…</Link>; }
export type Column = { key: string; label: string; render?: (row: Row) => ReactNode };
const rowSchema = z.record(z.string(), z.unknown());
function parseRows(data: unknown): { items: Row[]; nextCursor: string | null } {
  if (Array.isArray(data)) return { items: z.array(rowSchema).parse(data), nextCursor: null };
  return z.object({ items: z.array(rowSchema), nextCursor: z.string().nullable() }).parse(data);
}
export function ResourceTable({ path, columns, title, actions, interval = false }: { path: string; columns: Column[]; title?: string; actions?: (row: Row) => ReactNode; interval?: number | false }) {
  const { api, merchant } = useSession(); const [cursor, setCursor] = useState<string | null>(null);
  const url = path + (path.includes('?') ? '&' : '?') + (cursor ? `cursor=${encodeURIComponent(cursor)}` : '');
  const query = useQuery({ queryKey: [merchant?.id, path, cursor], queryFn: async ({ signal }) => parseRows(await api(url, { signal })), refetchInterval: interval });
  return <section className="panel table-panel">{title && <div className="section-heading"><h2>{title}</h2><button className="quiet" onClick={() => { void query.refetch(); }} disabled={query.isFetching}>Refresh</button></div>}
    {query.isPending && <p role="status" className="empty">Loading records…</p>}<ErrorMessage error={query.error} />
    {query.data && <><div className="table-scroll"><table><thead><tr>{columns.map(column => <th key={column.key}>{column.label}</th>)}{actions && <th>Actions</th>}</tr></thead><tbody>{query.data.items.map((row, index) => <tr key={text(row, 'id') || text(row, 'userId') || String(index)}>{columns.map(column => <td key={column.key}>{column.render ? column.render(row) : text(row, column.key) || '—'}</td>)}{actions && <td><div className="row-actions">{actions(row)}</div></td>}</tr>)}</tbody></table></div>
      {!query.data.items.length && <p className="empty">No records yet.</p>}<div className="pagination"><span>{query.data.items.length} records on this page</span><button className="quiet" disabled={!cursor} onClick={() => setCursor(null)}>Newest</button><button className="quiet" disabled={!query.data.nextCursor} onClick={() => setCursor(query.data!.nextCursor)}>Older →</button></div></>}
  </section>;
}
export function Detail({ path, children, interval = false }: { path: string; children: (row: Row) => ReactNode; interval?: number | false }) {
  const { api, merchant } = useSession(); const query = useQuery({ queryKey: [merchant?.id, path], queryFn: ({ signal }) => api<Row>(path, { signal }), refetchInterval: interval });
  if (query.isPending) return <p role="status">Loading details…</p>;
  if (query.isError) return <ErrorMessage error={query.error} />;
  return children(query.data);
}
export function Facts({ row, fields }: { row: Row; fields: Column[] }) {
  return <dl className="facts">{fields.map(field => <div key={field.key}><dt>{field.label}</dt><dd>{field.render ? field.render(row) : text(row, field.key) || '—'}</dd></div>)}</dl>;
}
export type Field = { name: string; label: string; kind?: 'text' | 'password' | 'email' | 'amount' | 'uuid' | 'json' | 'select'; options?: string[]; optional?: boolean; initial?: string; hint?: string };
export function CommandForm({ title, fields, submit, onSuccess, label = 'Save', management = false, publicForm = false }: {
  title: string; fields: Field[]; submit: (values: Record<string, string>, key: string) => Promise<unknown>; onSuccess?: (result: unknown) => void; label?: string; management?: boolean; publicForm?: boolean;
}) {
  const { merchant } = useSession(); const cache = useQueryClient();
  const shape = Object.fromEntries(fields.map(field => [field.name, z.string().superRefine((value, ctx) => {
    if (field.optional && !value) return;
    let message = '';
    if (!value.trim()) message = 'This field is required.';
    else if (field.kind === 'amount' && (!/^\d+$/.test(value) || BigInt(value) < 1n || BigInt(value) > 9_000_000_000_000n)) message = 'Enter positive integer minor units, up to 9,000,000,000,000.';
    else if (field.kind === 'uuid' && !z.uuid().safeParse(value).success) message = 'Enter a valid resource UUID.';
    else if (field.kind === 'email' && !z.email().safeParse(value).success) message = 'Enter a valid email address.';
    else if (field.kind === 'password' && value.length < 12) message = 'Use at least 12 characters.';
    else if (field.kind === 'json') { try { if (!z.record(z.string(), z.string()).safeParse(JSON.parse(value)).success) message = 'Use a JSON object with string values.'; } catch { message = 'Enter valid JSON.'; } }
    if (message) ctx.addIssue({ code: 'custom', message });
  })]));
  const { register, handleSubmit, formState: { errors } } = useForm<Record<string, string>>({ resolver: zodResolver(z.object(shape)), defaultValues: Object.fromEntries(fields.map(field => [field.name, field.initial ?? field.options?.[0] ?? ''])) });
  const attempt = useRef<{ payload: string; key: string } | null>(null);
  const mutation = useMutation({ retry: false, mutationFn: async (values: Record<string, string>) => {
    const payload = JSON.stringify(values);
    if (attempt.current?.payload !== payload) attempt.current = { payload, key: crypto.randomUUID() };
    return submit(values, attempt.current.key);
  }, onSuccess: result => { attempt.current = null; void cache.invalidateQueries(); onSuccess?.(result); } });
  const allowed = publicForm || (management ? ['OWNER', 'ADMIN'].includes(merchant?.role ?? '') : merchant?.role !== 'VIEWER');
  return <section className="panel command-panel"><h2>{title}</h2><form onSubmit={event => { void handleSubmit(values => mutation.mutateAsync(values).catch(() => undefined))(event); }}>
    <div className="form-grid">{fields.map(field => <label key={field.name}>{field.label}{field.kind === 'select' ? <select aria-label={field.label} {...register(field.name)}>{field.options?.map(option => <option key={option}>{option}</option>)}</select> : field.kind === 'json' ? <textarea aria-label={field.label} {...register(field.name)} rows={3} /> : <input aria-label={field.label} {...register(field.name)} type={field.kind === 'password' || field.kind === 'email' ? field.kind : 'text'} inputMode={field.kind === 'amount' ? 'numeric' : undefined} autoComplete={field.kind === 'password' ? 'current-password' : field.kind === 'email' ? 'email' : 'off'} />}{field.hint && <small>{field.hint}</small>}{errors[field.name] && <small className="field-error">{errors[field.name]?.message}</small>}</label>)}</div>
    <ErrorMessage error={mutation.error} />{mutation.isSuccess && <p role="status" className="success">Completed successfully.</p>}
    <button disabled={mutation.isPending || !allowed}>{mutation.isPending ? 'Submitting…' : label}</button>{!allowed && <small>Your role cannot perform this action.</small>}
  </form></section>;
}
export function Action({ label, path, method = 'POST', body, disabled = false, management = false, confirm = false }: { label: string; path: string; method?: string; body?: unknown; disabled?: boolean; management?: boolean; confirm?: boolean }) {
  const { api, merchant } = useSession(); const cache = useQueryClient(); const key = useRef(crypto.randomUUID());
  const [confirming, setConfirming] = useState(false);
  const mutation = useMutation({ retry: false, mutationFn: () => api(path, { method, body, key: key.current }), onSuccess: () => { key.current = crypto.randomUUID(); setConfirming(false); void cache.invalidateQueries(); } });
  const allowed = management ? ['OWNER', 'ADMIN'].includes(merchant?.role ?? '') : merchant?.role !== 'VIEWER';
  return <span className="action"><button className="quiet" disabled={disabled || mutation.isPending || !allowed} onClick={() => { if (confirm && !confirming) setConfirming(true); else mutation.mutate(); }}>{mutation.isPending ? 'Working…' : confirming ? `Confirm ${label.toLowerCase()}` : label}</button>{confirming && <button className="quiet" onClick={() => setConfirming(false)}>Cancel</button>}<ErrorMessage error={mutation.error} /></span>;
}
