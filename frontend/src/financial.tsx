import { useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { useSession } from './api';
import { Action, Badge, CommandForm, Detail, Facts, Heading, IdLink, ResourceTable, date, money, text, type Column, type Row } from './components';

const amount: Column = { key: 'amount', label: 'Amount', render: row => money(row.amount) };
const created: Column = { key: 'createdAt', label: 'Created', render: row => date(row.createdAt) };
const state: Column = { key: 'status', label: 'Status', render: row => <Badge value={text(row, 'status')} /> };
const journal: Column = { key: 'ledgerTransactionId', label: 'Ledger', render: row => row.ledgerTransactionId ? <IdLink id={text(row, 'ledgerTransactionId')} to={`/ledger/${text(row, 'ledgerTransactionId')}`} /> : '—' };
const idColumn = (base: string): Column => ({ key: 'id', label: 'ID', render: row => <IdLink id={text(row, 'id')} to={`${base}/${text(row, 'id')}`} /> });
const amountField = { name: 'amount', label: 'Amount (paise)', kind: 'amount' as const, hint: 'Integer minor units: 100 paise = ₹1.00.' };
const resultId = (result: unknown) => text(result as Row, 'id');

export function Wallets() {
  const { api } = useSession(); const navigate = useNavigate();
  return <><Heading title="Wallets" description="Customer funds and merchant settlement, backed by immutable ledger entries." />
    <ResourceTable path="/api/v1/wallets" title="Wallet balances" columns={[{ key: 'label', label: 'Wallet', render: row => <Link to={`/wallets/${text(row, 'id')}`}>{text(row, 'label')}</Link> }, idColumn('/wallets'), { key: 'kind', label: 'Type' }, { key: 'balanceMinor', label: 'Balance', render: row => money(row.balanceMinor) }, created]} />
    <CommandForm title="Create wallet" label="Create wallet" fields={[{ name: 'label', label: 'Wallet name' }, { name: 'kind', label: 'Type', kind: 'select', options: ['CUSTOMER', 'SETTLEMENT'] }, { name: 'customerId', label: 'Customer reference', optional: true, hint: 'Optional external reference for customer wallets.' }]} submit={(values, key) => api('/api/v1/wallets', { method: 'POST', key, body: { ...values, customerId: values.customerId || undefined, currency: 'INR' } })} onSuccess={result => navigate(`/wallets/${resultId(result)}`)} />
  </>;
}
export function WalletDetail() {
  const { id } = useParams(); const { api } = useSession();
  return <><Heading title="Wallet detail" description={id ?? ''} /><Detail path={`/api/v1/wallets/${id}/balance`}>{row => <section className="hero"><div><p className="eyebrow">Available balance · INR</p><h2 className="big-money">{money(row.balanceMinor)}</h2><p>As of {date(row.asOf)}</p></div><Badge value="LEDGER_BACKED" /></section>}</Detail>
    <ResourceTable path={`/api/v1/wallets/${id}/transactions`} title="Account activity" columns={[{ key: 'businessType', label: 'Movement' }, { key: 'side', label: 'Side' }, amount, journal, { key: 'postedAt', label: 'Posted', render: row => date(row.postedAt) }]} />
    <CommandForm title="Add simulated funds" label="Add simulated funds" fields={[amountField]} submit={(values, key) => api(`/api/v1/wallets/${id}/funding`, { method: 'POST', key, body: { amount: Number(values.amount), currency: 'INR' } })} />
    <p className="footnote">Funding requires the backend demo profile. No real money is deposited.</p></>;
}
export function Transfers() {
  const { api } = useSession(); const navigate = useNavigate();
  return <><Heading title="Transfers" description="Move simulated funds atomically between wallets in this merchant." />
    <ResourceTable path="/api/v1/transfers" title="Completed transfers" columns={[idColumn('/transfers'), amount, journal, created]} />
    <CommandForm title="Transfer funds" label="Transfer funds" fields={[{ name: 'sourceWalletId', label: 'Source wallet UUID', kind: 'uuid' }, { name: 'destinationWalletId', label: 'Destination wallet UUID', kind: 'uuid' }, amountField]} submit={(values, key) => api('/api/v1/transfers', { method: 'POST', key, body: { ...values, amount: Number(values.amount), currency: 'INR' } })} onSuccess={result => navigate(`/transfers/${resultId(result)}`)} />
  </>;
}
export function TransferDetail() {
  const { id } = useParams();
  return <><Heading title="Transfer detail" description={id ?? ''} /><Detail path={`/api/v1/transfers/${id}`}>{row => <section className="panel"><Facts row={row} fields={[amount, { key: 'sourceWalletId', label: 'From', render: value => <Link to={`/wallets/${text(value, 'sourceWalletId')}`}>{text(value, 'sourceWalletId')}</Link> }, { key: 'destinationWalletId', label: 'To', render: value => <Link to={`/wallets/${text(value, 'destinationWalletId')}`}>{text(value, 'destinationWalletId')}</Link> }, journal, created]} /></section>}</Detail></>;
}
export function Payments() {
  const { api } = useSession(); const navigate = useNavigate(); const [reference, setReference] = useState(''); const [status, setStatus] = useState('');
  const filter = new URLSearchParams(); if (reference) filter.set('reference', reference); if (status) filter.set('status', status);
  return <><Heading title="Payments" description="Create, capture, and inspect merchant payments." />
    <div className="filters"><label>Order reference (exact)<input value={reference} onChange={event => setReference(event.target.value)} placeholder="ORDER-98372" /></label><label>Status<select value={status} onChange={event => setStatus(event.target.value)}><option value="">All statuses</option>{['CREATED', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'PARTIALLY_REFUNDED', 'REFUNDED'].map(value => <option key={value}>{value}</option>)}</select></label></div>
    <ResourceTable key={filter.toString()} path={`/api/v1/payments?${filter}`} title="Payment history" columns={[idColumn('/payments'), { key: 'reference', label: 'Reference' }, amount, state, created]} />
    <CommandForm title="Create payment" label="Create payment" fields={[{ name: 'customerWalletId', label: 'Customer wallet UUID', kind: 'uuid' }, amountField, { name: 'reference', label: 'Order reference' }, { name: 'metadata', label: 'Metadata', kind: 'json', initial: '{}', hint: 'JSON object containing string values.' }]} submit={(values, key) => api('/api/v1/payments', { method: 'POST', key, body: { ...values, amount: Number(values.amount), currency: 'INR', metadata: JSON.parse(values.metadata ?? '{}') as unknown } })} onSuccess={result => navigate(`/payments/${resultId(result)}`)} />
  </>;
}
export function PaymentDetail() {
  const { id } = useParams(); const { api } = useSession();
  return <><Heading title="Payment detail" description={id ?? ''} /><Detail path={`/api/v1/payments/${id}`}>{row => <>
    <section className="panel"><Facts row={row} fields={[{ key: 'reference', label: 'Order reference' }, amount, state, { key: 'refundedAmount', label: 'Refunded', render: value => money(value.refundedAmount) }, journal, { key: 'failureCode', label: 'Failure reason' }, created]} />
      {row.status === 'CREATED' && <div className="row-actions"><Action label="Confirm payment" path={`/api/v1/payments/${id}/confirm`} body={{}} confirm /><Action label="Cancel payment" path={`/api/v1/payments/${id}/cancel`} body={{}} confirm /></div>}
      <h3>Metadata</h3><pre>{JSON.stringify(row.metadata, null, 2)}</pre>
    </section>
    {['SUCCEEDED', 'PARTIALLY_REFUNDED'].includes(text(row, 'status')) && <CommandForm title="Issue refund" label="Issue refund" fields={[{ ...amountField, optional: true, hint: 'Leave blank to refund the remaining captured amount.' }]} submit={(values, key) => api(`/api/v1/payments/${id}/refunds`, { method: 'POST', key, body: { currency: 'INR', amount: values.amount ? Number(values.amount) : undefined } })} />}
  </>}</Detail>
    <ResourceTable path={`/api/v1/payments/${id}/timeline`} title="Lifecycle" columns={[{ key: 'version', label: 'Version' }, { key: 'fromState', label: 'From' }, { key: 'toState', label: 'To', render: row => <Badge value={text(row, 'toState')} /> }, { key: 'occurredAt', label: 'Time', render: row => date(row.occurredAt) }, { key: 'reasonCode', label: 'Reason' }]} />
    <ResourceTable path={`/api/v1/refunds?paymentId=${id}`} title="Refunds" columns={[idColumn('/refunds'), amount, state, journal, created]} />
  </>;
}
export function Refunds() { return <><Heading title="Refunds" description="Full and partial refunds create compensating ledger entries. Open a payment to issue a refund." /><ResourceTable path="/api/v1/refunds" title="Refund history" columns={[idColumn('/refunds'), { key: 'paymentId', label: 'Payment', render: row => <IdLink id={text(row, 'paymentId')} to={`/payments/${text(row, 'paymentId')}`} /> }, amount, state, journal, created]} /></>; }
export function RefundDetail() {
  const { id } = useParams();
  return <><Heading title="Refund detail" description={id ?? ''} /><Detail path={`/api/v1/refunds/${id}`}>{row => <section className="panel"><Facts row={row} fields={[amount, state, journal, { key: 'paymentId', label: 'Payment', render: value => <Link to={`/payments/${text(value, 'paymentId')}`}>{text(value, 'paymentId')}</Link> }, { key: 'failureCode', label: 'Failure reason' }, created]} /></section>}</Detail></>;
}
export function Ledger() {
  return <><Heading title="Ledger" description="Immutable double-entry journals. Wallet liabilities increase with credits; cash-control assets increase with debits." />
    <ResourceTable path="/api/v1/ledger/transactions" title="Journal transactions" columns={[idColumn('/ledger'), { key: 'businessType', label: 'Source' }, { key: 'businessId', label: 'Business reference' }, { key: 'postedAt', label: 'Posted', render: row => date(row.postedAt) }]} />
    <ResourceTable path="/api/v1/ledger/accounts" title="Accounts" columns={[{ key: 'id', label: 'Account UUID' }, { key: 'kind', label: 'Type' }, { key: 'purpose', label: 'Purpose' }, { key: 'balanceMinor', label: 'Normal balance', render: row => money(row.balanceMinor) }]} />
  </>;
}
export function LedgerDetail() {
  const { id } = useParams();
  return <><Heading title="Journal entry" description={id ?? ''} /><Detail path={`/api/v1/ledger/transactions/${id}`}>{row => {
    const entries = row.entries as Row[]; const transaction = row.transaction as Row;
    const sum = (side: string) => entries.filter(entry => entry.side === side).reduce((total, entry) => total + BigInt(String(entry.amountMinor)), 0n);
    return <section className="panel"><Facts row={transaction} fields={[{ key: 'businessType', label: 'Source' }, { key: 'businessId', label: 'Business reference' }, { key: 'postedAt', label: 'Posted', render: value => date(value.postedAt) }]} />
      <div className="table-scroll"><table><thead><tr><th>Account</th><th>Debit</th><th>Credit</th></tr></thead><tbody>{entries.map(entry => <tr key={text(entry, 'id')}><td className="mono">{text(entry, 'accountId')}</td><td>{entry.side === 'DEBIT' ? money(entry.amountMinor) : '—'}</td><td>{entry.side === 'CREDIT' ? money(entry.amountMinor) : '—'}</td></tr>)}</tbody><tfoot><tr><th>Total</th><th>{money(sum('DEBIT'))}</th><th>{money(sum('CREDIT'))}</th></tr></tfoot></table></div><Badge value={sum('DEBIT') === sum('CREDIT') ? 'BALANCED' : 'IMBALANCED'} /></section>;
  }}</Detail></>;
}
