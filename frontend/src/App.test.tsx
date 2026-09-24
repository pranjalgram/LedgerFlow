import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { App } from './App';

afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

function renderApp() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(<QueryClientProvider client={client}><MemoryRouter><App /></MemoryRouter></QueryClientProvider>);
}

describe('readiness', () => {
  it('shows ready only after the backend reports UP', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({ status: 'UP' }))));
    renderApp();
    expect((await screen.findByText('Ready')).getAttribute('role')).toBe('status');
  });

  it('shows an actionable error instead of fabricated dashboard data when unavailable', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Network error')));
    renderApp();
    expect((await screen.findByRole('alert')).textContent).toContain('Start PostgreSQL and the API');
    expect(screen.queryByText('Ready')).toBeNull();
  });
});
