// NODE_PATH must contain Playwright; CHROMIUM_PATH may override Chromium.
const { chromium } = require('playwright');
const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const assets = path.resolve(__dirname, '../android/app/src/main/assets/display');
let failure = 0;
const server = http.createServer(async (req, res) => {
  let body = ''; for await (const chunk of req) body += chunk;
  const url = new URL(req.url, 'http://localhost');
if(url.pathname==='/room-library.js'){res.setHeader('Content-Type','application/javascript');res.end(fs.readFileSync(path.resolve(__dirname,'../android/app/src/main/assets/room/library.js'),'utf8'));return;}

  if (['/display', '/open', '/enter', '/join', '/'].includes(url.pathname)) {
    res.setHeader('Content-Type', 'text/html');
    const entry = ['/open', '/enter'].includes(url.pathname);
    const guest = url.pathname === '/enter';
    let page = fs.readFileSync(path.join(assets, entry ? 'open.html' : ['/join', '/'].includes(url.pathname) ? '../guest/index.html' : 'index.html'), 'utf8').replaceAll('__ROOM_CODE__', 'TEST');
    const values = {KIND:guest?'guest':'display',TITLE:guest?'Join room':'Open room display',LOCATION:guest?'Rooms → Invite guests':'Rooms → Open room display',DESCRIPTION:guest?'four-letter room code':'four-digit display code',LABEL:guest?'Room code':'Display code',TYPE:guest?'text':'password',INPUTMODE:guest?'text':'numeric',BUTTON:guest?'Join room':'Open display'};
    for (const [key, value] of Object.entries(values)) page = page.replaceAll('__ENTRY_' + key + '__', value);
    res.end(page); return;
  }
  let status = 200, result = {};
  if (['/v1/display/open', '/v1/guest/open'].includes(url.pathname)) {
    if (body === (url.pathname === '/v1/guest/open' ? 'TEST' : '1234')) result = {capability:'current-token'};
    else {status = 401; result = {error:'Code does not match. Check the display code on the host.'};}
  } else if (req.headers.authorization !== 'Bearer current-token') {
    status = 401; result = {error:'Room capability is invalid or expired'};
  } else if (failure) {status = failure; result = {error:'Room source is no longer available'};}
  else if (url.pathname === '/v1/queue') result = [];
  else if (url.pathname === '/v1/now-playing') result = {song:null,isPlaying:false,position:0};
  res.writeHead(status, {'Content-Type':'application/json'}); res.end(JSON.stringify(result));
});
(async () => {
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const base = 'http://127.0.0.1:' + server.address().port;
  const browser = await chromium.launch({headless:true, executablePath:process.env.CHROMIUM_PATH || '/usr/bin/chromium', args:['--no-sandbox']});
  const errors = [];
  try {
    const page = await browser.newPage({viewport:{width:390,height:844}});
    page.on('pageerror', e => errors.push(e.message));
    await page.goto(base + '/display#cap=old-token');
    await page.waitForURL('**/open?reason=expired');
    assert.equal(await page.evaluate(() => sessionStorage.getItem('harmonicast-display-cap')), null);
    await page.getByText('Previous room links expire', {exact:false}).waitFor();
    await page.getByLabel('Display code', {exact:true}).fill('0000');
    await page.getByRole('button', {name:'Open display',exact:true}).click();
    await page.getByText('Code does not match.', {exact:false}).waitFor();
    await page.waitForFunction(() => !document.querySelector('#submit').disabled);
    await page.getByLabel('Display code', {exact:true}).fill('1234');
    await page.getByLabel('Display code', {exact:true}).press('Enter');
    await page.locator('#status').filter({hasText:'Connected'}).waitFor();
    await page.reload();
    await page.locator('#status').filter({hasText:'Connected'}).waitFor();
    failure = 403;
    await page.locator('#message').filter({hasText:'Room source is no longer available'}).waitFor();
    assert.equal(new URL(page.url()).pathname, '/display');
    failure = 0;
    await page.locator('#status').filter({hasText:'Connected'}).waitFor();
    await page.context().setOffline(true);
    await page.locator('#status').filter({hasText:'Room unavailable'}).waitFor();
    await page.context().setOffline(false);
    await page.locator('#status').filter({hasText:'Connected'}).waitFor();
    const missing = await browser.newPage();
    await missing.goto(base + '/display'); await missing.waitForURL('**/open?reason=expired');
    const blocked = await browser.newPage();
    blocked.on('pageerror', e => errors.push(e.message));
    await blocked.addInitScript(() => { Object.defineProperty(window, 'sessionStorage', {get() {throw new Error('Storage blocked');}}); });
    await blocked.goto(base + '/display#cap=current-token');
    await blocked.locator('#status').filter({hasText:'Connected'}).waitFor();
    const guest = await browser.newPage({viewport:{width:390,height:844}});
    guest.on('pageerror', e => errors.push(e.message));
    await guest.goto(base + '/join#cap=old-token');
    await guest.waitForURL('**/enter?reason=expired');
    await guest.getByLabel('Room code', {exact:true}).fill('nope');
    await guest.getByRole('button', {name:'Join room',exact:true}).click();
    await guest.getByText('Code does not match.', {exact:false}).waitFor();
    assert.equal(await guest.evaluate(() => sessionStorage.getItem('harmonicast-room-cap')), null);
    await guest.waitForFunction(() => !document.querySelector('#submit').disabled);
    await guest.getByLabel('Room code', {exact:true}).fill('test');
    await guest.getByLabel('Room code', {exact:true}).press('Enter');
    await guest.locator('#status').filter({hasText:'Connected'}).waitFor();
    await guest.reload();
    await guest.locator('#status').filter({hasText:'Connected'}).waitFor();
    await guest.evaluate(() => sessionStorage.clear());
    await guest.reload();
    await guest.waitForURL('**/enter?reason=expired');
    await guest.goto(base);
    await guest.waitForURL('**/enter?reason=expired');
    const out = path.resolve(__dirname, '../android/app/build/reports/room-entry');
    fs.mkdirSync(out, {recursive:true});
    await guest.screenshot({path:path.join(out, 'guest-entry.png'), fullPage:true});
    assert.equal(await guest.locator('#visibility').isVisible(), false);
    await blocked.goto(base + '/join#cap=current-token');
    await blocked.locator('#status').filter({hasText:'Connected'}).waitFor();
    assert.deepEqual(errors, []);
    console.log('PASS: four-letter guest entry at base URL, guest-only API authorization covered by network tests, guest and display stale/missing token, code entry, keyboard, reload, source failure, offline recovery, blocked storage.');
  } finally {await browser.close(); server.close();}
})().catch(e => {console.error(e); server.close(); process.exitCode = 1;});
