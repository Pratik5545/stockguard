// Uses temporary tooling installed in ignored target/ui-qa, never production dependencies.
const { chromium } = require('../target/ui-qa/node_modules/playwright');
const { readFile } = require('node:fs/promises');
const assert = require('node:assert/strict');
const path = require('node:path');

(async () => {
  const browser = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: true });
  try {
    const page = await browser.newPage();
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    let role = 'CUSTOMER'; let available = 7; let failCatalog = false; let holds = []; let orders = [];
    let extraProducts = []; let pauseNextCatalogRead; let incompleteStock = false;
    const product = { id: '11111111-1111-4111-8111-111111111111', sku: 'TEST-001', name: 'Browser test product', description: 'Local test fixture, not production data.', price: 1250, availableQuantity: available };
    const json = (route, data, status = 200) => route.fulfill({ status, contentType: 'application/json', headers: { Date: new Date().toUTCString() }, body: JSON.stringify(data) });
    const paged = items => ({ items, page: 0, size: 12, totalElements: items.length, totalPages: items.length ? 1 : 0 });
    await page.route('https://stockguard.test/**', async route => {
      const request = route.request(); const url = new URL(request.url());
      if (!url.pathname.startsWith('/api/')) {
        const file = url.pathname === '/' ? 'index.html' : url.pathname.slice(1);
        return route.fulfill({ contentType: file.endsWith('.css') ? 'text/css' : file.endsWith('.js') ? 'text/javascript' : 'text/html', body: await readFile(path.join(__dirname, '../src/main/resources/static', file)) });
      }
      const endpoint = url.pathname.replace('/api/v1', '');
      if (endpoint === '/products') {
        if (failCatalog) return json(route, { detail: 'Unavailable' }, 503);
        const products = [{ ...product, availableQuantity: available }, ...extraProducts];
        const number = Number(url.searchParams.get('page'));
        const result = { page: number, size: 12, totalElements: products.length, totalPages: Math.ceil(products.length / 12),
          items: products.slice(number * 12, (number + 1) * 12) };
        if (number === 0 && pauseNextCatalogRead) {
          const pause = pauseNextCatalogRead;
          pauseNextCatalogRead = null;
          await pause;
        }
        return json(route, result);
      }
      if (endpoint === '/auth/register') return json(route, { id: 'registration', email: request.postDataJSON().email, role: 'CUSTOMER' }, 201);
      if (endpoint === '/auth/login') {
        const claims = Buffer.from(JSON.stringify({ role, exp: Math.floor(Date.now()/1000) + 900 })).toString('base64url');
        return json(route, { accessToken: `test.${claims}.test`, expiresIn: 900 });
      }
      if (endpoint === '/me/reservations') return json(route, paged(holds));
      if (endpoint === '/me/orders') return json(route, paged(orders));
      if (endpoint === '/reservations') {
        assert.ok(request.headers()['idempotency-key']);
        available--;
        const createdAt = new Date().toISOString();
        holds = [{ ...product, id: 'hold-1', userId: 'customer', productId: product.id, productName: product.name, status: 'PENDING',
          quantity: 1, unitPrice: product.price, createdAt, updatedAt: createdAt, expiresAt: new Date(Date.now() + 300000).toISOString() }];
        return json(route, holds[0], 201);
      }
      if (endpoint.endsWith('/confirm')) {
        assert.ok(request.headers()['idempotency-key']); holds[0].status = 'CONFIRMED';
        orders = [{ ...holds[0], id: 'order-1', reservationId: holds[0].id, status: 'PLACED', totalAmount: product.price }]; return json(route, orders[0], 201);
      }
      if (endpoint.endsWith('/cancel')) { holds[0].status = 'CANCELLED'; available++; return json(route, holds[0]); }
      if (endpoint === '/admin/products') {
        const fields = request.postDataJSON();
        return json(route, { ...product, ...fields, price: Number(fields.price), availableQuantity: fields.initialStock }, 201);
      }
      if (endpoint.endsWith('/stock-adjustments')) return json(route, incompleteStock ? {} :
        { productId: product.id, onHandQuantity: 10, reservedQuantity: 1, availableQuantity: 9 });
      return json(route, {}, 404);
    });
    await page.goto('https://stockguard.test/');
    await page.getByRole('heading', { name: product.name }).waitFor();
    for (const width of [320, 390, 768, 1280]) {
      await page.setViewportSize({ width, height: 900 });
      assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true, `overflow at ${width}`);
      await page.screenshot({ path: `target/frontend-${width}.png`, fullPage: true });
    }
    extraProducts = Array.from({ length: 12 }, (_, i) => ({ ...product, id: `extra-${i}`, name: `Product ${i + 2}` }));
    await page.getByRole('button', { name: 'Refresh stock' }).click();
    await page.getByText('Page 1 of 2').waitFor();
    let resume;
    pauseNextCatalogRead = new Promise(resolve => { resume = resolve; });
    const staleRequest = page.waitForRequest(request => request.url().includes('/products?page=0'));
    const refreshVisibleView = () => page.evaluate(() => document.dispatchEvent(new Event('visibilitychange')));
    await refreshVisibleView();
    const delayed = await staleRequest;
    await page.getByRole('button', { name: 'Next', exact: true }).click();
    await page.getByText('Page 2 of 2').waitFor();
    resume();
    await (await delayed.response()).finished();
    await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
    assert.equal(await page.locator('#catalog-pages span').textContent(), 'Page 2 of 2');
    assert.deepEqual(await page.locator('#products h3').allTextContents(), ['Product 13']);
    extraProducts = [];
    await refreshVisibleView();
    await page.getByRole('heading', { name: product.name }).waitFor();
    assert.equal(await page.locator('#catalog-pages button').count(), 0);
    await page.getByRole('button', { name: 'Sign in', exact: true }).first().click();
    await page.getByLabel('Email', { exact: true }).fill('fixture@example.test');
    await page.getByLabel('Password', { exact: true }).fill('local-fixture-password');
    await page.locator('#auth-submit').click();
    await page.getByRole('button', { name: 'Reserve', exact: true }).waitFor();
    await page.getByRole('button', { name: 'Reserve', exact: true }).click();
    const confirm = page.getByRole('button', { name: 'Confirm order' });
    await confirm.focus();
    holds.unshift({ ...holds[0], id: 'different-hold', productName: 'Earlier purchase', status: 'CONFIRMED' });
    await refreshVisibleView();
    await page.getByRole('heading', { name: 'Earlier purchase' }).waitFor();
    assert.ok(await confirm.evaluate(node => node === document.activeElement));
    holds.shift();
    await page.getByRole('button', { name: 'Confirm order' }).click();
    await page.getByRole('link', { name: 'Orders', exact: true }).click();
    await page.getByText('Order total:').waitFor();
    await page.getByRole('link', { name: 'Catalog', exact: true }).click();
    await page.getByRole('button', { name: 'Reserve', exact: true }).click();
    page.once('dialog', dialog => dialog.accept());
    await page.getByRole('button', { name: 'Cancel hold' }).click();
    await page.getByText('Hold cancelled. Stock has been released.', { exact: true }).waitFor();
    holds[0].status = 'PENDING'; holds[0].expiresAt = new Date(Date.now() - 1000).toISOString();
    await page.getByRole('link', { name: 'Catalog', exact: true }).click();
    await page.getByRole('link', { name: 'My holds', exact: true }).click();
    await page.getByText('Deadline passed · awaiting server release').waitFor();
    assert.equal(await page.getByRole('button', { name: 'Confirm order' }).isDisabled(), true);
    await page.getByRole('button', { name: 'Sign out', exact: true }).click();
    role = 'ADMIN';
    await page.locator('#auth-open').click();
    await page.getByLabel('Password', { exact: true }).fill('local-fixture-password');
    await page.locator('#auth-submit').click();
    await page.getByRole('button', { name: 'Adjust stock', exact: true }).click();
    await page.locator('#stock-form input[name=delta]').fill('3');
    incompleteStock = true;
    await page.getByRole('button', { name: 'Apply adjustment' }).click();
    await page.getByText('The result is uncertain.', { exact: false }).waitFor();
    assert.equal(await page.locator('#stock-result').textContent(), '');
    assert.equal(await page.locator('#stock-form input[name=delta]').inputValue(), '3');
    incompleteStock = false;
    await page.getByRole('button', { name: 'Apply adjustment' }).click();
    await page.getByText('On hand: 10').waitFor();
    await page.locator('#product-form input[name=sku]').fill('TEST-002');
    await page.locator('#product-form input[name=name]').fill('Another test product');
    await page.locator('#product-form input[name=price]').fill('10');
    await page.locator('#product-form input[name=initialStock]').fill('5');
    await page.getByRole('button', { name: 'Create product', exact: true }).click();
    await page.getByText('Product created:').waitFor();
    await page.getByRole('link', { name: 'Catalog', exact: true }).click();
    failCatalog = true; await page.getByRole('button', { name: 'Refresh stock' }).click();
    await page.getByRole('button', { name: 'Try again', exact: true }).waitFor();
    failCatalog = false; await page.getByRole('button', { name: 'Try again', exact: true }).click();
    await page.getByRole('heading', { name: product.name }).waitFor();
    await page.getByRole('button', { name: 'Sign out', exact: true }).click();
    await page.locator('#auth-open').click();
    await page.locator('#auth-toggle').click();
    await page.getByLabel('Password', { exact: true }).fill('local-fixture-password');
    await page.locator('#auth-submit').click();
    await page.getByText('Registration received. Sign in with your password.').waitFor();
    await page.keyboard.press('Escape');
    assert.equal(await page.locator('#auth-dialog').isVisible(), false);
    assert.deepEqual(errors, []);
    console.log('PASS: responsive layout, stale pagination, keyboard focus, registration, holds/orders, expiry, admin writes and uncertain responses, recovery and Escape (fixture API).');
  } finally { await browser.close(); }
})().catch(error => { console.error(error.message); process.exitCode = 1; });
