import { afterEach, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { CommandForm, money } from './components';

vi.mock('./api', async importOriginal => ({ ...await importOriginal<typeof import('./api')>(), useSession: () => ({ merchant: { id: 'tenant', role: 'OWNER' } }) }));
afterEach(cleanup);
it('formats large balances without floating point rounding', () => {
  expect(money('900719925474099199')).toBe('₹9,00,71,99,25,47,40,991.99');
  expect(money('1')).toBe('₹0.01');
});
it('rejects fractional money and preserves idempotency on an ambiguous retry', async () => {
  const submit = vi.fn().mockRejectedValueOnce(new Error('Connection lost')).mockResolvedValueOnce({ id: 'created' });
  render(<QueryClientProvider client={new QueryClient()}><CommandForm title="Transfer" label="Send" fields={[{ name: 'amount', label: 'Amount', kind: 'amount' }]} submit={submit} /></QueryClientProvider>);
  fireEvent.change(screen.getByLabelText('Amount'), { target: { value: '1.5' } });
  fireEvent.click(screen.getByRole('button', { name: 'Send' }));
  expect(await screen.findByText(/Enter positive integer/)).toBeDefined();
  expect(submit).not.toHaveBeenCalled();
  fireEvent.change(screen.getByLabelText('Amount'), { target: { value: '100' } });
  fireEvent.click(screen.getByRole('button', { name: 'Send' }));
  expect(await screen.findByRole('alert')).toBeDefined();
  fireEvent.click(screen.getByRole('button', { name: 'Send' }));
  await waitFor(() => expect(submit).toHaveBeenCalledTimes(2));
  expect(submit.mock.calls[0]?.[1]).toBe(submit.mock.calls[1]?.[1]);
});
