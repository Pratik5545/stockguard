import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
const source = await readFile(new URL('../src/main/resources/static/assets/api.js', import.meta.url), 'utf8');
const { createApi, createOperations, ApiError } = await import(`data:text/javascript;base64,${Buffer.from(source).toString('base64')}`);
const session = { token: 'test-token' };
const createdAt = '2030-01-01T00:00:00Z';
const line = { userId: 'customer', productId: 'product', sku: 'TEST', productName: 'Test product',
  quantity: 1, unitPrice: 50, createdAt };

test('public requests omit tokens; protected writes send the contract and never cookies', async () => {
  const requests = [];
  globalThis.fetch = async (url, options) => { requests.push({ url, ...options }); return Response.json({ id: 'test' }); };
  const api = createApi(() => session, () => {});
  await api('/products');
  await api('/reservations', { method: 'POST', body: { productId: 'id', quantity: 2 }, key: 'key-1', authenticated: true });
  assert.equal(requests[0].headers.Authorization, undefined);
  assert.equal(requests[1].headers.Authorization, 'Bearer test-token');
  assert.equal(requests[1].headers['Idempotency-Key'], 'key-1');
  assert.deepEqual(JSON.parse(requests[1].body), { productId: 'id', quantity: 2 });
  assert.equal(requests[1].credentials, 'omit');
  assert.equal(requests[1].cache, 'no-store');
});

test('401 clears authenticated sessions; 429 gives retry guidance; HTML errors stay generic', async () => {
  let expired = 0;
  const api = createApi(() => session, () => expired++);
  globalThis.fetch = async () => new Response('{}', { status: 401 });
  await assert.rejects(api('/me/orders', { authenticated: true }), error => error.status === 401);
  assert.equal(expired, 1);
  await assert.rejects(api('/auth/login'), error => error.status === 401);
  assert.equal(expired, 1);
  globalThis.fetch = async () => new Response('{}', { status: 429, headers: { 'Retry-After': '23' } });
  await assert.rejects(api('/auth/login'), /23 seconds/);
  globalThis.fetch = async () => new Response('<html>upstream details</html>', { status: 503 });
  await assert.rejects(api('/products'), /temporarily unavailable/);
});

test('uncertain reservation retry retains key and body; each new action gets a fresh key', async () => {
  const calls = []; let fail = true; let key = 0;
  const operations = createOperations(async (path, options) => {
    calls.push({ path, ...options }); if (fail) throw new ApiError('network'); return {};
  }, () => `key-${++key}`);
  await assert.rejects(operations.run('/reservations', { quantity: 1 }), /network/);
  await assert.rejects(operations.run('/reservations', { quantity: 2 }), /previous action/);
  fail = false;
  await operations.run('/reservations', { quantity: 1 });
  await operations.run('/reservations/id/confirm');
  await operations.run('/reservations/id/cancel');
  assert.equal(calls[0].key, calls[1].key);
  assert.equal(calls[2].key, 'key-2');
  assert.equal(calls[3].key, 'key-3');
  assert.equal(calls[2].body, undefined);
  assert.equal(operations.pending, null);
});

test('double submission is blocked and definite rejections release the action', async () => {
  let release;
  const operations = createOperations(() => new Promise(resolve => { release = resolve; }), () => 'key');
  const running = operations.run('/reservations');
  await assert.rejects(operations.run('/reservations'), /already in progress/);
  release({}); await running;
  const rejected = createOperations(async () => { throw new ApiError('Insufficient stock', 409); }, () => 'key');
  await assert.rejects(rejected.run('/reservations'), /Insufficient stock/);
  assert.equal(rejected.pending, null);
});

test('sign-out clears retry state even when an older request finishes later', async () => {
  const waiting = [];
  const operations = createOperations(() => new Promise((resolve, reject) => waiting.push({ resolve, reject })), () => 'key');
  const request = operations.run('/reservations');
  operations.clear();
  const nextSessionRequest = operations.run('/reservations/id/cancel');
  waiting[0].reject(new ApiError('network'));
  await assert.rejects(request);
  await assert.rejects(operations.run('/reservations'), /already in progress/);
  assert.equal(operations.pending.path, '/reservations/id/cancel');
  waiting[1].resolve({}); await nextSessionRequest;
  assert.equal(operations.pending, null);
});

test('a 401 that finishes after a new sign-in leaves the new session active', async () => {
  let current = session;
  const api = createApi(() => current, () => { current = null; });
  let finish;
  globalThis.fetch = () => new Promise(resolve => { finish = resolve; });
  const old = api('/me/orders', { authenticated: true });
  const next = { token: session.token };
  current = next;
  finish(new Response('{}', { status: 401 }));
  await assert.rejects(old, error => error.status === 401);
  assert.equal(current, next);
});

test('a lost result followed by a rate-limited replay commits just one hold', async () => {
  const calls = [];
  const committed = new Map();
  globalThis.fetch = async (_, { headers, body }) => {
    const key = headers['Idempotency-Key'];
    calls.push({ key, body });
    if (calls.length === 2) return new Response('{}', { status: 429 });
    if (!committed.has(key)) committed.set(key, {
      ...line, ...JSON.parse(body), id: String(committed.size + 1), status: 'PENDING', updatedAt: createdAt,
      expiresAt: '2030-01-01T00:05:00Z'
    });
    if (calls.length === 1) throw new Error('response lost');
    return Response.json(committed.get(key), { status: 201 });
  };
  const operations = createOperations(createApi(() => session, () => {}));
  const body = { productId: 'product', quantity: 1 };
  const reserve = () => operations.run('/reservations', body);
  await assert.rejects(reserve());
  await assert.rejects(reserve(), error => error.status === 429);
  await assert.rejects(operations.run('/reservations', { ...body, quantity: 2 }), /previous action/);
  assert.equal((await reserve()).id, '1');
  assert.equal(committed.size, 1);
  assert.deepEqual(calls, [calls[0], calls[0], calls[0]]);
  assert.equal(operations.pending, null);
  assert.equal((await reserve()).id, '2');
});

test('a first-attempt rate limit allows a different action', async () => {
  const operations = createOperations(async () => { throw new ApiError('Rate limited', 429); });
  await assert.rejects(operations.run('/reservations', { quantity: 1 }));
  assert.equal(operations.pending, null);
  await assert.rejects(operations.run('/reservations', { quantity: 2 }), error => error.status === 429);
});

test('truncated JSON, an incomplete receipt and a receipt for another hold all retain the key', async () => {
  const order = { ...line, id: 'order', status: 'PLACED', totalAmount: 50, reservationId: 'hold' };
  const receipts = ['{"id":', '{"id":"order","status":"PLACED"}', JSON.stringify({ ...order, reservationId: 'other' }), JSON.stringify(order)];
  const keys = new Set();
  globalThis.fetch = async (_, { headers }) => {
    keys.add(headers['Idempotency-Key']);
    return new Response(receipts.shift(), { status: 201 });
  };
  const operations = createOperations(createApi(() => session, () => {}));
  const confirm = () => operations.run('/reservations/hold/confirm');
  for (let i = 0; i < 3; i++) {
    await assert.rejects(confirm(), error => error.status === 0);
    assert.ok(operations.pending);
  }
  assert.equal((await confirm()).id, 'order');
  assert.equal(keys.size, 1);
  assert.equal(operations.pending, null);
});
