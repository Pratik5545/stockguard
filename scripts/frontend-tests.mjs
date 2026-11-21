import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
const source = await readFile(new URL('../src/main/resources/static/assets/api.js', import.meta.url), 'utf8');
const { createApi, createOperations, ApiError } = await import(`data:text/javascript;base64,${Buffer.from(source).toString('base64')}`);

test('public requests omit tokens; protected writes send the contract and never cookies', async () => {
  const requests = [];
  globalThis.fetch = async (url, options) => { requests.push({ url, ...options }); return new Response('{}'); };
  const api = createApi(() => 'test-token', () => {});
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
  const api = createApi(() => 'test-token', () => expired++);
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
  let reject;
  const operations = createOperations(() => new Promise((_, fail) => { reject = fail; }), () => 'key');
  const request = operations.run('/reservations');
  operations.clear(); reject(new ApiError('network'));
  await assert.rejects(request);
  assert.equal(operations.pending, null);
});
