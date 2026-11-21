export class ApiError extends Error {
  constructor(message, status = 0) { super(message); this.status = status; }
}

export function createApi(getToken, onUnauthorized, observeDate = () => {}) {
  return async function request(path, { method = 'GET', body, key, authenticated = false } = {}) {
    const headers = { Accept: 'application/json' };
    if (body !== undefined) headers['Content-Type'] = 'application/json';
    if (key) headers['Idempotency-Key'] = key;
    if (authenticated) {
      const token = getToken();
      if (!token) throw new ApiError('Please sign in to continue.', 401);
      headers.Authorization = `Bearer ${token}`;
    }
    let response;
    try {
      response = await fetch(`/api/v1${path}`, { method, headers,
        body: body === undefined ? undefined : JSON.stringify(body),
        credentials: 'omit', cache: 'no-store', signal: AbortSignal.timeout(90000) });
    } catch {
      throw new ApiError('The server could not be reached. It may be waking up. Please try again.');
    }
    const date = Date.parse(response.headers.get('Date'));
    if (Number.isFinite(date)) observeDate(date);
    let data;
    try { data = await response.json(); } catch { data = {}; }
    if (!response.ok) {
      if (response.status === 401 && authenticated) onUnauthorized();
      const retry = response.headers.get('Retry-After');
      const message = response.status === 429
        ? `Too many attempts. ${/^\d+$/.test(retry || '') ? `Try again in ${retry} seconds.` : 'Please wait before retrying.'}`
        : response.status >= 500 ? 'The service is temporarily unavailable. Please try again shortly.'
        : data.detail || data.title || `Request failed (${response.status}).`;
      throw new ApiError(message, response.status);
    }
    return data;
  };
}

// One key per logical action; uncertain responses retain it for a safe explicit retry.
export function createOperations(api, newKey = () => crypto.randomUUID()) {
  let pending = null;
  let busy = false;
  return {
    get pending() { return pending; },
    clear() { pending = null; },
    async run(path, body) {
      if (busy) throw new ApiError('An action is already in progress.', 409);
      if (pending && (pending.path !== path || JSON.stringify(pending.body) !== JSON.stringify(body))) {
        throw new ApiError('Resolve the previous action with Retry before starting another reservation action.', 409);
      }
      pending ||= { path, body, key: newKey() };
      const operation = pending;
      busy = true;
      try {
        const result = await api(path, { method: 'POST', body, key: operation.key, authenticated: true });
        if (pending === operation) pending = null;
        return result;
      } catch (error) {
        if (error.status >= 400 && error.status < 500 && pending === operation) pending = null;
        throw error;
      } finally { busy = false; }
    }
  };
}

export function tokenClaims(token) {
  const part = token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/');
  return JSON.parse(atob(part.padEnd(Math.ceil(part.length / 4) * 4, '=')));
}
