import test,{before,after,beforeEach} from 'node:test';
import assert from 'node:assert/strict';
import { once } from 'node:events';
import { readFile } from 'node:fs/promises';
import { createEmulatorRuntime,HOST,AUTH_HOST,PROJECT } from '../runtime.mjs';
import { createHttpServer } from '../server.mjs';
import { readHttpConfig } from '../http-config.mjs';

let rt,server,url;const tokens={};
before(async()=>{
 rt=createEmulatorRuntime();
 for(const role of ['ADMIN','SUPER_ADMIN','USER']){
  try{await rt.auth.deleteUser('HTTP_'+role);}catch{}
  const uid='HTTP_'+role,email=role.toLowerCase()+'@http.fixture.invalid';
  await rt.auth.createUser({uid,email,password:'LocalFixture123!'});
  await rt.auth.setCustomUserClaims(uid,{role,admin:role!=='USER',superAdmin:role==='SUPER_ADMIN'});
  const r=await fetch(`http://${AUTH_HOST}/identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key=local`,{
   method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({email,password:'LocalFixture123!',returnSecureToken:true})});
  tokens[role]=(await r.json()).idToken;assert.ok(tokens[role]);
 }
 // Test-only adapter: production config explicitly rejects emulator endpoints.
 const firebase={verifyIdToken:t=>rt.auth.verifyIdToken(t,true),getUser:uid=>rt.auth.getUser(uid),readProfile:async uid=>{
  const d=await rt.db.doc('usuarios/'+uid).get();return d.exists?{role:d.get('role'),estado:d.get('estado')}:null;}};
 const config={...readHttpConfig({}).config,globalLimit:600,ipLimit:600,uidLimit:600};
 server=createHttpServer({config,firebase});server.listen(0,'127.0.0.1');await once(server,'listening');url='http://127.0.0.1:'+server.address().port;
});
beforeEach(async()=>{
 await fetch(`http://${HOST}/emulator/v1/projects/${PROJECT}/databases/(default)/documents`,{method:'DELETE'});
 for(const role of ['ADMIN','SUPER_ADMIN','USER']){
  const uid='HTTP_'+role;await rt.auth.updateUser(uid,{disabled:false});await rt.auth.setCustomUserClaims(uid,{role,admin:role!=='USER',superAdmin:role==='SUPER_ADMIN'});
  await rt.db.doc('usuarios/'+uid).set({role,estado:'ACTIVO'});
 }
 await rt.db.doc('productos/api-preserve').set({codigo:'PRESERVE',nombre:'Ficticio',stock:7,cantidadRestante:99});
 await rt.db.doc('existencias/api-preserve').set({productoId:'api-preserve',codigo:'0007',cantidad:7});
 await rt.db.doc('registros/api-preserve').set({tipo:'SALIDA',estadoPago:'Pendiente',estadoFormalizacion:'PENDIENTE'});
});
after(async()=>{if(server){server.closeAllConnections();await new Promise(r=>server.close(r));}await rt?.close();});
const h=role=>({Authorization:'Bearer '+tokens[role]});

for(const role of ['ADMIN','SUPER_ADMIN'])test(`HTTP Emulator valid ${role} token and active profile are authorized`,async()=>{
 const r=await fetch(url+'/v1/admin/status',{headers:h(role)});assert.equal(r.status,200);assert.equal((await r.json()).inventoryWritesEnabled,false);
});
test('HTTP Emulator USER cannot access admin even with a valid Firebase token',async()=>{
 assert.equal((await fetch(url+'/v1/admin/status',{headers:h('USER')})).status,403);
});
test('HTTP Emulator blocked profile loses access immediately without token caching',async()=>{
 assert.equal((await fetch(url+'/v1/admin/status',{headers:h('ADMIN')})).status,200);
 await rt.db.doc('usuarios/HTTP_ADMIN').update({estado:'BLOQUEADO'});
 assert.equal((await fetch(url+'/v1/admin/status',{headers:h('ADMIN')})).status,403);
});
test('HTTP Emulator disabled Firebase Auth account is rejected',async()=>{
 await rt.auth.updateUser('HTTP_ADMIN',{disabled:true});assert.equal((await fetch(url+'/v1/admin/status',{headers:h('ADMIN')})).status,403);
});
test('HTTP Emulator removal of current administrative claims rejects the old elevated token',async()=>{
 await rt.auth.setCustomUserClaims('HTTP_ADMIN',{role:'USER'});assert.equal((await fetch(url+'/v1/admin/status',{headers:h('ADMIN')})).status,403);
});
test('HTTP Emulator missing or inconsistent own profile is rejected',async()=>{
 await rt.db.doc('usuarios/HTTP_ADMIN').delete();assert.equal((await fetch(url+'/v1/admin/status',{headers:h('ADMIN')})).status,403);
 await rt.db.doc('usuarios/HTTP_ADMIN').set({role:'USER',estado:'ACTIVO'});assert.equal((await fetch(url+'/v1/admin/status',{headers:h('ADMIN')})).status,403);
});
test('HTTP Emulator preparation returns a proposal and preserves all inventory/history native versions',async()=>{
 const paths=['productos/api-preserve','existencias/api-preserve','registros/api-preserve'];
 const before=await Promise.all(paths.map(p=>rt.db.doc(p).get()));
 const base=new URL('../../../docs/importacion-paso4b-final/fixtures/',import.meta.url),c=JSON.parse(await readFile(new URL('valid.json',base)));
 c.requester.uid='HTTP_ADMIN';const data=new FormData();data.append('contract',new Blob([JSON.stringify(c)]),'contract.json');data.append('excel',new Blob([await readFile(new URL('valid.xlsx',base))]),'source.xlsx');
 const r=await fetch(url+'/v1/admin/imports/prepare',{method:'POST',headers:h('ADMIN'),body:data});assert.equal(r.status,200,await r.clone().text());
 assert.equal((await r.json()).authorization,false);
 for(let i=0;i<paths.length;i++){const after=await rt.db.doc(paths[i]).get();assert.deepEqual(after.data(),before[i].data());assert.ok(after.updateTime.isEqual(before[i].updateTime));}
 assert.equal((await rt.db.collection('importaciones').get()).size,0);
});
test('HTTP Emulator privileged token cannot call execution or recovery routes',async()=>{
 for(const action of ['execute','start','recover','sync'])assert.equal((await fetch(url+'/v1/admin/imports/'+action,{method:'POST',headers:h('SUPER_ADMIN')})).status,404);
 assert.equal((await rt.db.doc('productos/api-preserve').get()).get('stock'),7);assert.equal((await rt.db.collection('importaciones').get()).size,0);
});
test('HTTP Emulator expired token is rejected',async()=>{
 const p=tokens.ADMIN.split('.'),claims=JSON.parse(Buffer.from(p[1],'base64url'));claims.exp=1;p[1]=Buffer.from(JSON.stringify(claims)).toString('base64url');
 assert.equal((await fetch(url+'/v1/admin/status',{headers:{Authorization:'Bearer '+p.join('.')}})).status,401);
});
test('HTTP Emulator revoked token is rejected with checkRevoked enabled',async()=>{
 await new Promise(r=>setTimeout(r,1100));await rt.auth.revokeRefreshTokens('HTTP_ADMIN');
 assert.equal((await fetch(url+'/v1/admin/status',{headers:h('ADMIN')})).status,401);
});
