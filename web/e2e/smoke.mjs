// Browser smoke test of the running stack (make up, then make web-smoke): opens every page in
// headless Chromium, waits for real content, fails on console errors, and saves screenshots to
// .cache/screenshots. The screenshots show real register data (names): never commit them.
import { chromium } from "playwright";

const base = process.env.BASE_URL ?? "http://api:8080";
const out = process.env.OUT_DIR ?? "/out";
const company = process.env.COMPANY ?? "";
const pages = [
  ["overview", "/", "text=By legal family", 3000],
  ["search", "/search?q=rigas%20udens", "table tbody tr", 300],
  ["changes", "/changes", "table tbody tr", 300],
  ["pipeline", "/pipeline", "table tbody tr", 300],
  ["insights", "/insights", "table tbody tr", 300],
];

const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1280, height: 900 } });
const errors = [];
page.on("pageerror", (e) => errors.push(`${page.url()}: ${e.message}`));
page.on("console", (m) => m.type() === "error" && errors.push(`${page.url()}: ${m.text()}`));
let failed = 0;

async function visit(name, path, ready, settle) {
  const started = Date.now();
  await page.goto(base + path);
  try {
    await page.waitForSelector(ready, { timeout: 30_000 });
    await page.waitForTimeout(settle);
    console.log(`ok   ${name.padEnd(16)} ${Date.now() - started} ms`);
  } catch {
    failed++;
    console.log(`FAIL ${name.padEnd(16)} no "${ready}"`);
  }
  // Viewport screenshots: a full-page screenshot resizes the page and restarts the chart animations.
  await page.screenshot({ path: `${out}/${name}.png` });
}

for (const [name, path, ready, settle] of pages) {
  await visit(name, path, ready, settle);
}
// A company from the search results (or COMPANY), its page and its network at each level.
const reg = company || (await page.goto(`${base}/api/search?q=rigas%20udens`).then((r) => r.json())).items[0].regNumber;
await visit("company", `/companies/${reg}`, "text=Facts", 300);
for (const level of [1, 2, 3]) {
  await visit(`network-level-${level}`, `/companies/${reg}/network?level=${level}`, "svg g circle", 4000);
}
await page.goto(`${base}/pipeline`);
await page.locator("table tbody tr a").first().click();
await page.waitForSelector("text=Files").then(() => console.log("ok   run detail")).catch(() => { failed++; console.log("FAIL run detail"); });

console.log(errors.length ? `console errors:\n${errors.join("\n")}` : "no console errors");
await browser.close();
process.exit(failed + errors.length > 0 ? 1 : 0);
