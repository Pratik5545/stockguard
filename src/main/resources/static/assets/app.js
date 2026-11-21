import { createApi, createOperations, tokenClaims } from './api.js';

const $ = selector => document.querySelector(selector);
const state = { token: null, role: null, expires: 0, generation: 0, offset: 0,
  view: 'catalog', pages: { catalog: 0, reservations: 0, orders: 0 }, loading: new Set(), register: false };
const api = createApi(() => state.token, () => logout('Your session ended. Please sign in again.'), date => { state.offset = date - Date.now(); });
const operations = createOperations(api);
const now = () => Date.now() + state.offset;
const amount = value => Number(value).toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 });
const dateText = value => new Date(value).toLocaleString();

function element(tag, text, className) {
  const node = document.createElement(tag);
  if (text !== undefined) node.textContent = text;
  if (className) node.className = className;
  return node;
}
function button(text, action, className) {
  const node = element('button', text, className);
  node.type = 'button'; node.addEventListener('click', action); return node;
}
function notice(message, error = false) {
  const node = $('#notice'); node.replaceChildren(document.createTextNode(message));
  node.className = error ? 'error' : ''; node.hidden = false;
  if (operations.pending) node.append(' ', button('Retry previous action', () => mutate(operations.pending.path, operations.pending.body)));
}
function empty(container, message) { container.replaceChildren(element('p', message, 'empty-state')); }
function sessionUi() {
  document.querySelectorAll('[data-customer]').forEach(node => { node.hidden = state.role !== 'CUSTOMER'; });
  document.querySelectorAll('[data-admin]').forEach(node => { node.hidden = state.role !== 'ADMIN'; });
  $('#auth-open').hidden = Boolean(state.token); $('#logout').hidden = !state.token;
  $('#identity').textContent = state.role ? `${state.role === 'ADMIN' ? 'Admin' : 'Customer'} session` : 'Guest';
}
function logout(message = 'You have signed out.') {
  state.token = null; state.role = null; state.expires = 0; state.generation++;
  operations.clear(); state.pages.reservations = 0; state.pages.orders = 0;
  $('#holds').replaceChildren(); $('#order-list').replaceChildren();
  $('#product-form').reset(); $('#stock-form').reset(); $('#stock-result').textContent = '';
  sessionUi(); location.hash = 'catalog'; route(); notice(message);
}
function route() {
  let view = location.hash.slice(1) || 'catalog';
  if (!['catalog', 'reservations', 'orders', 'admin'].includes(view)) view = 'catalog';
  if ((['reservations', 'orders'].includes(view) && state.role !== 'CUSTOMER') || (view === 'admin' && state.role !== 'ADMIN')) {
    view = 'catalog'; notice('Sign in with the appropriate account to open that workspace.');
  }
  state.view = view;
  document.querySelectorAll('.view').forEach(node => { node.hidden = node.id !== view; });
  document.querySelectorAll('nav a').forEach(node => {
    if (node.hash === `#${view}`) node.setAttribute('aria-current', 'page'); else node.removeAttribute('aria-current');
  });
  load(view);
}
function pagination(view, data) {
  const node = $(`#${view}-pages`); node.replaceChildren();
  if (data.totalPages <= 1) return;
  const move = delta => { state.pages[view] += delta; load(view); };
  const previous = button('Previous', () => move(-1)); previous.disabled = data.page === 0;
  const next = button('Next', () => move(1)); next.disabled = data.page + 1 >= data.totalPages;
  node.append(previous, element('span', `Page ${data.page + 1} of ${data.totalPages}`), next);
}
async function load(view = state.view, quiet = false) {
  const loadKey = `${view}:${state.generation}`;
  if (view === 'admin' || state.loading.has(loadKey)) return;
  if (view !== 'catalog' && state.role !== 'CUSTOMER') return;
  const generation = state.generation;
  const container = $(view === 'catalog' ? '#products' : view === 'reservations' ? '#holds' : '#order-list');
  state.loading.add(loadKey); container.setAttribute('aria-busy', 'true');
  if (!quiet) empty(container, 'Loading… The demo server may take a moment to wake up.');
  try {
    const data = await api(`${view === 'catalog' ? '/products' : `/me/${view}`}?page=${state.pages[view]}&size=12`, { authenticated: view !== 'catalog' });
    if (generation !== state.generation) return;
    if (data.items.length === 0 && data.page > 0) { state.pages[view] = 0; state.loading.delete(loadKey); return load(view); }
    // Preserve quantity input and focus during background catalog updates.
    if (quiet && view === 'catalog' && container.children.length === data.items.length && data.items.every((p, i) => container.children[i].dataset.id === p.id)) {
      data.items.forEach((product, i) => updateProduct(container.children[i], product));
    } else {
      container.replaceChildren(...data.items.map(view === 'catalog' ? productCard : item => recordCard(item, view)));
    }
    if (!data.items.length) empty(container, view === 'catalog' ? 'No products yet. Check back soon.' : view === 'reservations' ? 'No holds yet. Reserve a product from the catalog to get started.' : 'No orders yet. Confirm a hold to place your first order.');
    pagination(view, data);
    if (view === 'catalog') $('#catalog-status').textContent = `${data.totalElements} products · Updated ${new Date().toLocaleTimeString()}`;
    tick();
  } catch (error) {
    if (generation !== state.generation) return;
    if (!quiet) { empty(container, error.message); container.append(button('Try again', () => load(view))); }
    else if (view === 'catalog') $('#catalog-status').textContent = 'Stock refresh failed. Displayed availability may be outdated.';
    else notice(`Could not refresh ${view}. ${error.message}`, true);
  } finally {
    state.loading.delete(loadKey); container.removeAttribute('aria-busy');
  }
}
function updateProduct(card, product) {
  card.querySelector('h3').textContent = product.name;
  card.querySelector('.description').textContent = product.description || 'Ready for your next order.';
  card.querySelector('.price').textContent = amount(product.price);
  const badge = card.querySelector('.badge'); badge.textContent = product.availableQuantity > 0 ? `${product.availableQuantity} available` : 'Out of stock';
  badge.className = `badge${product.availableQuantity > 0 ? '' : ' empty'}`;
  const reserve = card.querySelector('.reserve-form button');
  if (reserve) reserve.disabled = product.availableQuantity < 1;
}
function productCard(product) {
  const card = element('article', undefined, 'product'); card.dataset.id = product.id;
  const top = element('div', undefined, 'product-top'); top.append(element('span', product.sku, 'sku'), element('span', '', 'badge'));
  card.append(top, element('h3'), element('p', '', 'description'), element('div', '', 'price'), element('p', 'Unit price · currency not specified', 'muted'));
  if (state.role === 'CUSTOMER') {
    const form = element('form', undefined, 'reserve-form');
    const label = element('label', 'Quantity'); const input = element('input');
    input.type = 'number'; input.min = '1'; input.max = '2'; input.step = '1'; input.value = '1'; input.required = true;
    label.append(input); const submit = element('button', 'Reserve', 'primary'); form.append(label, submit);
    form.addEventListener('submit', async event => { event.preventDefault(); submit.disabled = true;
      try { await mutate('/reservations', { productId: product.id, quantity: Number(input.value) }); }
      finally { submit.disabled = false; }
    }); card.append(form);
  } else if (state.role === 'ADMIN') {
    card.append(button('Adjust stock', () => { $('#stock-form').elements.productId.value = product.id; location.hash = 'admin'; $('#stock-form').elements.delta.focus(); }));
  } else card.append(button('Sign in to reserve', openAuth, 'primary'));
  updateProduct(card, product); return card;
}
function recordCard(item, view) {
  const card = element('article', undefined, 'record');
  const top = element('div', undefined, 'record-top'); top.append(element('h3', item.productName), element('span', item.status, 'badge'));
  card.append(top, element('p', `${item.quantity} × ${amount(item.unitPrice)} · ${item.sku}`), element('p', `ID: ${item.id}`, 'muted'), element('p', `Created ${dateText(item.createdAt)}`, 'muted'));
  if (view === 'orders') card.append(element('p', `Order total: ${amount(item.totalAmount)} (currency not specified)`));
  else if (item.status === 'PENDING') {
    const timer = element('p', '', 'countdown'); timer.dataset.expires = item.expiresAt;
    const actions = element('div', undefined, 'actions');
    actions.append(button('Confirm order', event => lifecycle(event, item.id, 'confirm'), 'primary'), button('Cancel hold', event => lifecycle(event, item.id, 'cancel')));
    card.append(timer, element('p', `Deadline: ${dateText(item.expiresAt)}`, 'muted'), actions);
  }
  return card;
}
async function lifecycle(event, id, action) {
  const buttons = event.currentTarget.parentElement.querySelectorAll('button'); buttons.forEach(node => { node.disabled = true; });
  try { await mutate(`/reservations/${id}/${action}`); }
  finally { buttons.forEach(node => { node.disabled = false; }); tick(); }
}
async function mutate(path, body) {
  const generation = state.generation;
  try {
    await operations.run(path, body);
    if (generation !== state.generation) return;
    notice(path.endsWith('/confirm') ? 'Order placed. You can find it in Orders.' : path.endsWith('/cancel') ? 'Hold cancelled. Stock has been released.' : 'Stock reserved. Confirm your hold before the deadline.');
    state.pages.reservations = 0;
    if (path === '/reservations') { location.hash = 'reservations'; }
    await load(state.view);
  } catch (error) { if (generation === state.generation) notice(error.message, true); }
}
function tick() {
  if (state.token && now() >= state.expires) { logout('Your session expired. Sign in again to manage your holds.'); return; }
  document.querySelectorAll('[data-expires]').forEach(node => {
    const seconds = Math.max(0, Math.ceil((Date.parse(node.dataset.expires) - now()) / 1000));
    node.textContent = seconds ? `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')} remaining` : 'Deadline passed · awaiting server release';
    if (!seconds) node.parentElement.querySelectorAll('button').forEach(button => { button.disabled = true; });
  });
}
function openAuth() { $('#auth-error').textContent = ''; $('#auth-dialog').showModal(); }
function authMode() {
  $('#auth-title').textContent = state.register ? 'Create your account' : 'Sign in';
  $('#auth-submit').textContent = state.register ? 'Create account' : 'Sign in';
  $('#auth-toggle').textContent = state.register ? 'Already registered? Sign in' : 'New here? Create an account';
  $('#auth-description').textContent = state.register ? 'Use a password of 12–128 characters. New accounts are customers.' : 'Your holds and orders are waiting.';
  const password = $('#auth-form').elements.password; password.minLength = state.register ? 12 : 1;
  password.autocomplete = state.register ? 'new-password' : 'current-password'; password.value = '';
  $('#auth-error').textContent = '';
}
$('#auth-form').addEventListener('submit', async event => {
  event.preventDefault(); const form = event.currentTarget; const submit = $('#auth-submit');
  submit.disabled = true; $('#auth-toggle').disabled = true; $('#auth-error').textContent = '';
  const register = state.register;
  try {
    const response = await api(`/auth/${register ? 'register' : 'login'}`, { method: 'POST', body: { email: form.elements.email.value.trim(), password: form.elements.password.value } });
    if (register) { state.register = false; authMode(); $('#auth-description').textContent = 'Account created. Sign in with your password.'; }
    else {
      // Claims control presentation only. The backend verifies signatures and permissions.
      const claims = tokenClaims(response.accessToken);
      state.token = response.accessToken; state.role = claims.role;
      state.expires = Math.min(claims.exp * 1000, now() + response.expiresIn * 1000); state.generation++;
      sessionUi(); $('#auth-dialog').close(); notice('Signed in. Your session stays in this tab only.'); route();
    }
  } catch (error) { $('#auth-error').textContent = error.message; }
  finally { form.elements.password.value = ''; submit.disabled = false; $('#auth-toggle').disabled = false; }
});
async function adminSubmit(event, type) {
  event.preventDefault(); const form = event.currentTarget; const submit = form.querySelector('button');
  const generation = state.generation; submit.disabled = true;
  const fields = Object.fromEntries(new FormData(form));
  const path = type === 'product' ? '/admin/products' : `/admin/products/${encodeURIComponent(fields.productId.trim())}/stock-adjustments`;
  const body = type === 'product' ? { ...fields, price: fields.price, initialStock: Number(fields.initialStock) } : { delta: Number(fields.delta) };
  try {
    const result = await api(path, { method: 'POST', body, authenticated: true });
    if (generation !== state.generation) return;
    notice(type === 'product' ? `Product created: ${result.name}.` : 'Stock adjustment saved.');
    if (type === 'product') { form.reset(); $('#stock-form').elements.productId.value = result.id; }
    else { $('#stock-result').textContent = `On hand: ${result.onHandQuantity} · Reserved: ${result.reservedQuantity} · Available: ${result.availableQuantity}`; form.elements.delta.value = ''; }
  } catch (error) {
    if (generation === state.generation) notice(error.status === 0 || error.status >= 500 ? 'The result is uncertain. Check the catalog or API before resubmitting; admin writes cannot be safely retried automatically.' : error.message, true);
  } finally { submit.disabled = false; }
}
$('#product-form').addEventListener('submit', event => adminSubmit(event, 'product'));
$('#stock-form').addEventListener('submit', event => adminSubmit(event, 'stock'));
$('#auth-open').addEventListener('click', openAuth);
$('#auth-close').addEventListener('click', () => $('#auth-dialog').close());
$('#auth-dialog').addEventListener('close', () => { $('#auth-form').elements.password.value = ''; });
$('#auth-toggle').addEventListener('click', () => { state.register = !state.register; authMode(); });
$('#logout').addEventListener('click', () => logout());
$('#refresh').addEventListener('click', () => load('catalog'));
window.addEventListener('hashchange', route);
window.addEventListener('pageshow', event => { if (event.persisted) logout('Sign in again to continue.'); });
document.addEventListener('visibilitychange', () => { if (!document.hidden) { tick(); load(state.view, true); } });
setInterval(tick, 1000);
setInterval(() => { if (!document.hidden) load(state.view, true); }, 15000);
sessionUi(); route();
