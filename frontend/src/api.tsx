import { createContext, useContext, useEffect, useState, type ReactNode } from 'react';
import { useQueryClient } from '@tanstack/react-query';

export type Tokens = { accessToken: string; refreshToken: string };
export type Merchant = { id: string; displayName: string; role: string };
export class ApiError extends Error {
  constructor(message: string, public status: number, public requestId: string | null) { super(message); }
}
export async function request<T>(path: string, options: { method?: string; body?: unknown; token?: string; merchant?: string; key?: string; signal?: AbortSignal } = {}): Promise<T> {
  const headers: Record<string, string> = { Accept: 'application/json' };
  if (options.body !== undefined) headers['Content-Type'] = 'application/json';
  if (options.token) headers.Authorization = `Bearer ${options.token}`;
  if (options.merchant) headers['X-Merchant-Id'] = options.merchant;
  if (options.key) headers['Idempotency-Key'] = options.key;
  const response = await fetch(path, { method: options.method ?? 'GET', headers, body: options.body === undefined ? undefined : JSON.stringify(options.body), signal: options.signal ?? AbortSignal.timeout(30_000), credentials: 'omit' });
  if (!response.ok) {
    const problem: unknown = await response.json().catch(() => null);
    const detail = typeof problem === 'object' && problem !== null && 'detail' in problem && typeof problem.detail === 'string' ? problem.detail : `Request failed (${response.status}).`;
    throw new ApiError(detail, response.status, response.headers.get('X-Request-Id'));
  }
  return response.status === 204 || response.headers.get('content-length') === '0' ? undefined as T : await response.json() as T;
}
type Session = {
  tokens: Tokens | null; merchant: Merchant | null; merchants: Merchant[];
  login: (email: string, password: string, merchantName?: string) => Promise<void>;
  logout: () => Promise<void>; select: (id: string) => void;
  api: <T>(path: string, options?: Parameters<typeof request>[1]) => Promise<T>;
};
const Context = createContext<Session | null>(null);
export function SessionProvider({ children }: { children: ReactNode }) {
  const [tokens, setTokens] = useState<Tokens | null>(null);
  const [merchants, setMerchants] = useState<Merchant[]>([]);
  const [merchantId, setMerchantId] = useState<string>('');
  const cache = useQueryClient();
  useEffect(() => {
    if (!tokens) return;
    let active = true;
    const timer = setTimeout(() => {
      void request<Tokens>('/api/v1/auth/refresh', { method: 'POST', body: { refreshToken: tokens.refreshToken } })
        .then(next => { if (active) setTokens(next); })
        .catch(() => { if (active) { setTokens(null); setMerchants([]); cache.clear(); } });
    }, 8 * 60_000);
    return () => { active = false; clearTimeout(timer); };
  }, [tokens, cache]);
  async function login(email: string, password: string, merchantName?: string) {
    if (merchantName) await request('/api/v1/auth/register', { method: 'POST', body: { email, password, merchantName } });
    const next = await request<Tokens>('/api/v1/auth/login', { method: 'POST', body: { email, password } });
    const available = await request<Merchant[]>('/api/v1/merchants', { token: next.accessToken });
    const first = available[0];
    if (!first) throw new Error('This account has no active merchant memberships.');
    cache.clear(); setMerchants(available); setMerchantId(first.id); setTokens(next);
  }
  async function logout() {
    const refreshToken = tokens?.refreshToken;
    setTokens(null); setMerchants([]); setMerchantId(''); cache.clear();
    if (refreshToken) await request('/api/v1/auth/logout', { method: 'POST', body: { refreshToken } });
  }
  function select(id: string) { cache.clear(); setMerchantId(id); }
  const api: Session['api'] = (path, options = {}) => request(path, { ...options, token: tokens?.accessToken, merchant: merchantId });
  return <Context.Provider value={{ tokens, merchants, merchant: merchants.find(value => value.id === merchantId) ?? null, login, logout, select, api }}>{children}</Context.Provider>;
}
export function useSession() { const session = useContext(Context); if (!session) throw new Error('Session provider missing'); return session; }
