import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { once } from 'node:events';
import { spawn } from 'node:child_process';
import { createServer } from 'node:net';
import { readHttpConfig,EXCEL_LIMIT,CONTRACT_LIMIT } from '../http-config.mjs';
import { createHttpServer } from '../server.mjs';

const token='a'.repeat(40),headers={Authorization:'Bearer '+token};
const config=(overrides={})=>({...readHttpConfig({}).config,globalLimit:500,ipLimit:100,uidLimit:100,...overrides});
const fake=(overrides={})=>({verifyIdToken:async()=>({uid:'ADMIN',role:'ADMIN',admin:true}),getUser:async()=>({disabled:false,customClaims:{role:'ADMIN',admin:true}}),readProfile:async()=>({role:'ADMIN',estado:'ACTIVO'}),...overrides});
async function running(t,options={}){
 const logs=[],server=createHttpServer({config:config(options.config),firebase:options.firebase===null?null:options.firebase??fake(),logger:e=>logs.push(e)});
 server.listen(0,'127.0.0.1');await once(server,'listening');
 t.after(async()=>{server.closeAllConnections();await new Promise(resolve=>server.close(resolve));});
 return {url:`http://127.0.0.1:${server.address().port}`,server,logs};
}
async function upload(options={}){
 const base=new URL('../../../docs/importacion-paso4b-final/fixtures/',import.meta.url);
 const contract=JSON.parse(await readFile(new URL('valid.json',base))),excel=await readFile(new URL('valid.xlsx',base));
 if(options.uid)contract.requester.uid=options.uid;
 if(options.badHash)contract.plan.fileSha256='0'.repeat(64);
 const data=new FormData();data.append('contract',new Blob([JSON.stringify(contract)],{type:'application/json'}),'contract.json');
 if(options.extra)data.append('other','unexpected');
 data.append('excel',new Blob([options.bytes??excel],{type:'application/octet-stream'}),'source.xlsx');return data;
}

test('HTTP configuration rejects write activation, emulator injection, partial credentials and invalid limits',()=>{
 for(const env of [{INVENTORY_WRITES_ENABLED:'true'},{FIRESTORE_EMULATOR_HOST:''},{FIREBASE_AUTH_EMULATOR_HOST:'127.0.0.1:9098'},
  {FIREBASE_PROJECT_ID:'empresa-clisa'},{FIREBASE_SERVICE_ACCOUNT_JSON:'secret-malformed-value'},{PORT:'-1'},{TRUST_PROXY_HOPS:'2'},
  {MAX_EXCEL_BYTES:String(EXCEL_LIMIT+1)},{RENDER:'true',REQUIRE_HTTPS:'false'}]){
  assert.throws(()=>readHttpConfig(env),e=>e.message==='INVALID_CONFIGURATION'&&!e.message.includes('secret'));
 }
 assert.equal(readHttpConfig({}).account,null);assert.equal(readHttpConfig({RENDER:'true'}).config.requireHttps,true);
});
test('HTTP health is public, lightweight and does not reveal Firebase configuration or framework',async t=>{
 const {url}=await running(t,{firebase:null});const r=await fetch(url+'/health');assert.equal(r.status,200);assert.deepEqual(await r.json(),{status:'ok'});
 assert.equal(r.headers.get('x-powered-by'),null);assert.equal(r.headers.get('cache-control'),'no-store');assert.equal(r.headers.get('x-content-type-options'),'nosniff');
});
test('HTTP status rejects absent token, wrong bearer syntax and query token',async t=>{
 const {url}=await running(t);for(const h of [{},{Authorization:'Basic '+token}])assert.equal((await fetch(url+'/v1/admin/status',{headers:h})).status,401);
 assert.equal((await fetch(url+'/v1/admin/status?token='+token)).status,401);
});
for(const code of ['auth/invalid-id-token','auth/id-token-expired','auth/id-token-revoked'])test(`HTTP ${code} is rejected without leaking SDK errors`,async t=>{
 const {url}=await running(t,{firebase:fake({verifyIdToken:async()=>{throw Object.assign(Error('PRIVATE SECRET diagnostic'),{code});}})});
 const r=await fetch(url+'/v1/admin/status',{headers});assert.equal(r.status,401);assert.ok(!(await r.text()).includes('PRIVATE'));
});
for(const mode of ['USER','blocked','disabled','stale-role','missing-profile','incomplete-claims'])test(`HTTP ${mode} cannot use administrative endpoints`,async t=>{
 const overrides={};
 if(mode==='USER')overrides.verifyIdToken=async()=>({uid:'USER',role:'USER'});
 if(mode==='blocked')overrides.readProfile=async()=>({role:'ADMIN',estado:'BLOQUEADO'});
 if(mode==='disabled')overrides.getUser=async()=>({disabled:true,customClaims:{role:'ADMIN',admin:true}});
 if(mode==='stale-role')overrides.getUser=async()=>({disabled:false,customClaims:{role:'USER'}});
 if(mode==='missing-profile')overrides.readProfile=async()=>null;
 if(mode==='incomplete-claims')overrides.verifyIdToken=async()=>({uid:'ADMIN',role:'ADMIN'});
 const {url}=await running(t,{firebase:fake(overrides)});assert.equal((await fetch(url+'/v1/admin/status',{headers})).status,403);
});
for(const role of ['ADMIN','SUPER_ADMIN'])test(`HTTP active ${role} sees only safe capabilities`,async t=>{
 const claims={role,admin:true,superAdmin:role==='SUPER_ADMIN'};
 const {url}=await running(t,{firebase:fake({verifyIdToken:async()=>({uid:role,...claims}),getUser:async()=>({disabled:false,customClaims:claims}),readProfile:async()=>({role,estado:'ACTIVO'})})});
 const r=await fetch(url+'/v1/admin/status',{headers});assert.equal(r.status,200);assert.equal((await r.json()).inventoryWritesEnabled,false);
});
test('HTTP execution, recovery, stock and classification writes have no routes',async t=>{
 const {url}=await running(t);for(const path of ['/v1/admin/imports/execute','/v1/admin/imports/recover','/v1/admin/classification/apply','/v1/admin/stock']){
  assert.equal((await fetch(url+path,{method:'POST',headers})).status,404);
  assert.equal((await fetch(url+path,{method:'POST'})).status,401);
 }
});
test('HTTP unconfigured Firebase fails closed while health remains available',async t=>{
 const {url}=await running(t,{firebase:null});assert.equal((await fetch(url+'/v1/admin/status',{headers})).status,503);assert.equal((await fetch(url+'/health')).status,200);
});
test('HTTP Firebase backend failure is generic and logs never contain credentials or URLs',async t=>{
 const {url,logs}=await running(t,{firebase:fake({readProfile:async()=>{throw Error('PRIVATE SECRET credentials.json');}})});
 const r=await fetch(url+'/v1/admin/status?password=PRIVATE',{headers});assert.equal(r.status,503);assert.ok(!(await r.text()).includes('PRIVATE'));
 assert.ok(!JSON.stringify(logs).includes(token)&&!JSON.stringify(logs).includes('PRIVATE'));
});
test('HTTP IP/global rate limits apply before expensive Firebase reads and ignore untrusted forwarded IP',async t=>{
 let reads=0;const {url}=await running(t,{config:{ipLimit:2},firebase:fake({verifyIdToken:async()=>{reads++;return {uid:'ADMIN',role:'ADMIN',admin:true};}})});
 for(let i=1;i<=3;i++){const r=await fetch(url+'/v1/admin/status',{headers:{...headers,'X-Forwarded-For':`192.0.2.${i}`}});assert.equal(r.status,i<=2?200:429);}
 assert.equal(reads,2);assert.equal((await fetch(url+'/health')).status,200);
});
test('HTTP UID rate limit cannot be bypassed using multiple source IPs',async t=>{
 let reads=0;const {url}=await running(t,{config:{trustProxyHops:1,uidLimit:1},firebase:fake({readProfile:async()=>{reads++;return {role:'ADMIN',estado:'ACTIVO'};}})});
 assert.equal((await fetch(url+'/v1/admin/status',{headers:{...headers,'X-Forwarded-For':'192.0.2.1'}})).status,200);
 assert.equal((await fetch(url+'/v1/admin/status',{headers:{...headers,'X-Forwarded-For':'192.0.2.2'}})).status,429);
 assert.equal(reads,1);
});
test('HTTP requires HTTPS behind the configured proxy and does not trust forwarded protocol without a proxy',async t=>{
 const {url}=await running(t,{config:{requireHttps:true}});
 assert.equal((await fetch(url+'/v1/admin/status',{headers:{...headers,'X-Forwarded-Proto':'https'}})).status,400);
 const proxied=await running(t,{config:{requireHttps:true,trustProxyHops:1}});
 assert.equal((await fetch(proxied.url+'/v1/admin/status',{headers:{...headers,'X-Forwarded-Proto':'https'}})).status,200);
});
test('HTTP authenticates before reading uploaded documents and rejects unsupported content encoding',async t=>{
 const {url}=await running(t);
 assert.equal((await fetch(url+'/v1/admin/imports/prepare',{method:'POST',body:await upload()})).status,401);
 assert.equal((await fetch(url+'/v1/admin/imports/prepare',{method:'POST',headers:{...headers,'Content-Encoding':'gzip'},body:'x'})).status,415);
});
test('HTTP reuses the administrative compiler in a worker without writes or persistent upload',async t=>{
 const {url}=await running(t),r=await fetch(url+'/v1/admin/imports/prepare',{method:'POST',headers,body:await upload()});
 const text=await r.text();assert.equal(r.status,200,text);const result=JSON.parse(text);
 assert.equal(result.mode,'SAFE_PREVIEW');assert.equal(result.authorization,false);assert.equal(result.inventoryWritesEnabled,false);
 assert.equal(result.proposal.plan.inventoryPolicy,'EXCEL_REFERENCE_V1');assert.equal(result.proposal.plan.productionExecutable,false);
 assert.equal(result.proposal.plan.operations.find(o=>o.type==='UPDATE_PRODUCT_STOCK').expected.stock,6);
});
test('HTTP caller cannot prepare a dossier belonging to another authenticated user',async t=>{
 const {url}=await running(t),r=await fetch(url+'/v1/admin/imports/prepare',{method:'POST',headers,body:await upload({uid:'OTHER'})});assert.equal(r.status,403);
});
test('HTTP source hash mismatch, malformed multipart and extra fields are rejected',async t=>{
 const {url}=await running(t);
 assert.equal((await fetch(url+'/v1/admin/imports/prepare',{method:'POST',headers,body:await upload({badHash:true})})).status,422);
 assert.equal((await fetch(url+'/v1/admin/imports/prepare',{method:'POST',headers:{...headers,'Content-Type':'multipart/form-data; boundary=bad'},body:'invalid'})).status,400);
 assert.equal((await fetch(url+'/v1/admin/imports/prepare',{method:'POST',headers,body:await upload({extra:true})})).status,400);
});
test('HTTP maximum Excel/contract upload sizes are enforced while streaming',async t=>{
 const {url}=await running(t,{config:{excelBytes:2048}});
 assert.equal((await fetch(url+'/v1/admin/imports/prepare',{method:'POST',headers,body:await upload({bytes:Buffer.alloc(2049)})})).status,413);
 const limited=await running(t,{config:{contractBytes:100}});
 assert.equal((await fetch(limited.url+'/v1/admin/imports/prepare',{method:'POST',headers,body:await upload()})).status,413);
});
test('HTTP hung authentication is bounded and maintains pending-work concurrency protection',async t=>{
 const {url}=await running(t,{config:{authTimeoutMs:30,maxAuthPending:1},firebase:fake({verifyIdToken:()=>new Promise(()=>{})})});
 assert.equal((await fetch(url+'/v1/admin/status',{headers})).status,504);
 assert.equal((await fetch(url+'/v1/admin/status',{headers})).status,503);
});
test('HTTP CPU-bound preparation has a hard worker timeout without executing inventory',async t=>{
 const {url}=await running(t,{config:{prepareTimeoutMs:1}});
 assert.equal((await fetch(url+'/v1/admin/imports/prepare',{method:'POST',headers,body:await upload()})).status,504);
 assert.equal((await fetch(url+'/v1/admin/status',{headers})).status,200);
});
test('HTTP only one Excel proposal may occupy memory/CPU concurrently',async t=>{
 const {url}=await running(t),first=await upload(),second=await upload();
 const results=await Promise.all([first,second].map(body=>fetch(url+'/v1/admin/imports/prepare',{method:'POST',headers,body})));
 assert.deepEqual(results.map(r=>r.status).sort(),[200,503]);await Promise.all(results.map(r=>r.text()));
});
test('HTTP server uses process PORT, exposes health without secrets and exits when write activation is attempted',async t=>{
 const probe=createServer();probe.listen(0,'127.0.0.1');await once(probe,'listening');const port=probe.address().port;await new Promise(r=>probe.close(r));
 const env={...process.env,PORT:String(port)};
 for(const k of ['FIRESTORE_EMULATOR_HOST','FIREBASE_AUTH_EMULATOR_HOST','FIREBASE_PROJECT_ID','FIREBASE_SERVICE_ACCOUNT_JSON','INVENTORY_WRITES_ENABLED','REQUIRE_HTTPS','RENDER'])delete env[k];
 const child=spawn(process.execPath,['server.mjs'],{cwd:new URL('../',import.meta.url),env,windowsHide:true,stdio:['ignore','pipe','pipe']});
 t.after(()=>child.kill());let output='';child.stdout.on('data',v=>{output+=v;});child.stderr.on('data',v=>{output+=v;});
 await new Promise((resolve,reject)=>{const timer=setTimeout(()=>reject(Error('SERVER_START_TIMEOUT')),5000);const check=()=>{if(output.includes('listening')){clearTimeout(timer);resolve();}else if(child.exitCode!==null){clearTimeout(timer);reject(Error('SERVER_EXITED'));}else setTimeout(check,20);};check();});
 assert.deepEqual(await (await fetch(`http://127.0.0.1:${port}/health`)).json(),{status:'ok'});assert.ok(!output.includes('private_key'));
 child.kill();await once(child,'exit');
 const denied=spawn(process.execPath,['server.mjs'],{cwd:new URL('../',import.meta.url),env:{...env,INVENTORY_WRITES_ENABLED:'true'},windowsHide:true,stdio:'ignore'});
 const [code]=await once(denied,'exit');assert.equal(code,1);
});
