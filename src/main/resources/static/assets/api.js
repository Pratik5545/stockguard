export class ApiError extends Error {
  constructor(message, status = 0) { super(message); this.status = status; }
}

export function createApi(getSession, onUnauthorized, observeDate = () => {}) {
  return async function request(path, { method = 'GET', body, key, authenticated = false, validate } = {}) {
    const session = getSession();
    const headers = { Accept: 'application/json' };
    if (body !== undefined) headers['Content-Type'] = 'application/json';
    if (key) headers['Idempotency-Key'] = key;
    if (authenticated) {
      if (!session) throw new ApiError('Please sign in to continue.', 401);
      headers.Authorization = `Bearer ${session.token}`;
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
    if (Number.isFinite(date) && getSession() === session) observeDate(date);
    const data = await response.json().catch(() => null);
    if (!response.ok) {
      if (response.status === 401 && authenticated && getSession() === session) onUnauthorized();
      const retry = response.headers.get('Retry-After');
      const message = response.status === 429
        ? `Too many attempts. ${/^\d+$/.test(retry || '') ? `Try again in ${retry} seconds.` : 'Please wait before retrying.'}`
        : response.status >= 500 ? 'The service is temporarily unavailable. Please try again shortly.'
        : data?.detail || data?.title || `Request failed (${response.status}).`;
      throw new ApiError(message, response.status);
    }
    if (!data || typeof data !== 'object' || Array.isArray(data) || !Object.keys(data).length || (validate && !validate(data))) {
      throw new ApiError('The server returned an incomplete or unexpected response.');
    }
    return data;
  };
}

const hasText = value => typeof value === 'string' && value.length > 0;
const timestamp = value => hasText(value) && Number.isFinite(Date.parse(value));
const nonnegativeInteger = value => Number.isSafeInteger(value) && value >= 0;
const amount = value => Number.isFinite(value) && value >= 0;

export function isProduct(data) {
  return data && hasText(data.id) && hasText(data.sku) && hasText(data.name) &&
    amount(data.price) && nonnegativeInteger(data.availableQuantity);
}

export function isInventory(data) {
  return hasText(data.productId) &&
    [data.onHandQuantity, data.reservedQuantity, data.availableQuantity].every(nonnegativeInteger) &&
    data.availableQuantity === data.onHandQuantity - data.reservedQuantity;
}

function isPurchaseLine(data) {
  return data && [data.id, data.userId, data.productId, data.sku, data.productName, data.status].every(hasText) &&
    Number.isSafeInteger(data.quantity) && data.quantity > 0 && amount(data.unitPrice) && timestamp(data.createdAt);
}

export function isReservation(data) {
  return isPurchaseLine(data) && timestamp(data.expiresAt) && timestamp(data.updatedAt);
}

export function isOrder(data) {
  return isPurchaseLine(data) && hasText(data.reservationId) && amount(data.totalAmount);
}

function confirmsAction(path, body, result) {
  if (path.endsWith('/confirm')) {
    return isOrder(result) && result.status === 'PLACED' && path === `/reservations/${result.reservationId}/confirm`;
  }
  if (!isReservation(result)) return false;
  if (path === '/reservations') {
    return result.status === 'PENDING' && result.productId === body.productId && result.quantity === body.quantity;
  }
  return result.status === 'CANCELLED' && path === `/reservations/${result.id}/cancel`;
}

// One key per logical action; uncertain responses retain it for a safe explicit retry.
export function createOperations(api, newKey = () => crypto.randomUUID()) {
  let pending = null;
  let active = null;
  return {
    get pending() { return pending; },
    clear() { pending = null; active = null; },
    async run(path, body) {
      if (active) throw new ApiError('An action is already in progress.', 409);
      if (pending && (pending.path !== path || JSON.stringify(pending.body) !== JSON.stringify(body))) {
        throw new ApiError('Resolve the previous action with Retry before starting another reservation action.', 409);
      }
      const replay = pending !== null;
      const operation = pending ||= { path, body: body && { ...body }, key: newKey() };
      active = operation;
      try {
        const result = await api(path, {
          method: 'POST', body: operation.body, key: operation.key, authenticated: true,
          validate: result => confirmsAction(path, operation.body, result)
        });
        if (pending === operation) pending = null;
        return result;
      } catch (error) {
        // A rate limit can reject a replay before the server looks up the first result.
        const definite = replay ? [400, 404, 409].includes(error.status)
          : error.status >= 400 && error.status < 500 && error.status !== 408;
        if (definite && pending === operation) pending = null;
        throw error;
      } finally { if (active === operation) active = null; }
    }
  };
}

export function tokenClaims(token) {
  const part = token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/');
  return JSON.parse(atob(part.padEnd(Math.ceil(part.length / 4) * 4, '=')));
}
