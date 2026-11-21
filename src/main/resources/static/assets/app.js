import { createApi, createOperations, isInventory, isOrder, isProduct, isReservation, tokenClaims } from './api.js';

const $ = selector => document.querySelector(selector);
const state = { session: null, offset: 0,
  view: 'catalog', pages: { catalog: 0, reservations: 0, orders: 0 }, loading: new Map(), register: false };
const api = createApi(() => state.session, () => logout('Your session ended. Please sign in again.'), date => { state.offset = date - Date.now(); });
const operations = createOperations(api);
const now = () => Date.now() + state.offset;
const amount = value => Number(value).toLocaleString('en-IN', { style: 'currency', currency: 'INR' });
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
  const pending = operations.pending;
  if (pending) node.append(' ', button('Retry previous action', () => {
    if (operations.pending === pending) mutate(pending.path, pending.body);
  }));
}
function empty(container, message) { container.replaceChildren(element('p', message, 'empty-state')); }
function sessionUi() {
  const role = state.session?.role;
  document.querySelectorAll('[data-customer]').forEach(node => { node.hidden = role !== 'CUSTOMER'; });
  document.querySelectorAll('[data-admin]').forEach(node => { node.hidden = role !== 'ADMIN'; });
  $('#auth-open').hidden = Boolean(state.session); $('#logout').hidden = !state.session;
  $('#identity').textContent = role ? `${role === 'ADMIN' ? 'Admin' : 'Customer'} session` : 'Guest';
}
function logout(message = 'You have signed out.') {
  state.session = null; state.loading.clear();
  operations.clear(); state.pages.reservations = 0; state.pages.orders = 0;
  for (const node of document.querySelectorAll('#holds, #order-list, #reservations-pages, #orders-pages')) {
    node.replaceChildren(); node.removeAttribute('aria-busy');
  }
  $('#product-form').reset(); $('#stock-form').reset(); $('#stock-result').textContent = '';
  sessionUi(); location.hash = 'catalog'; route(); notice(message);
}
function route() {
  if (location.hash === '#main') { $('#main').focus(); return; }
  let view = location.hash.slice(1) || 'catalog';
  if (!['catalog', 'reservations', 'orders', 'admin'].includes(view)) view = 'catalog';
  if ((['reservations', 'orders'].includes(view) && state.session?.role !== 'CUSTOMER') || (view === 'admin' && state.session?.role !== 'ADMIN')) {
    view = 'catalog'; notice('Sign in with the appropriate account to open that workspace.');
  }
  state.view = view;
  const titles = { catalog: 'Product catalog', reservations: 'My holds', orders: 'Order history', admin: 'Manage inventory' };
  const heading = $(`#${view} h1`);
  heading.textContent = titles[view];
  heading.tabIndex = -1;
  document.title = `${titles[view]} | StockGuard`;
  document.querySelectorAll('.view').forEach(node => { node.hidden = node.id !== view; });
  document.querySelectorAll('nav a').forEach(node => {
    if (node.hash === `#${view}`) node.setAttribute('aria-current', 'page'); else node.removeAttribute('aria-current');
  });
  load(view);
  heading.focus({ preventScroll: true });
}
function pagination(view, data) {
  const node = $(`#${view}-pages`);
  if (data.totalPages <= 1) { node.replaceChildren(); return; }
  if (!node.childElementCount) {
    const move = delta => {
      const page = Number(node.dataset.page) + delta;
      if (state.pages[view] === page) return;
      state.pages[view] = page;
      load(view);
    };
    node.append(button('Previous', () => move(-1)), element('span'), button('Next', () => move(1)));
  }
  node.dataset.page = data.page;
  const [previous, label, next] = node.children;
  previous.disabled = data.page === 0;
  next.disabled = data.page + 1 >= data.totalPages;
  label.textContent = `Page ${data.page + 1} of ${data.totalPages}`;
}
async function load(view = state.view, quiet = false) {
  if (view === 'admin' || (quiet && state.loading.has(view))) return;
  if (view !== 'catalog' && state.session?.role !== 'CUSTOMER') return;
  const request = { page: state.pages[view], session: state.session };
  const current = () => state.loading.get(view) === request && state.session === request.session;
  const container = $(view === 'catalog' ? '#products' : view === 'reservations' ? '#holds' : '#order-list');
  const validItem = view === 'catalog' ? isProduct : view === 'reservations' ? isReservation : isOrder;
  state.loading.set(view, request); container.setAttribute('aria-busy', 'true');
  if (!quiet) empty(container, 'Loading… The demo server may take a moment to wake up.');
  try {
    const data = await api(`${view === 'catalog' ? '/products' : `/me/${view}`}?page=${request.page}&size=12`, {
      authenticated: view !== 'catalog',
      validate: data => data.page === request.page && Array.isArray(data.items) && data.items.every(validItem) &&
        Number.isInteger(data.totalPages) && Number.isInteger(data.totalElements)
    });
    if (!current()) return;
    if (data.items.length === 0 && data.page > 0) { state.pages[view] = 0; return load(view); }
    // Preserve quantity input and focus during background catalog updates.
    if (quiet && view === 'catalog' && container.children.length === data.items.length && data.items.every((p, i) => container.children[i].dataset.id === p.id)) {
      data.items.forEach((product, i) => updateProduct(container.children[i], product));
    } else if (view === 'catalog') container.replaceChildren(...data.items.map(productCard));
    else renderRecords(container, data.items, view);
    if (!data.items.length) empty(container, view === 'catalog' ? 'No products yet. Check back soon.' : view === 'reservations' ? 'No holds yet. Reserve a product from the catalog to get started.' : 'No orders yet. Confirm a hold to place your first order.');
    pagination(view, data);
    if (view === 'catalog') $('#catalog-status').textContent = `${data.totalElements} ${data.totalElements === 1 ? 'product' : 'products'} · Updated ${new Date().toLocaleTimeString()}`;
    tick();
  } catch (error) {
    if (!current()) return;
    if (!quiet) { empty(container, error.message); container.append(button('Try again', () => load(view))); }
    else if (view === 'catalog') $('#catalog-status').textContent = 'Stock refresh failed. Displayed availability may be outdated.';
    else notice(`Could not refresh ${view}. ${error.message}`, true);
  } finally {
    if (current()) { state.loading.delete(view); container.removeAttribute('aria-busy'); }
  }
}
function updateProduct(card, product) {
  card.querySelector('h3').textContent = product.name;
  card.querySelector('.description').textContent = product.description || 'No description provided.';
  card.querySelector('.price').textContent = amount(product.price);
  const badge = card.querySelector('.badge'); badge.textContent = product.availableQuantity > 0 ? `${product.availableQuantity} available` : 'Out of stock';
  badge.className = `badge${product.availableQuantity > 0 ? '' : ' empty'}`;
  const reserve = card.querySelector('.reserve-form button');
  if (reserve) reserve.disabled = product.availableQuantity < 1;
}
function productCard(product) {
  const card = element('article', undefined, 'product'); card.dataset.id = product.id;
  const top = element('div', undefined, 'product-top'); top.append(element('span', product.sku, 'sku'), element('span', '', 'badge'));
  card.append(top, element('h3'), element('p', '', 'description'), element('div', '', 'price'), element('p', 'Unit price · INR', 'muted'));
  if (state.session?.role === 'CUSTOMER') {
    const form = element('form', undefined, 'reserve-form');
    const label = element('label', 'Quantity'); const input = element('input');
    input.type = 'number'; input.min = '1'; input.max = '2'; input.step = '1'; input.value = '1'; input.required = true;
    label.append(input); const submit = element('button', 'Reserve', 'primary'); form.append(label, submit);
    form.addEventListener('submit', async event => { event.preventDefault(); submit.disabled = true;
      try { await mutate('/reservations', { productId: product.id, quantity: Number(input.value) }); }
      finally { submit.disabled = false; }
    }); card.append(form);
  } else if (state.session?.role === 'ADMIN') {
    card.append(button('Adjust stock', () => { $('#stock-form').elements.productId.value = product.id; location.hash = 'admin'; $('#stock-form').elements.delta.focus(); }));
  } else card.append(button('Sign in to reserve', openAuth, 'primary'));
  updateProduct(card, product); return card;
}
function recordCard(item, view) {
  const card = element('article', undefined, 'record');
  card.dataset.id = item.id;
  card.dataset.status = item.status;
  const top = element('div', undefined, 'record-top'); top.append(element('h3', item.productName), element('span', item.status, 'badge'));
  card.append(top, element('p', `${item.quantity} × ${amount(item.unitPrice)} · ${item.sku}`), element('p', `ID: ${item.id}`, 'muted'), element('p', `Created ${dateText(item.createdAt)}`, 'muted'));
  if (view === 'orders') card.append(element('p', `Order total: ${amount(item.totalAmount)}`));
  else if (item.status === 'PENDING') {
    const timer = element('p', '', 'countdown'); timer.dataset.expires = item.expiresAt;
    const actions = element('div', undefined, 'actions');
    actions.append(button('Confirm order', event => lifecycle(event, item.id, 'confirm'), 'primary'), button('Cancel hold', event => lifecycle(event, item.id, 'cancel')));
    card.append(timer, element('p', `Deadline: ${dateText(item.expiresAt)}`, 'muted'), actions);
  }
  return card;
}

function renderRecords(container, items, view) {
  const oldRows = new Map(Array.from(container.children, row => [row.dataset.id, row]));
  const focused = document.activeElement;
  const focusedId = container.contains(focused) ? focused.closest('.record')?.dataset.id : null;
  const rows = items.map(item => {
    const row = oldRows.get(item.id);
    return row && row.dataset.status === item.status ? row : recordCard(item, view);
  });
  rows.forEach((row, index) => {
    const position = container.children[index];
    if (position !== row) container.insertBefore(row, position || null);
  });
  while (container.childElementCount > rows.length) container.lastElementChild.remove();
  if (focusedId && state.view === view && document.activeElement !== focused) {
    const heading = rows.find(row => row.dataset.id === focusedId)?.querySelector('h3') || $(`#${view} h1`);
    heading.tabIndex = -1;
    (focused.isConnected && !focused.disabled ? focused : heading).focus({ preventScroll: true });
  }
}
async function lifecycle(event, id, action) {
  if (action === 'cancel' && !window.confirm('Cancel this hold and release its stock?')) return;
  const buttons = event.currentTarget.parentElement.querySelectorAll('button'); buttons.forEach(node => { node.disabled = true; });
  try { await mutate(`/reservations/${id}/${action}`); }
  finally { buttons.forEach(node => { node.disabled = false; }); tick(); }
}
async function mutate(path, body) {
  const session = state.session;
  try {
    await operations.run(path, body);
    if (session !== state.session) return;
    notice(path.endsWith('/confirm') ? 'Order placed. You can find it in Orders.' : path.endsWith('/cancel') ? 'Hold cancelled. Stock has been released.' : 'Stock reserved. Confirm your hold before the deadline.');
    state.pages.reservations = 0;
    if (path === '/reservations' && state.view !== 'reservations') location.hash = 'reservations';
    else await load(state.view);
  } catch (error) { if (session === state.session) notice(error.message, true); }
}
function tick() {
  if (state.session && now() >= state.session.expires) { logout('Your session expired. Sign in again to manage your holds.'); return; }
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
    const response = await api(`/auth/${register ? 'register' : 'login'}`, {
      method: 'POST', body: { email: form.elements.email.value.trim(), password: form.elements.password.value },
      validate: data => register ? typeof data.id === 'string' && data.role === 'CUSTOMER' && typeof data.email === 'string'
        : typeof data.accessToken === 'string' && Number.isFinite(data.expiresIn) && data.expiresIn > 0
    });
    if (register) { state.register = false; authMode(); $('#auth-description').textContent = 'Registration received. Sign in with your password.'; }
    else {
      // Claims control presentation only. The backend verifies signatures and permissions.
      const claims = tokenClaims(response.accessToken);
      if (!['ADMIN', 'CUSTOMER'].includes(claims.role) || !Number.isFinite(claims.exp)) throw new Error('Invalid sign-in response.');
      state.session = { token: response.accessToken, role: claims.role,
        expires: Math.min(claims.exp * 1000, now() + response.expiresIn * 1000) };
      sessionUi(); $('#auth-dialog').close(); notice('Signed in. Your session stays in this tab only.'); route();
    }
  } catch (error) { $('#auth-error').textContent = error.message; }
  finally { form.elements.password.value = ''; submit.disabled = false; $('#auth-toggle').disabled = false; }
});
async function adminSubmit(event, type) {
  event.preventDefault(); const form = event.currentTarget; const submit = form.querySelector('button');
  const session = state.session; submit.disabled = true;
  const fields = Object.fromEntries(new FormData(form));
  if (type === 'stock' && Number(fields.delta) < 0 && !window.confirm(`Remove ${Math.abs(Number(fields.delta))} units from physical stock?`)) {
    submit.disabled = false; return;
  }
  const path = type === 'product' ? '/admin/products' : `/admin/products/${encodeURIComponent(fields.productId.trim())}/stock-adjustments`;
  const body = type === 'product' ? { ...fields, price: fields.price, initialStock: Number(fields.initialStock) } : { delta: Number(fields.delta) };
  if (type === 'stock') $('#stock-result').textContent = '';
  try {
    const result = await api(path, {
      method: 'POST', body, authenticated: true,
      validate: data => type === 'product' ? isProduct(data) && data.sku === fields.sku
        : isInventory(data) && data.productId === fields.productId.trim().toLowerCase()
    });
    if (session !== state.session) return;
    notice(type === 'product' ? `Product created: ${result.name}.` : 'Stock adjustment saved.');
    if (type === 'product') { form.reset(); $('#stock-form').elements.productId.value = result.id; }
    else { $('#stock-result').textContent = `On hand: ${result.onHandQuantity} · Reserved: ${result.reservedQuantity} · Available: ${result.availableQuantity}`; form.elements.delta.value = ''; }
  } catch (error) {
    if (session === state.session) notice(error.status === 0 || error.status === 408 || error.status >= 500 ? 'The result is uncertain. Check current stock or product details before submitting again.' : error.message, true);
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
let fieldNumber = 0;
function validateField(field) {
  const id = field.dataset.errorId ||= `field-error-${++fieldNumber}`;
  let message = document.getElementById(id);
  if (!message) {
    message = element('span', '', 'field-error'); message.id = id;
    field.insertAdjacentElement('afterend', message);
    field.setAttribute('aria-describedby', id);
  }
  const invalid = !field.validity.valid;
  message.textContent = invalid ? field.validationMessage : '';
  field.setAttribute('aria-invalid', String(invalid));
}
document.addEventListener('invalid', event => {
  if (event.target.matches('input, textarea')) validateField(event.target);
}, true);
document.addEventListener('focusout', event => {
  if (event.target.matches('input, textarea')) validateField(event.target);
});
document.addEventListener('input', event => {
  if (event.target.matches('[aria-invalid=true]')) validateField(event.target);
});
document.addEventListener('reset', event => {
  event.target.querySelectorAll('.field-error').forEach(node => { node.textContent = ''; });
  event.target.querySelectorAll('[aria-invalid]').forEach(node => node.removeAttribute('aria-invalid'));
});
window.addEventListener('offline', () => notice('You are offline. Displayed stock may be outdated. Reconnect before submitting changes.', true));
window.addEventListener('online', () => { notice('Connection restored. Refreshing current records.'); load(state.view, true); });
window.addEventListener('hashchange', route);
window.addEventListener('pageshow', event => { if (event.persisted) logout('Sign in again to continue.'); });
document.addEventListener('visibilitychange', () => { if (!document.hidden) { tick(); load(state.view, true); } });
setInterval(tick, 1000);
setInterval(() => { if (!document.hidden) load(state.view, true); }, 15000);
sessionUi(); route();
