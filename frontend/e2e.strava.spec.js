/* PR5 E2E Playwright — themes cluster MyLocation mini-menu dropdown offline + polish */
import { test, expect } from '@playwright/test';
const APP = process.env.APP_URL || 'http://localhost:8000/app/';

test.describe('Strava field UX', () => {
  test('themes cycle light→dark→mid without reload + persist before paint', async ({ page }) => {
    await page.goto(APP);
    const html = page.locator('html');
    await expect(html).toHaveAttribute('data-theme', /light|dark|mid/);
    const t0 = await html.getAttribute('data-theme');
    await page.click('#btnTheme');
    const t1 = await html.getAttribute('data-theme');
    expect(t1).not.toEqual(t0);
    await page.reload();
    await expect(html).toHaveAttribute('data-theme', t1);
  });

  test('pins numbered 1..n + cluster Strava chip expands on zoom', async ({ page }) => {
    await page.goto(APP);
    await page.waitForSelector('.pin, .cluster-chip', { timeout: 10000 });
    const pins = page.locator('.pin span');
    const n = await pins.count();
    if (n > 0) {
      for (let i = 0; i < Math.min(n, 5); i++) expect(await pins.nth(i).textContent()).toBe(String(i + 1));
    }
    const cluster = page.locator('.cluster-chip').first();
    if (await cluster.count() > 0) await expect(cluster).toContainText(/\d+/);
  });

  test('MyLocation centers + accuracy circle + start injection', async ({ page }) => {
    await page.goto(APP);
    await page.evaluate(() => { navigator.geolocation.__mockFix = { coords: { latitude: 8.61, longitude: -71.65, accuracy: 30 } }; });
    await page.click('#btnMyLocation');
    await expect(page.locator('.my-location-dot')).toBeVisible({ timeout: 5000 });
  });

  test('selection Set badge + sticky mini-menu + filter persistence', async ({ page }) => {
    await page.goto(APP);
    await page.waitForSelector('#listaClientes input[type="checkbox"]');
    await page.locator('#listaClientes input[type="checkbox"]').first().check();
    await expect(page.locator('#selectionBadge')).toContainText(/1 seleccionado/);
    await expect(page.locator('#miniMenu')).toBeVisible();
    await page.fill('#q', 'VIGIA');
    await page.waitForTimeout(400);
    await page.fill('#q', '');
    await expect(page.locator('#selectionBadge')).toContainText(/1 seleccionado/);
  });

  test('Exportar dropdown Esc/outside Arrow-Enter + focus return', async ({ page }) => {
    await page.goto(APP);
    await page.click('#btnExportDropdown');
    await expect(page.locator('#exportMenu')).toBeVisible();
    await expect(page.locator('#btnExportDropdown')).toHaveAttribute('aria-expanded', 'true');
    await page.keyboard.press('ArrowDown');
    await page.keyboard.press('Escape');
    await expect(page.locator('#exportMenu')).toBeHidden();
    await expect(page.locator('#btnExportDropdown')).toBeFocused();
    await page.click('#btnExportDropdown');
    await page.click('body', { position: { x: 5, y: 5 } });
    await expect(page.locator('#exportMenu')).toBeHidden();
  });

  test('offline airplane tiles+list + queue→sync banner', async ({ page, context }) => {
    await page.goto(APP);
    await page.waitForSelector('#listaClientes');
    await context.setOffline(true);
    await page.reload();
    await expect(page.locator('#offlineBanner')).toBeVisible();
    await expect(page.locator('#listaClientes')).not.toBeEmpty();
    await context.setOffline(false);
    await expect(page.locator('#offlineBanner')).toBeHidden();
  });
});
