import fs from 'node:fs/promises';
import path from 'node:path';
import {pathToFileURL} from 'node:url';
import assert from 'node:assert/strict';

const modulePath=process.env.PLAYWRIGHT_MODULE || 'playwright';
const {chromium}=await import(modulePath.startsWith('/')?pathToFileURL(modulePath).href:modulePath);
const origin=process.env.GRAFANA_URL;
assert(origin && /^http:\/\/[A-Za-z0-9.-]+:\d+$/.test(origin),'Set GRAFANA_URL');
const out=path.resolve(process.env.LAB_EVIDENCE_DIR || 'artifacts/code13/help');
await fs.mkdir(out,{recursive:true});
const browser=await chromium.launch({headless:true,executablePath:process.env.BROWSER_EXECUTABLE || '/usr/bin/google-chrome',args:['--no-sandbox']});
const results=[];
try {
  for (const lang of ['en','ru']) for (const [width,height] of [[1440,1000],[390,844]]) {
    const page=await browser.newPage({viewport:{width,height}});
    const failures=[];
    page.on('response',async response=>{
      if(!response.url().includes('/api/ds/query'))return;
      try {for(const result of Object.values((await response.json()).results || {}))if(result.error)failures.push(result.error);} catch {}
    });
    const helpBoard=JSON.parse(await fs.readFile('deploy/otel-lab/grafana/dashboards/ahwotel-help-'+lang+'.json','utf8'));
    for (const title of (lang==='ru'?['CPU системы, если доступен','Тепловой статус']:['System CPU, when supported','Thermal status'])) {
      await page.goto(origin+'/d/ahwotel-'+lang,{waitUntil:'domcontentloaded'});
      await page.getByText(lang==='ru'?'Доступная RAM':'Available RAM',{exact:true}).first().waitFor({timeout:60000});
      const heading=page.getByText(title,{exact:true}).first();
      await page.mouse.move(width/2,height*0.7);
      for(let i=0;i<15 && !await heading.count();i++){await page.mouse.wheel(0,400);await page.waitForTimeout(250);}
      await heading.scrollIntoViewIfNeeded();
      const header=heading.locator('xpath=../..');
      const disclosure=header.locator('span[tabindex="0"]').first();
      await disclosure.focus();
      await disclosure.hover();
      const tooltip=page.getByRole('tooltip');
      await tooltip.waitFor({state:'visible'});
      const bounds=await tooltip.boundingBox();
      assert(bounds && bounds.x>=-1 && bounds.y>=-1 && bounds.x+bounds.width<=width+1 && bounds.y+bounds.height<=height+1,'Tooltip must fit viewport: '+JSON.stringify(bounds));
      await page.screenshot({path:path.join(out,`${lang}-${width}-${title.includes('CPU')?'cpu':'thermal'}-tooltip.png`)});
      await page.keyboard.press('Escape');
      await tooltip.waitFor({state:'hidden'});
      const link=header.getByTitle(lang==='ru'?'Подробнее':'Read more');
      await link.focus();
      await page.keyboard.press('Enter');
      await page.waitForURL('**/d/ahwotel-help-'+lang+'**');
      const source=page.getByRole('heading',{name:lang==='ru'?'Источник':'Source',exact:true});
      await source.waitFor({state:'attached',timeout:30000});
      assert.equal(await page.locator('h3').count(),8,'Only the selected metric has eight help sections');
      const full=helpBoard.panels.find(p=>p.title===title).options.content;
      const last=page.getByText(full.split('\n\n').at(-1),{exact:true});
      await last.scrollIntoViewIfNeeded();
      const endBounds=await last.boundingBox();
      assert(endBounds && endBounds.width>100 && endBounds.height>10 && endBounds.y>=0 && endBounds.y+endBounds.height<=height+1,'Full help must be readable by scrolling');
      assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false,'No horizontal page overflow');
      await page.screenshot({path:path.join(out,`${lang}-${width}-${title.includes('CPU')?'cpu':'thermal'}-full.png`)});
      assert.deepEqual(failures,[]);
      results.push({lang,width,title,tooltip:bounds,lastSection:endBounds,sections:8,keyboardLink:true});
    }
    await page.close();
  }
  await fs.writeFile(path.join(out,'result.json'),JSON.stringify({passed:true,results},null,2));
  console.log('PASS: exact CPU/Thermal help, EN/RU, desktop/mobile, tooltip bounds and keyboard navigation');
} finally {await browser.close();}
