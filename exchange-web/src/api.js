const API = '/api/v1';

export async function request(path, token, options = {}) {
  const headers = { ...(options.body ? { 'Content-Type': 'application/json' } : {}), ...options.headers };
  if (token) headers.Authorization = `Bearer ${token}`;
  const response = await fetch(`${API}${path}`, { ...options, headers, cache: 'no-store' });
  const raw = await response.text();
  let body = null;
  if (raw) {
    try { body = JSON.parse(raw); } catch { body = raw; }
  }
  if (!response.ok) {
    const details = typeof body === 'object' && body !== null
      ? body.status || body.detail || body.message || body.error
      : body;
    throw new Error(`${response.status} ${details || response.statusText}`);
  }
  return body;
}

export const exchangeApi = {
  instruments: () => request('/instruments'),
  snapshot: symbol => request(`/market/instruments/${encodeURIComponent(symbol)}/snapshot`),
  auth: (mode, email, password) => request(`/auth/${mode}`, null, {
    method: 'POST', body: JSON.stringify({ email, password })
  }),
  cash: token => request('/account', token),
  holdings: token => request('/account/holdings', token),
  orders: token => request('/account/orders', token),
  submit: (symbol, token, order) => request(`/instruments/${encodeURIComponent(symbol)}/orders`, token, {
    method: 'POST', body: JSON.stringify(order)
  }),
  cancel: (symbol, id, token) => request(`/instruments/${encodeURIComponent(symbol)}/orders/${id}`, token, {
    method: 'DELETE'
  })
};
