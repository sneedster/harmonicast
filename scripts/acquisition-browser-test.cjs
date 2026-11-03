// Run with Playwright available on NODE_PATH; CHROMIUM_PATH can select an installed browser.
const {chromium} = require('playwright');
const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const root = path.resolve(__dirname, '..');
const out = process.env.HARMONICAST_TEST_OUTPUT || path.join(root, 'android/app/build/reports/acquisition-browser');
fs.mkdirSync(out, {recursive:true});
let allowed = true, requests = [], queued = [], setupToken = '', setupClosed = false, submitted = null, setupFailure = false;
const recording = {id:'11111111-1111-1111-1111-111111111111',title:'Missing Track',artist:'Example Artist',kind:'recording',album:'Example Album',year:'2001',durationMs:180000,detail:'',artworkKey:'release/11111111-1111-1111-1111-111111111111'};
const server = http.createServer(async (req,res)=>{
  let body='';for await(const chunk of req)body+=chunk;
  const url=new URL(req.url,'http://localhost');
if(url.pathname==='/room-library.js'){res.setHeader('Content-Type','application/javascript');res.end(fs.readFileSync(path.resolve(__dirname,'../android/app/src/main/assets/room/library.js'),'utf8'));return;}
let result={},code=200;
  if(['/setup','/guest','/display'].includes(url.pathname)){
    const kind=url.pathname.slice(1);res.setHeader('Content-Type','text/html');
    res.end(fs.readFileSync(path.join(root,'android/app/src/main/assets',kind,'index.html'),'utf8').replaceAll('__ROOM_CODE__','ABCD'));return;
  }
  if(url.pathname==='/pair'){setupToken='temporary-pairing-token';result={token:setupToken};}
  else if(url.pathname==='/validate'){assert.equal(req.headers.authorization,'Bearer '+setupToken);submitted=JSON.parse(body);if(setupFailure){code=400;result={error:'Check the SoulSync API key'};}else result={message:'Connection tested. Select Save connection on the Android device.'};}
  else if(url.pathname==='/status'){if(setupClosed){code=403;result={error:'Setup closed'};}else result={ready:!!submitted,message:'Save connection on the Android device'};}
  else {
    if(!url.pathname.startsWith("/v1/")){res.writeHead(404);res.end();return;}
    assert.equal(req.headers.authorization,'Bearer room-token');
    if(url.pathname==='/v1/library/search')result={kind:'tracks',items:url.searchParams.get('q')==='Example Artist'?[{id:'local',title:'Local song',artist:'Example Artist'}]:[]};
    else if(url.pathname==='/v1/queue')result=queued;
    else if(url.pathname==='/v1/now-playing')result={song:null,isPlaying:false,position:0};
    else if(url.pathname==='/v1/status')result={acquisitionAllowed:allowed,acquisitionAvailable:true};
    else if(url.pathname==='/v1/acquisition/entry')result={available:allowed,artist:url.searchParams.get('q')==='Example Artist'};
    else if(url.pathname==='/v1/acquisition/artwork')result={image:'data:image/png;base64,'+fs.readFileSync(path.join(root,'public/harmonicast-icon.png')).toString('base64')};
    else if(url.pathname==='/v1/acquisition/catalog'){
      const mode=url.searchParams.get('mode');
      result={items:mode==='artist'?[{id:'artist-id',title:'Example Artist',artist:'Example Artist',kind:'artist'}]:mode==='albums'?[{id:'album-id',title:'Example Album',artist:'Example Artist',kind:'album',year:'2001',artworkKey:'release-group/11111111-1111-1111-1111-111111111111'}]:[recording],more:false,offset:0};
    } else if(url.pathname==='/v1/acquisition/requests'&&req.method==='POST'){
      assert.equal(JSON.parse(body).recordingId,recording.id);
      if(!allowed){code=403;result={error:'Music acquisition is disabled in this room'};}
      else{await new Promise(resolve=>setTimeout(resolve,13000));result={id:'request1',recording,status:'acquiring',message:'Acquiring track…'};requests=[result];}
    } else if(url.pathname==='/v1/acquisition/requests')result={items:requests};
    else{code=404;result={error:'Not found'};}
  }
  res.writeHead(code,{'Content-Type':'application/json','Cache-Control':'no-store'});res.end(JSON.stringify(result));
});
(async()=>{
 await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
 const base='http://127.0.0.1:'+server.address().port;
 const browser=await chromium.launch({headless:true,executablePath:process.env.CHROMIUM_PATH||undefined,args:['--no-sandbox']});
 const errors=[];
 try{
  const page=await browser.newPage({viewport:{width:1100,height:900}});page.on('pageerror',e=>errors.push(e.message));
  await page.goto(base+'/setup');await page.getByLabel('Pairing code').fill('123456');await page.getByRole('button',{name:'Pair',exact:true}).click();
  await page.getByLabel('MusicGrabber URL').fill('https://musicgrabber.example');await page.getByLabel('Username',{exact:true}).fill('listener');await page.getByLabel('Password',{exact:true}).fill('test-password');
  assert.equal(await page.getByLabel('API key',{exact:true}).isVisible(),false);
  await page.screenshot({path:path.join(out,'computer-setup.png'),fullPage:true});
  await page.getByRole('button',{name:'Test connection'}).click();await page.getByText('Connection tested. Select Save connection on the Android device.').waitFor();
  assert.equal(submitted.username,'listener');assert.equal(submitted.apiKeyMode,false);assert.equal(submitted.remember,true);
  assert.equal(await page.getByLabel('Password',{exact:true}).inputValue(),'');
  assert.equal(await page.evaluate(()=>localStorage.length+sessionStorage.length),0);
  setupClosed=true;await page.getByText('Setup closed.',{exact:false}).waitFor();await page.close();
  setupClosed=false;submitted=null;
  const soul=await browser.newPage({viewport:{width:390,height:844}});soul.on('pageerror',e=>errors.push(e.message));
  await soul.goto(base+'/setup');await soul.getByLabel('Pairing code').fill('123456');await soul.getByRole('button',{name:'Pair',exact:true}).click();
  await soul.getByRole('radio',{name:'SoulSync',exact:true}).focus();await soul.keyboard.press('Space');
  await soul.getByLabel('SoulSync URL').fill('https://soulsync.example');
  assert.equal(await soul.getByLabel('Username',{exact:true}).isVisible(),false);
  await soul.getByRole('button',{name:'Test connection'}).click();await soul.getByText('Enter a SoulSync API key with download access.').waitFor();
  const key=soul.getByLabel('API key',{exact:true});await key.fill('soul-test-key');assert.equal(await key.getAttribute('type'),'password');
  await soul.getByRole('button',{name:'Show API key',exact:true}).click();assert.equal(await key.getAttribute('type'),'text');
  await soul.getByRole('button',{name:'Hide API key',exact:true}).click();
  setupFailure=true;await soul.getByRole('button',{name:'Test connection'}).click();await soul.getByText('Check the SoulSync API key',{exact:true}).waitFor();
  assert.equal(await key.inputValue(),'soul-test-key');
  await soul.screenshot({path:path.join(out,'soulsync-setup-error.png'),fullPage:true});
  setupFailure=false;await soul.getByRole('button',{name:'Test connection'}).click();await soul.getByText('Connection tested. Select Save connection on the Android device.').waitFor();
  assert.equal(submitted.providerId,'soulsync');assert.equal(submitted.apiKeyMode,true);assert.equal(submitted.apiKey,'soul-test-key');
  assert.equal(await key.inputValue(),'');assert.equal(await soul.evaluate(()=>localStorage.length+sessionStorage.length),0);
  await soul.screenshot({path:path.join(out,'soulsync-setup-staged.png'),fullPage:true});await soul.close();
  for(const kind of ['guest','display']){
    allowed=true;requests=[];queued=[];
    const p=await browser.newPage({viewport:{width:kind==='guest'?420:1280,height:900}});p.on('pageerror',e=>errors.push(e.message));
    await p.goto(base+'/'+kind+'#cap=room-token');
    if(kind==='display') await p.locator('nav button[data-page=search]').click();
    await p.locator('#search').fill('Missing Track');await p.locator('#search-form button[type=submit]').click();
    await p.getByRole('button',{name:'Search connected music sources'}).click();
    await p.getByRole('button',{name:'Acquire track'}).waitFor();
    await p.locator('.acquisition-panel .catalog-cover img').waitFor({state:'visible'});
    await p.screenshot({path:path.join(out,kind+'-catalog-art.png')});
    await p.screenshot({path:path.join(out,kind+'-acquisition.png'),fullPage:true});
    await p.getByRole('button',{name:'Acquire track'}).click();await p.getByText('Checking your library and requesting track…',{exact:true}).waitFor();await p.getByText('Acquiring track…',{exact:true}).first().waitFor();assert.equal(queued.length,0);
    requests[0].status='fulfilled';requests[0].message='Queued';queued=[{id:'plex-track',title:'Missing Track',artist:'Example Artist'}];
    await p.getByText('Missing Track — Queued',{exact:true}).waitFor();
    await p.getByRole('button',{name:'Close',exact:true}).click();
    assert.equal(await p.locator('#search').inputValue(),'Missing Track');
    await p.locator('#search').fill('Example Artist');await p.locator('#search-form button[type=submit]').click();
    await p.getByRole('button',{name:'Find songs by this artist'}).click();
    await p.getByRole('button',{name:'Browse releases'}).click();await p.getByRole('button',{name:'View tracks'}).click();await p.getByRole('button',{name:'Acquire track'}).waitFor();
    allowed=false;await p.getByText('Music acquisition is disabled or unavailable. Accepted requests continue.').waitFor();
    await p.close();
  }
  assert.deepEqual(errors,[]);console.log('PASS: MusicGrabber and SoulSync pairing/login, keyboard service choice, key recovery and clearing, guest and display search, artist browsing, request feedback, and live room disable');
 }finally{await browser.close();server.close();}
})().catch(e=>{console.error(e);server.close();process.exitCode=1;});
