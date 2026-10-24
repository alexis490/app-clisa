import test,{before,after,beforeEach} from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { generateKeyPairSync,sign } from 'node:crypto';
import { Timestamp,FieldValue } from 'firebase-admin/firestore';
import { initializeTestEnvironment,assertFails,assertSucceeds } from '@firebase/rules-unit-testing';
import { doc,writeBatch,serverTimestamp,getDoc,setDoc,updateDoc } from 'firebase/firestore';
import { createEmulatorRuntime,assertEmulator,PROJECT,HOST } from '../runtime.mjs';
import { canonical,sha,hash,productId,existenceId } from '../canonical.mjs';
import { baseline,fingerprint,historyHash,stamp } from '../inventory.mjs';
import { InventorySyncEngine } from '../engine.mjs';
import { estimate,validateContract,operationIdentity,planIdentity } from '../contract.mjs';

let now=Date.now();const bytes=Buffer.from('FICTITIOUS LOCAL EXCEL BYTES, NOT A REAL EXPORT');
const {publicKey,privateKey}=generateKeyPairSync('ed25519');
const trust={operator:publicKey};let rt,env;
const engine=(fault=()=>{})=>new InventorySyncEngine(rt,{trust,clock:()=>now,fault});
const ref=path=>rt.db.doc(path);
async function clear(){
 assertEmulator(rt);const r=await fetch(`http://${HOST}/emulator/v1/projects/${PROJECT}/databases/(default)/documents`,{method:'DELETE'});assert.equal(r.status,200);
}
before(async()=>{
 rt=createEmulatorRuntime();
 env=await initializeTestEnvironment({projectId:PROJECT,firestore:{host:'127.0.0.1',port:8086,rules:await readFile(new URL('../../../security/paso4b1/firestore.emulator.rules',import.meta.url),'utf8')}});
 for(const role of ['USER','ADMIN','SUPER_ADMIN']){
  try{await rt.auth.deleteUser(role);}catch{}
  await rt.auth.createUser({uid:role,email:`${role.toLowerCase()}@fixture.invalid`});
  await rt.auth.setCustomUserClaims(role,{role,admin:role!=='USER',superAdmin:role==='SUPER_ADMIN'});
 }
});
after(async()=>{await env?.cleanup();await rt?.close();});
beforeEach(async()=>{
 await clear();
 for(const role of ['USER','ADMIN','SUPER_ADMIN'])await ref(`usuarios/${role}`).set({uid:role,role,estado:'ACTIVO',displayName:role});
 await ref('tiendas/clisa').set({nombre:'CLISA',estado:'ACTIVA'});await ref('tiendas/toqen').set({nombre:'TOQEN',estado:'ACTIVA'});
 await rt.auth.updateUser('ADMIN',{disabled:false});await rt.auth.setCustomUserClaims('ADMIN',{role:'ADMIN',admin:true});
});
async function seed(pid='p1',sku='SKU/1',serial='0001',qty=5){
 await ref(`productos/${pid}`).set({codigo:sku,nombre:'Producto ficticio',stock:qty,estado:'ACTIVO',cantidadRestante:99,categoriaId:'original',marcaId:'original',descripcion:'Conservar',actualizadoEn:new Timestamp(1,0)});
 await ref(`existencias/e-${pid}`).set({productoId:pid,codigoProducto:sku,codigo:serial,cantidad:qty,estado:qty?'DISPONIBLE':'SIN_STOCK',actualizadoEn:new Timestamp(1,0)});
}
async function movement(state='PENDIENTE',pid='p1'){
 const r={registroId:'mov-'+pid,tipo:'SALIDA',estado:'COMPLETADO',productoId:pid,codigoProducto:'SKU/1',existenciaId:'e-'+pid,codigoExistencia:'0001',nombreProducto:'Producto ficticio',cantidad:1,tiendaOrigenId:'clisa',tiendaOrigenNombre:'CLISA',tiendaDestinoId:'toqen',tiendaDestinoNombre:'TOQEN',registradoPorId:'USER',registradoPorNombre:'USER',registradoPorEmail:'user@fixture.invalid',recibidoPor:'Receptor ficticio',estadoPago:'Pendiente',fechaHora:new Timestamp(2,0)};
 if(state!=='SIN_VERIFICAR')Object.assign(r,{estadoFormalizacion:state,formalizadoEn:state==='COMPLETADO'?new Timestamp(3,0):null,formalizadoPor:state==='COMPLETADO'?'ADMIN':null});
 await ref('registros/'+r.registroId).set(r);
}
function op(type,path,pid,sku,expected,before={},serial=null){
 const [collection,documentId]=path.split('/');
 const value={operationId:hash([type,path,expected]),type,collection,documentId,path,sku,serial,productId:pid,before,expected,reason:'Fixture local',validation:'CANDIDATE',risk:'HIGH',blockers:[],references:[],atomicGroup:`product:${pid}`,observedVersion:type.startsWith('CREATE_')?null:'Timestamp(seconds=1, nanoseconds=0)'};
 return value;
}
async function fixture({fresh=false,two=false,qty=6,proof=true}={}){
 if(!fresh)await seed();if(two)await seed('p2','SKU/2','0002');
 const products=(await rt.db.collection('productos').get()).docs,entries=(await rt.db.collection('existencias').get()).docs;
 const comparison=baseline(products,entries),pid=fresh?productId('SKU/1'):'p1';
 const sources=[{code:'SKU/1',name:'Producto ficticio',reportedQuantity:qty,sheet:'Hoja',row:2,serials:[{code:'0001',quantity:qty,sheet:'Hoja',row:3,valid:true}]}];
 let operations=fresh?[op('CREATE_PRODUCT',`productos/${pid}`,pid,'SKU/1',{codigo:'SKU/1',nombre:'Producto ficticio',stock:qty,estado:'ACTIVO'}),op('CREATE_EXISTENCE',`existencias/${existenceId(pid,'0001')}`,pid,'SKU/1',{productoId:pid,codigoProducto:'SKU/1',codigo:'0001',cantidad:qty,estado:'DISPONIBLE'}, {},'0001')]:[
  op('UPDATE_EXISTENCE','existencias/e-p1','p1','SKU/1',{cantidad:qty,estado:'DISPONIBLE'},{cantidad:5,estado:'DISPONIBLE',productoId:'p1',codigoProducto:'SKU/1',codigo:'0001'},'0001'),op('UPDATE_PRODUCT_STOCK','productos/p1','p1','SKU/1',{stock:qty},{stock:5})];
 if(two){sources.push({code:'SKU/2',name:'Producto ficticio',reportedQuantity:qty,sheet:'Hoja',row:4,serials:[{code:'0002',quantity:qty,sheet:'Hoja',row:5,valid:true}]});operations.push(op('UPDATE_EXISTENCE','existencias/e-p2','p2','SKU/2',{cantidad:qty,estado:'DISPONIBLE'},{cantidad:5},'0002'),op('UPDATE_PRODUCT_STOCK','productos/p2','p2','SKU/2',{stock:qty},{stock:5}));}
 const contract={schemaVersion:1,requester:{uid:'ADMIN'},plan:{schemaVersion:4,inventoryPolicy:'EXCEL_REFERENCE_V1',planId:'dryrun-'+hash(['fixture',operations]),fileSha256:sha(bytes),observedAtMillis:now,baselineFingerprint:fingerprint(comparison),temporal:{declaredMillis:now-60_000,uncertain:false},checks:[],operations},source:{products:sources,errorCount:0,issues:[],completeness:{state:'UNKNOWN',technicalValid:true,manifestDigest:null}},comparison};
 return {contract,bytes,approval:await approve(contract,proof),pid};
}
async function approve(contract,proof=true,changes={}){
 contract.plan.temporalIdentity??=null;
 const resealedIds=new Map();for(const o of contract.plan.operations){const previous=o.operationId;o.operationId=operationIdentity(o);resealedIds.set(previous,o.operationId);}
 if(changes.allowedOperationIds)changes={...changes,allowedOperationIds:changes.allowedOperationIds.map(id=>resealedIds.get(id)??id)};
 contract.plan.planId=planIdentity(contract);
 const payload={contractHash:hash(contract),fileSha256:contract.plan.fileSha256,sourceHash:hash(contract.source),requesterUid:contract.requester.uid,issuedAt:now-1000,expiresAt:now+60_000,evidenceKind:'EMULATOR_FIXTURE_ONLY',allowedOperationIds:contract.plan.operations.filter(o=>!o.blockers.length&&o.validation==='CANDIDATE').map(o=>o.operationId),budget:{reads:50_000,writes:20_000,deletes:20_000,reserveReads:500,reserveWrites:500,measuredAt:now},...changes};
 return {keyId:'operator',payload,signature:sign(null,Buffer.from(canonical(payload)),privateKey).toString('base64')};
}
async function unchangedInventory(){return hash((await rt.db.collection('productos').get()).docs.map(d=>[d.id,d.data().stock]));}

test('4B Excel reference refuses an old plan rather than replaying its deductions',async()=>{
 const f=await fixture();f.contract.plan.schemaVersion=3;f.approval=await approve(f.contract);
 await assert.rejects(engine().start(f),/LEGACY_INVENTORY_POLICY_REQUIRES_NEW_PLAN/);
});

test('4B formalization and payment changes do not alter Excel stock',async()=>{
 await movement('COMPLETADO');const f=await fixture();
 await engine(async point=>{if(point==='beforeUnit')await ref('registros/mov-p1').update({estadoPago:'Pagado'});}).start(f);
 assert.equal((await ref('productos/p1').get()).get('stock'),6);
 assert.equal((await ref('registros/mov-p1').get()).get('estadoPago'),'Pagado');
});

test('4B2B2 changing reconciliation evidence after signature cannot authorize execution',async()=>{
 const f=await fixture();f.approval.payload.contractHash='tampered';
 await assert.rejects(engine().start(f),/TRUSTED_APPROVAL_REQUIRED/);assert.equal((await ref('productos/p1').get()).get('stock'),5);
});
test('4B2B2 unrelated product adjustments do not silently alter the reviewed product scope',async()=>{
 await movement('COMPLETADO','p2');await ref('registros/mov-p2').update({tipo:'AJUSTE_ENTRADA'});const f=await fixture({two:true});
 f.approval=await approve(f.contract,true,{allowPartial:true,allowedOperationIds:f.contract.plan.operations.filter(o=>o.productId==='p1').map(o=>o.operationId)});
 const original=(await ref('registros/mov-p2').get()).data();const r=await engine().start(f);
 assert.equal(r.status,'REQUIRES_REVIEW');assert.equal((await ref('productos/p1').get()).get('stock'),6);
 assert.equal((await ref('productos/p2').get()).get('stock'),5);assert.deepEqual((await ref('registros/mov-p2').get()).data(),original);
});

test('01 creates product + deterministic existence in one unit, with journal/checkpoint',async()=>{
 const f=await fixture({fresh:true});const r=await engine().start({...f,runId:'run_create'});assert.equal(r.status,'COMPLETED');
 assert.equal((await ref('productos/'+f.pid).get()).get('stock'),6);assert.equal((await ref('existencias/'+existenceId(f.pid,'0001')).get()).get('cantidad'),6);
 assert.equal((await rt.db.collection('importaciones/run_create/journal').get()).size,2);assert.equal((await ref('importaciones/_control_clisa').get()).get('locked'),false);
});
test('02 updates individual quantity and stock together, preserves metadata and cantidadRestante',async()=>{
 const f=await fixture();await engine().start(f);const p=(await ref('productos/p1').get()).data();
 assert.equal(p.stock,6);assert.equal((await ref('existencias/e-p1').get()).get('cantidad'),6);
 assert.equal(p.cantidadRestante,99);assert.equal(p.categoriaId,'original');assert.equal(p.marcaId,'original');assert.equal(p.descripcion,'Conservar');
});
test('03 unchanged operations cause zero writes and no lock/job',async()=>{
 const f=await fixture();f.contract.plan.operations=f.contract.plan.operations.map(o=>({...o,type:'SKIP_UNCHANGED',validation:'SKIPPED',expected:o.before}));
 f.approval=await approve(f.contract,true,{allowedOperationIds:f.contract.plan.operations.map(o=>o.operationId)});
 await assert.rejects(engine().start(f),/NO_AUTHORIZED_CHANGES/);assert.equal((await rt.db.collection('importaciones').get()).size,0);
});
for(const kind of ['sku','serial'])test(`04 duplicate ${kind} in source is not fused or selected`,async()=>{
 const f=await fixture();if(kind==='sku')f.contract.source.products.push(structuredClone(f.contract.source.products[0]));else f.contract.source.products[0].serials.push({...f.contract.source.products[0].serials[0]});
 f.approval=await approve(f.contract);await assert.rejects(engine().start(f),/DUPLICATE/);assert.equal((await ref('productos/p1').get()).get('stock'),5);
});
test('05 deterministic IDs distinguish slash, underscore, leading zeros and unicode',()=>{
 assert.notEqual(productId('SFYRS/1000G'),productId('SFYRS_1000G'));assert.notEqual(existenceId('p','0001'),existenceId('p','1'));assert.equal(productId('😀'),productId('😀'));
});
test('06 native document version conflict after preparation blocks whole unit',async()=>{
 const f=await fixture();let once=false;const e=engine(async(point)=>{if(point==='beforeUnit'&&!once){once=true;await ref('productos/p1').update({descripcion:'Cambio concurrente'});}});
 await assert.rejects(e.start({...f,runId:'run_version'}),/DOCUMENT_VERSION_CONFLICT/);assert.equal((await ref('existencias/e-p1').get()).get('cantidad'),5);assert.equal((await ref('importaciones/run_version').get()).get('status'),'FAILED');
});
test('07 old plan blocked before touching importaciones',async()=>{
 const f=await fixture();f.contract.plan.observedAtMillis=now-16*60_000;f.approval=await approve(f.contract);await assert.rejects(engine().start(f),/PLAN_EXPIRED/);assert.equal((await rt.db.collection('importaciones').get()).size,0);
});
test('08 altered plan rejected even if JSON looks valid',async()=>{
 const f=await fixture();f.contract.plan.operations[0].expected.cantidad=100;await assert.rejects(engine().start(f),/PLAN_TAMPERED/);
});
test('09 repeated source cannot run under another ID',async()=>{
 const f=await fixture();await engine().start({...f,runId:'run_once'});await assert.rejects(engine().start({...f,runId:'run_twice'}),/SOURCE_ALREADY_RESERVED/);assert.equal((await ref('productos/p1').get()).get('stock'),6);
});
test('10 simultaneous imports acquire one global lock only',async()=>{
 const f=await fixture();const r=await Promise.allSettled([engine().start({...f,runId:'run_a'}),engine().start({...f,runId:'run_b'})]);
 assert.equal(r.filter(x=>x.status==='fulfilled').length,1);assert.equal((await ref('productos/p1').get()).get('stock'),6);
});
function salida(client,id='new-salida'){
 const b=writeBatch(client);b.set(doc(client,'registros',id),{registroId:id,tipo:'SALIDA',estado:'COMPLETADO',productoId:'p1',codigoProducto:'SKU/1',nombreProducto:'Producto ficticio',existenciaId:'e-p1',codigoExistencia:'0001',cantidad:1,tiendaOrigenId:'clisa',tiendaOrigenNombre:'CLISA',tiendaDestinoId:'toqen',tiendaDestinoNombre:'TOQEN',registradoPorId:'USER',registradoPorNombre:'USER',registradoPorEmail:'user@fixture.invalid',recibidoPor:'Receptor ficticio',estadoPago:'Pendiente',fechaHora:serverTimestamp(),estadoFormalizacion:'PENDIENTE',formalizadoEn:null,formalizadoPor:null});
 b.update(doc(client,'productos','p1'),{stock:4,ultimaSalidaId:id,actualizadoEn:serverTimestamp()});b.update(doc(client,'existencias','e-p1'),{cantidad:4,estado:'DISPONIBLE',ultimaSalidaId:id,actualizadoEn:serverTimestamp()});return b.commit();
}
test('11 real client SALIDA succeeds unlocked; same atomic operation denied during import',async()=>{
 await seed();const client=env.authenticatedContext('USER',{role:'USER',email:'user@fixture.invalid'}).firestore();
 await assertSucceeds(salida(client,'before-lock'));now=Date.now()+2000;await seed();const f=await fixture();let checked=false;
 await engine(async point=>{if(point==='beforeUnit'){await assertFails(salida(client));checked=true;}}).start(f);
 assert.equal(checked,true);assert.equal((await ref('registros/new-salida').get()).exists,false);
});
for(const formal of ['PENDIENTE','COMPLETADO','SIN_VERIFICAR'])test(`12 ${formal} is never deducted again and needs no inclusion proof`,async()=>{
 await movement(formal);const f=await fixture({proof:false});const saved=(await ref('registros/mov-p1').get()).data();
 await engine().start(f);assert.equal((await ref('productos/p1').get()).get('stock'),6);
 assert.equal((await ref('existencias/e-p1').get()).get('cantidad'),6);assert.deepEqual((await ref('registros/mov-p1').get()).data(),saved);
});

test('13 no export time or transfer certificate is required',async()=>{
 const f=await fixture({proof:false});f.contract.plan.temporal=null;f.approval=await approve(f.contract,false);
 await engine().start(f);assert.equal((await ref('productos/p1').get()).get('stock'),6);
});

test('14 fault inside transaction commits neither inventory nor journal',async()=>{
 const f=await fixture();await assert.rejects(engine(point=>{if(point==='insideUnit')throw Error('INJECTED');}).start({...f,runId:'run_fault'}),/INJECTED/);
 assert.equal((await ref('productos/p1').get()).get('stock'),5);assert.equal((await rt.db.collection('importaciones/run_fault/journal').get()).size,0);assert.equal((await ref('importaciones/_control_clisa').get()).get('locked'),true);
});
test('15 supervised recovery resumes partial commits without reapplying acknowledged or lost-response unit',async()=>{
 const f=await fixture({two:true});let lost=false;
 await assert.rejects(engine(point=>{if(point==='afterUnit'&&!lost){lost=true;throw Error('LOST_RESPONSE');}}).start({...f,runId:'run_partial'}),/LOST_RESPONSE/);
 assert.equal((await ref('importaciones/run_partial').get()).get('status'),'PARTIAL');const before=(await ref('productos/p1').get()).updateTime;
 await assert.rejects(engine().recover({...f,runId:'run_partial'}),/SUPERVISION_REQUIRED/);
 const r=await engine().recover({...f,runId:'run_partial',supervised:true});assert.equal(r.status,'COMPLETED');
 assert.ok((await ref('productos/p1').get()).updateTime.isEqual(before));assert.equal((await ref('productos/p2').get()).get('stock'),6);
 assert.equal((await rt.db.collection('importaciones/run_partial/journal').get()).size,4);
});
test('16 insufficient quota budget rejects before job creation',async()=>{
 const f=await fixture();f.approval=await approve(f.contract,true,{budget:{reads:2,writes:1,deletes:0,reserveReads:0,reserveWrites:0,measuredAt:now}});await assert.rejects(engine().start(f),/QUOTA_INSUFFICIENT/);assert.equal((await rt.db.collection('importaciones').get()).size,0);
});
test('17 deletions remain disabled despite trusted approval and complete claim',async()=>{
 const f=await fixture();const o=f.contract.plan.operations[0];o.type='DELETE_EXISTENCE';o.expected={};f.contract.source.completeness.state='COMPLETE_VERIFIED';f.approval=await approve(f.contract);await assert.rejects(engine().start(f),/DELETE_DISABLED/);assert.equal((await ref('existencias/e-p1').get()).exists,true);
});
for(const mode of ['USER','blocked','claims','disabled'])test(`18 unauthorized ${mode} cannot execute`,async()=>{
 const f=await fixture();if(mode==='USER')f.contract.requester.uid='USER';if(mode==='blocked')await ref('usuarios/ADMIN').update({estado:'BLOQUEADO'});if(mode==='claims')await rt.auth.setCustomUserClaims('ADMIN',{role:'ADMIN'});if(mode==='disabled')await rt.auth.updateUser('ADMIN',{disabled:true});
 f.approval=await approve(f.contract);await assert.rejects(engine().start(f),/UNAUTHORIZED/);assert.equal((await ref('productos/p1').get()).get('stock'),5);
});
test('19 history, payment and formalization are byte-for-byte preserved on successful fixture reconciliation',async()=>{
 await movement('COMPLETADO');const f=await fixture();const before=(await ref('registros/mov-p1').get()).data();await engine().start(f);assert.deepEqual((await ref('registros/mov-p1').get()).data(),before);
});
test('20 database duplicate SKU blocks, never takes first',async()=>{
 const f=await fixture();await ref('productos/duplicate').set((await ref('productos/p1').get()).data());await assert.rejects(engine().start(f),/SCAN_BUDGET_EXCEEDED|AMBIGUOUS_DATABASE_SKU|BASELINE_CHANGED/);
});
test('21 database serial linked to another product blocks',async()=>{
 const f=await fixture();await ref('existencias/e-p1').update({productoId:'wrong'});await assert.rejects(engine().start(f),/INCONSISTENT_DATABASE_EXISTENCE/);
});
test('22 valid signature cannot authorize a different quantity than normalized source',async()=>{
 const f=await fixture();f.contract.plan.operations[0].expected.cantidad=90;f.approval=await approve(f.contract);await assert.rejects(engine().start(f),/PROPOSED_OPERATIONS_DO_NOT_MATCH_SOURCE/);
});
test('23 too many series/writes block entire product without splitting',async()=>{
 const f=await fixture({fresh:true});const p=f.contract.source.products[0];p.serials=Array.from({length:200},(_,i)=>({code:'s'+i,quantity:1,valid:true}));p.reportedQuantity=200;
 const pid=f.pid;f.contract.plan.operations=[op('CREATE_PRODUCT','productos/'+pid,pid,p.code,{codigo:p.code,nombre:p.name,stock:200,estado:'ACTIVO'}),...p.serials.map(s=>op('CREATE_EXISTENCE','existencias/'+existenceId(pid,s.code),pid,p.code,{productoId:pid,codigoProducto:p.code,codigo:s.code,cantidad:1,estado:'DISPONIBLE'}, {},s.code))];f.approval=await approve(f.contract);
 await assert.rejects(engine().start(f),/PRODUCT_TOO_MANY_WRITES/);assert.equal((await rt.db.collection('productos').get()).size,0);
});
test('24 negative source quantity is never converted to zero',async()=>{
 const f=await fixture();f.contract.source.products[0].serials[0].quantity=-1;f.approval=await approve(f.contract);await assert.rejects(engine().start(f),/INVALID_SOURCE_QUANTITY/);
});
test('25 unknown quota balance and forged approval fail closed',async()=>{
 const f=await fixture();f.approval.signature='invalid';await assert.rejects(engine().start(f),/TRUSTED_APPROVAL_REQUIRED/);
 f.approval=await approve(f.contract,true,{budget:null});await assert.rejects(engine().start(f),/QUOTA_BALANCE_UNKNOWN/);
});
test('26 revoked profile before a later unit leaves PARTIAL and lock retained',async()=>{
 const f=await fixture({two:true});await assert.rejects(engine(async(point,pid)=>{if(point==='afterUnit'&&pid==='p1')await ref('usuarios/ADMIN').update({estado:'BLOQUEADO'});}).start({...f,runId:'run_revoke'}),/UNAUTHORIZED_PROFILE/);
 assert.equal((await ref('productos/p2').get()).get('stock'),5);assert.equal((await ref('importaciones/run_revoke').get()).get('status'),'PARTIAL');assert.equal((await ref('importaciones/_control_clisa').get()).get('locked'),true);
});
test('27 source with existing 567 issues cannot be authorized by a JSON declaration',async()=>{
 const f=await fixture();f.contract.source.errorCount=567;f.contract.source.issues=[{kind:'MISSING_SKU'}];f.contract.source.completeness.state='INCOMPLETE';f.approval=await approve(f.contract);await assert.rejects(engine().start(f),/TRUNCATED_SOURCE_ISSUES/);
});
test('28 source product missing from Excel is not deleted or zeroed',async()=>{
 await seed('p-absent','ABSENT','absent-series',3);
 const f=await fixture();const absent={...op('REVIEW_MISSING_PRODUCT','productos/p-absent','p-absent','ABSENT',{}),validation:'REVIEW_REQUIRED',blockers:['MISSING_PRODUCT']};
 f.contract.plan.operations.push(absent);f.approval=await approve(f.contract,true,{allowPartial:true});const r=await engine().start(f);assert.equal(r.status,'REQUIRES_REVIEW');assert.equal((await ref('importaciones/_control_clisa').get()).get('locked'),false);
 assert.equal((await ref('productos/p-absent').get()).get('stock'),3);
});
test('29 real Kotlin contract canonicalization and ID policy match Node, blockers remain blocked',async()=>{
 const f=JSON.parse(await readFile(process.env.CLISA_SYNC_CONTRACT_FIXTURE || new URL('../../../docs/importacion-paso4b1/kotlin-contract-fixture.json',import.meta.url),'utf8'));
 assert.equal(hash(f.contract),f.canonicalHash);assert.equal(productId('SFYRS/1000G'),f.productId);assert.equal(existenceId('SFYRS_1000G','0001😀'),f.existenceId);assert.equal(f.contract.plan.productionExecutable,false);
 assert.equal(f.contract.plan.inventoryPolicy,'EXCEL_REFERENCE_V1');
 assert.ok(f.contract.plan.operations.every(o=>operationIdentity(o)===o.operationId));assert.equal(planIdentity(f.contract),f.contract.plan.planId);
});
test('30 endpoint guard refuses a production target before SDK IO',()=>{
 const saved=process.env.GCLOUD_PROJECT;try{process.env.GCLOUD_PROJECT='empresa-clisa';assert.throws(()=>assertEmulator(),/DEMO_PROJECT_REQUIRED/);}finally{process.env.GCLOUD_PROJECT=saved;}
});
test('31 expired locks never auto-release; stale worker fails fence',async()=>{
 const f=await fixture();await assert.rejects(engine(async point=>{if(point==='beforeUnit')await ref('importaciones/_control_clisa').update({fence:99});}).start({...f,runId:'run_fence'}),/FENCED_OUT/);
 assert.equal((await ref('productos/p1').get()).get('stock'),5);assert.equal((await ref('importaciones/_control_clisa').get()).get('locked'),true);
});
test('32 client cannot fabricate global lock/job, or bypass locked product creation',async()=>{
 const client=env.authenticatedContext('ADMIN',{role:'ADMIN',admin:true}).firestore();
 await assertFails(setDoc(doc(client,'importaciones','_control_clisa'),{locked:false}));
 await ref('importaciones/_control_clisa').set({locked:true,runId:'fixture',fence:1});
 await assertFails(setDoc(doc(client,'productos','other'),{codigo:'OTHER',nombre:'No',estado:'ACTIVO',stock:0}));
});
test('33 partial recovery rejects changed already-applied inventory rather than replaying',async()=>{
 const f=await fixture({two:true});await assert.rejects(engine(point=>{if(point==='afterUnit')throw Error('LOST');}).start({...f,runId:'run_changed'}),/LOST/);
 await ref('productos/p1').update({stock:3});await assert.rejects(engine().recover({...f,runId:'run_changed',supervised:true}),/APPLIED_DOCUMENT_CHANGED/);assert.equal((await ref('productos/p2').get()).get('stock'),5);
});
test('34 stock unchanged but serial quantity differences still require atomic stock-consistent proposal',async()=>{
 const f=await fixture();f.contract.plan.operations=f.contract.plan.operations.filter(o=>o.type!=='UPDATE_PRODUCT_STOCK');f.approval=await approve(f.contract);await assert.rejects(engine().start(f),/PROPOSED_OPERATIONS_DO_NOT_MATCH_SOURCE/);
});
test('35 same run ID returns recorded result without new journal or stock writes',async()=>{
 const f=await fixture();await engine().start({...f,runId:'run_idempotent'});const time=(await ref('productos/p1').get()).updateTime;
 const repeated=await engine().start({...f,runId:'run_idempotent'});assert.equal(repeated.status,'COMPLETED');assert.ok((await ref('productos/p1').get()).updateTime.isEqual(time));
 assert.equal((await rt.db.collection('importaciones/run_idempotent/journal').get()).size,2);
});
test('36 SALIDA committed after comparison invalidates the plan, no automatic restoration',async()=>{
 const f=await fixture();const client=env.authenticatedContext('USER',{role:'USER',email:'user@fixture.invalid'}).firestore();await assertSucceeds(salida(client));
 await assert.rejects(engine().start(f),/BASELINE_CHANGED/);assert.equal((await ref('productos/p1').get()).get('stock'),4);assert.equal((await ref('existencias/e-p1').get()).get('cantidad'),4);
});
test('37 blocked group cannot be smuggled into explicit authorized operation list',async()=>{
 const f=await fixture();f.contract.plan.operations[0].blockers=['UNVERIFIED_EXPORT_CUTOFF'];f.contract.plan.operations[0].validation='BLOCKED';
 f.approval=await approve(f.contract,true,{allowedOperationIds:f.contract.plan.operations.map(o=>o.operationId)});await assert.rejects(engine().start(f),/BLOCKED_OPERATION/);
});
test('38 pending group cannot run when only the stock half is authorized',async()=>{
 const f=await fixture();f.approval=await approve(f.contract,true,{allowPartial:true,allowedOperationIds:[f.contract.plan.operations[1].operationId]});await assert.rejects(engine().start(f),/ATOMIC_GROUP_BLOCKED/);
});
test('39 missing serial is not interpreted as permission to delete or replace',async()=>{
 const f=await fixture();await ref('existencias/extra').set({productoId:'p1',codigoProducto:'SKU/1',codigo:'extra',cantidad:0,estado:'SIN_STOCK',actualizadoEn:new Timestamp(1,0)});
 f.contract.comparison=baseline((await rt.db.collection('productos').get()).docs,(await rt.db.collection('existencias').get()).docs);f.contract.plan.baselineFingerprint=fingerprint(f.contract.comparison);f.approval=await approve(f.contract);
 const before=(await ref('existencias/extra').get()).updateTime;await engine().start(f);assert.ok((await ref('existencias/extra').get()).updateTime.isEqual(before));assert.equal((await ref('productos/p1').get()).get('stock'),6);
});
test('40 unapproved registry or a real-export attestation type is rejected',async()=>{
 const f=await fixture();await assert.rejects(new InventorySyncEngine(rt,{trust:{},clock:()=>now}).start(f),/TRUSTED_APPROVAL_REQUIRED/);
 f.approval=await approve(f.contract,true,{evidenceKind:'PRODUCTION'});await assert.rejects(engine().start(f),/REAL_EXPORT_AUTHORITY_NOT_IMPLEMENTED/);
});
test('41 formalization may change while import uses only inventory concurrency',async()=>{
 await movement();const f=await fixture();await engine(async point=>{if(point==='beforeUnit')await ref('registros/mov-p1').update({estadoFormalizacion:'COMPLETADO'});}).start(f);
 assert.equal((await ref('productos/p1').get()).get('stock'),6);assert.equal((await ref('registros/mov-p1').get()).get('estadoFormalizacion'),'COMPLETADO');
});

test('42 exhausted recovery attempts retain global lock',async()=>{
 const f=await fixture();await assert.rejects(engine(point=>{if(point==='insideUnit')throw Error('FAULT');}).start({...f,runId:'run_retry_limit'}),/FAULT/);
 await ref('importaciones/run_retry_limit').update({recoveryCount:2});await assert.rejects(engine().recover({...f,runId:'run_retry_limit',supervised:true}),/RECOVERY_ATTEMPTS_EXHAUSTED/);
 assert.equal((await ref('importaciones/_control_clisa').get()).get('locked'),true);
});
test('43 creates new existence under real legacy product ID and leaves unchanged series unwritten',async()=>{
 const f=await fixture();const source=f.contract.source.products[0];source.serials[0].quantity=5;source.serials.push({code:'0009',quantity:1,valid:true});
 f.contract.plan.operations=f.contract.plan.operations.filter(o=>o.type!=='UPDATE_EXISTENCE');
 f.contract.plan.operations.push(op('CREATE_EXISTENCE','existencias/'+existenceId('p1','0009'),'p1','SKU/1',{productoId:'p1',codigoProducto:'SKU/1',codigo:'0009',cantidad:1,estado:'DISPONIBLE'}, {},'0009'));
 f.approval=await approve(f.contract);const before=(await ref('existencias/e-p1').get()).updateTime;await engine().start(f);
 assert.ok((await ref('existencias/e-p1').get()).updateTime.isEqual(before));assert.equal((await ref('existencias/'+existenceId('p1','0009')).get()).get('productoId'),'p1');assert.equal((await ref('productos/p1').get()).get('stock'),6);
});
test('44 stock-only discrepancy reconciles to unchanged serial sums without writing the series',async()=>{
 const f=await fixture();await ref('productos/p1').update({stock:2});f.contract.source.products[0].reportedQuantity=5;f.contract.source.products[0].serials[0].quantity=5;
 f.contract.plan.operations=[op('UPDATE_PRODUCT_STOCK','productos/p1','p1','SKU/1',{stock:5},{stock:2})];
 f.contract.comparison=baseline((await rt.db.collection('productos').get()).docs,(await rt.db.collection('existencias').get()).docs);f.contract.plan.baselineFingerprint=fingerprint(f.contract.comparison);f.approval=await approve(f.contract);
 const before=(await ref('existencias/e-p1').get()).updateTime;await engine().start(f);assert.equal((await ref('productos/p1').get()).get('stock'),5);assert.ok((await ref('existencias/e-p1').get()).updateTime.isEqual(before));
});
test('45 supervisor can fence a RUNNING worker left by process interruption',async()=>{
 const f=await fixture({two:true});await assert.rejects(engine(point=>{if(point==='afterUnit')throw Error('CRASH');}).start({...f,runId:'run_interrupted'}),/CRASH/);
 await ref('importaciones/run_interrupted').update({status:'RUNNING'});const token=(await ref('importaciones/_control_clisa').get()).get('fence');
 const r=await engine().recover({...f,runId:'run_interrupted',supervised:true});assert.equal(r.status,'COMPLETED');assert.ok(r.fence>token);
});
test('46 historical size and unrecognized formalization fields do not gate import',async()=>{
 await movement();const f=await fixture({proof:false});await ref('registros/other').set({tipo:'SALIDA',productoId:'p1'});
 await engine().start(f);assert.equal((await ref('productos/p1').get()).get('stock'),6);
});

test('47 malformed historical records remain untouched and are not part of classification',async()=>{
 await ref('registros/malformed').set({tipo:'SALIDA',estado:'COMPLETADO'});const f=await fixture({proof:false});const before=(await ref('registros/malformed').get()).data();await engine().start(f);
 assert.deepEqual((await ref('registros/malformed').get()).data(),before);
});

test('48 estimates include quota reservation, audit, bounded retries and zero deletions',async()=>{
 const f=await fixture();const selection=validateContract(f.contract,bytes,f.approval,trust,now),cost=estimate(f.contract,selection);
 assert.equal(cost.inventoryWrites,2);assert.equal(cost.deletes,0);assert.ok(cost.writes>cost.inventoryWrites);assert.ok(cost.reads>f.contract.comparison.products.length+f.contract.comparison.existences.length);assert.equal(cost.maxTransactionAttempts,3);
});
test('49 inactive product is blocked, without reactivation',async()=>{
 const f=await fixture();await ref('productos/p1').update({estado:'INACTIVO'});await assert.rejects(engine().start(f),/INACTIVE_PRODUCT_REQUIRES_REVIEW/);
 assert.equal((await ref('productos/p1').get()).get('estado'),'INACTIVO');
});
test('50 unchanged informational operations do not force a review lock or get written',async()=>{
 const f=await fixture();const skip={...op('SKIP_UNCHANGED','existencias/unrelated','unrelated','UNRELATED',{}),validation:'SKIPPED',risk:'NONE',atomicGroup:'product:unrelated'};
 f.contract.plan.operations.push(skip);f.approval=await approve(f.contract,true,{allowPartial:true});const r=await engine().start(f);assert.equal(r.status,'COMPLETED');assert.equal(r.omittedCount,0);
 assert.equal((await ref('existencias/unrelated').get()).exists,false);assert.equal((await ref('importaciones/_control_clisa').get()).get('locked'),false);
});
test('51 supervised recovery cannot change the originally approved product scope',async()=>{
 const f=await fixture({two:true});await assert.rejects(engine(point=>{if(point==='afterUnit')throw Error('INTERRUPTED');}).start({...f,runId:'run_scope'}),/INTERRUPTED/);
 const approval=await approve(f.contract,true,{allowPartial:true,allowedOperationIds:f.contract.plan.operations.filter(o=>o.productId==='p1').map(o=>o.operationId)});
 await assert.rejects(engine().recover({...f,approval,runId:'run_scope',supervised:true}),/RECOVERY_SCOPE_CHANGED/);
 assert.equal((await ref('productos/p2').get()).get('stock'),5);assert.equal((await ref('importaciones/_control_clisa').get()).get('locked'),true);
});
test('Excel policy rejects a signed stock deduction from a historical transfer',async()=>{
 await movement('PENDIENTE');const f=await fixture();
 f.contract.plan.operations.find(o=>o.type==='UPDATE_PRODUCT_STOCK').expected.stock=5;
 f.contract.plan.operations.find(o=>o.type==='UPDATE_EXISTENCE').expected.cantidad=5;
 f.approval=await approve(f.contract,false,{reconciliation:{p1:{stockConciliado:5}}});
 await assert.rejects(engine().start(f),/PROPOSED_OPERATIONS_DO_NOT_MATCH_SOURCE/);
 assert.equal((await ref('productos/p1').get()).get('stock'),5);
});
test('a serial created concurrently under another product blocks the whole new product unit',async()=>{
 const f=await fixture({fresh:true});let once=false;
 await assert.rejects(engine(async point=>{if(point==='beforeUnit'&&!once){once=true;await ref('productos/other-real').set({codigo:'OTHER',nombre:'Other',stock:1,estado:'ACTIVO'});
 await ref('existencias/other-serial').set({productoId:'other-real',codigoProducto:'OTHER',codigo:'0001',cantidad:1,estado:'DISPONIBLE'});}}).start(f),/SERIAL_CREATED_CONCURRENTLY/);
 assert.equal((await ref('productos/'+f.pid).get()).exists,false);
});
test('52 legacy recovery checkpoint without immutable scope requires explicit review',async()=>{
 const f=await fixture();await assert.rejects(engine(point=>{if(point==='insideUnit')throw Error('FAILED');}).start({...f,runId:'run_legacy_scope'}),/FAILED/);
 await ref('importaciones/run_legacy_scope').update({schemaVersion:1,authorizationScopeHash:FieldValue.delete()});
 await assert.rejects(engine().recover({...f,runId:'run_legacy_scope',supervised:true}),/RECOVERY_SCOPE_CHANGED/);
 assert.equal((await ref('productos/p1').get()).get('stock'),5);
});
