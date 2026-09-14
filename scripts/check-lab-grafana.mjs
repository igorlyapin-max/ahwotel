import fs from 'node:fs/promises';
import path from 'node:path';
import {pathToFileURL} from 'node:url';
import assert from 'node:assert/strict';

const modulePath = process.env.PLAYWRIGHT_MODULE || 'playwright';
const {chromium} = await import(modulePath.startsWith('/') ? pathToFileURL(modulePath).href : modulePath);
const origin = process.env.GRAFANA_URL;
assert(origin && /^http:\/\/[A-Za-z0-9.-]+:\d+$/.test(origin), 'Set GRAFANA_URL');
const out = path.resolve('artifacts/otel-lab');
await fs.mkdir(out, {recursive: true});
const browser = await chromium.launch({headless: true, executablePath: process.env.BROWSER_EXECUTABLE || '/usr/bin/google-chrome', args: ['--no-sandbox']});
const results = [];
try {
  for (const [lang, title] of [['en', 'Available RAM'], ['ru', 'Доступная RAM']]) {
    const page = await browser.newPage({viewport: {width: 1440, height: 1000}});
    const frames = [];
    const failures = [];
    page.on('response', async response => {
      const pathname = new URL(response.url()).pathname;
      // Anonymous viewers have no personal favorites; Grafana probes this optional API with 401.
      const anonymousFavorites = response.status()===401 && pathname==='/api/user/stars';
      if (!anonymousFavorites && response.url().startsWith(origin+'/api/') && response.status() >= 400) failures.push('HTTP '+response.status()+' '+pathname);
      if (!response.url().includes('/api/ds/query')) return;
      try {
        const body = await response.json();
        for (const result of Object.values(body.results || {})) {
          if (result.error) failures.push(result.error);
          for (const frame of result.frames || []) frames.push(frame);
        }
      } catch { /* Unrelated cancelled requests during navigation are not measurement evidence. */ }
    });
    const url = new URL('/d/ahwotel-' + lang, origin);
    url.searchParams.set('from', process.env.LAB_FROM || 'now-1h');
    url.searchParams.set('to', process.env.LAB_TO || 'now');
    if (process.env.LAB_DEVICE_ID) url.searchParams.set('var-device', process.env.LAB_DEVICE_ID);
    if (process.env.LAB_SESSION_ID) url.searchParams.set('var-session', process.env.LAB_SESSION_ID);
    await page.goto(url.href, {waitUntil: 'domcontentloaded'});
    const panel = page.getByText(title, {exact: true}).first();
    await panel.waitFor({state: 'visible', timeout: 60000});
    const box = await panel.boundingBox();
    assert(box && box.width > 40 && box.height > 10, 'Panel title must be visibly laid out');
    const hasMemory = () => frames.some(frame => frame.schema?.fields?.some((f, i) => f.type === 'number' && f.name === 'device_memory_available_bytes' && (!process.env.LAB_DEVICE_ID || f.labels?.device_id === process.env.LAB_DEVICE_ID) && (!process.env.LAB_SESSION_ID || f.labels?.monitoring_session_id === process.env.LAB_SESSION_ID) && frame.data?.values?.[i]?.some(v => typeof v === 'number')));
    const deadline = Date.now() + 30000;
    while (Date.now() < deadline && !hasMemory()) {
      await page.waitForTimeout(500);
    }
    assert(hasMemory(), 'Grafana must receive RAM measurements for the selected device and session');
    await page.waitForTimeout(2000);
    assert.equal(failures.length, 0, failures.join('\n'));
    assert.equal(await page.getByText('match[] must contain at least one non-empty matcher').count(),0,'Template variable queries must work for All');
    await page.screenshot({path: path.join(out, 'grafana-' + lang + '.png')});
    // Walk the full dashboard so lazy panels also execute their PromQL.
    await page.mouse.move(1000, 650);
    for (let step = 0; step < 12; step++) {
      await page.mouse.wheel(0, 800);
      await page.waitForTimeout(500);
    }
    const pipeline = page.getByText(lang === 'en' ? 'Collector / Prometheus readiness' : 'Доступность Collector / Prometheus', {exact: true}).first();
    await pipeline.waitFor({state: 'visible', timeout: 15000});
    await pipeline.scrollIntoViewIfNeeded();
    await page.waitForTimeout(1500);
    assert.equal(failures.length, 0, failures.join('\n'));
    await page.screenshot({path: path.join(out, 'grafana-' + lang + '-pipeline.png')});
    if (process.env.LAB_REQUIRE_SLOW === '1') {
      for (const [section, heading] of [['battery', lang === 'en' ? 'Battery level' : 'Заряд батареи'],
                                       ['self', lang === 'en' ? 'Agent PSS' : 'Память PSS агента'],
                                       ['availability', lang === 'en' ? 'Battery / Self: last observation and status' : 'Battery / Self: последнее наблюдение и статус']]) {
        const headingNode = page.getByText(heading, {exact: true}).first();
        await headingNode.scrollIntoViewIfNeeded();
        const headingBox = await headingNode.boundingBox();
        assert(headingBox && headingBox.width > 40 && headingBox.height > 10);
        await page.mouse.wheel(0, headingBox.y - 200);
        await page.waitForTimeout(1500);
        await page.screenshot({path: path.join(out, 'grafana-' + lang + '-' + section + '.png')});
        if (section === 'availability') {
          await page.getByText(lang === 'en' ? 'Observed at' : 'Время наблюдения', {exact: true}).first().waitFor({state: 'visible'});
          assert.equal(await page.getByText('NaN', {exact: true}).count(), 0, 'Text status/metric columns must not use date formatting');
          await page.getByText(/^(agent|device)\.[a-z_]+/).first().waitFor({state: 'visible'});
        }
      }
      for (const metric of ['device_battery_level_percent', 'device_battery_temperature_celsius', 'agent_memory_pss_bytes']) {
        assert(frames.some(frame => frame.schema?.fields?.some((field, i) => field.name === metric &&
          (!process.env.LAB_DEVICE_ID || field.labels?.device_id === process.env.LAB_DEVICE_ID) &&
          (!process.env.LAB_SESSION_ID || field.labels?.monitoring_session_id === process.env.LAB_SESSION_ID) &&
          frame.data?.values?.[i]?.some(v => typeof v === 'number'))), 'Missing slow metric in Grafana: ' + metric);
      }
      assert.equal(failures.length, 0, failures.join('\n'));
    }
    await fs.writeFile(path.join(out, 'grafana-' + lang + '-frames.json'), JSON.stringify(frames, null, 2));
    results.push({language: lang, url: page.url(), visiblePanel: title, bounds: box, dataFrames: frames.length,
      slowMetricsVerified: process.env.LAB_REQUIRE_SLOW === '1', queryErrors: failures});
    await page.close();
  }
  await fs.writeFile(path.join(out, 'grafana-browser.json'), JSON.stringify({passed: true, results}, null, 2));
  console.log('PASS: EN/RU anonymous dashboards, visible panels and numeric measurement frames');
} finally { await browser.close(); }
