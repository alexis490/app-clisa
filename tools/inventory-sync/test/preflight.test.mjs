import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { ProductionReadPreflight,runReadPreflight,READ_PERMISSIONS,FUTURE_WRITE_PERMISSIONS,checkSample } from '../read-preflight.mjs';
import { reviewReconciliation } from '../reconciliation-policy.mjs';
import { buildIssueReview } from '../issue-review.mjs';

const root='projects/empresa-clisa/databases/(default)/documents/';
const field=v=>v===null?{nullValue:null}:typeof v==='string'?{stringValue:v}:typeof v==='boolean'?{booleanValue:v}:Number.isInteger(v)?{integerValue:String(v)}:{timestampValue:v.timestamp};
const document=(path,data)=>({name:root+path,fields:Object.fromEntries(Object.entries(data).map(([k,v])=>[k,field(v)]))});
function mock({missingPermission=false,identityEmail='operator@fixture.invalid',role='ADMIN',control=null,profileCount=1,authRole='ADMIN',duplicate=false,unavailable=false}={}){
 const calls=[];const rows={
  productos:[document('productos/LEGACY_ID',{codigo:'SFYRS/1000G',nombre:'Fixture',stock:4,cantidadRestante:9,estado:'ACTIVO'})],
  existencias:[document('existencias/legacy-series',{productoId:'LEGACY_ID',codigoProducto:'SFYRS/1000G',codigo:'0001',cantidad:4,estado:'DISPONIBLE'})],
  registros:['PENDIENTE','COMPLETADO','SIN_VERIFICAR'].map((state,i)=>document('registros/r'+i,{productoId:'LEGACY_ID',existenciaId:'legacy-series',cantidad:1,tipo:'SALIDA',estado:'COMPLETADO',fechaHora:{timestamp:'2026-10-22T00:00:00Z'},estadoPago:'Pendiente',tiendaOrigenId:'clisa',tiendaDestinoId:'toqen',...(state==='SIN_VERIFICAR'?{}:{estadoFormalizacion:state})})),
  usuarios:Array.from({length:profileCount},(_,i)=>document('usuarios/admin'+i,{uid:'admin'+i,role,estado:'ACTIVO'})),
  tiendas:[document('tiendas/clisa',{nombre:'CLISA',estado:'ACTIVA'}),document('tiendas/toqen',{nombre:'TOQEN',estado:'ACTIVA'})],
  categorias:[document('categorias/a',{nombre:'Audio',activo:true})],marcas:[document('marcas/a',{nombre:'Marca',activo:true})]
 };
 const fetchImpl=async(url,opts)=>{
  calls.push({url,...opts});let data={},status=200;
  if(unavailable)return {ok:false,status:403,json:async()=>({})};
  if(url.endsWith('userinfo'))data={email:identityEmail,verified_email:true};
  else if(url.includes('testIamPermissions'))data={permissions:missingPermission?[]:[...READ_PERMISSIONS,...FUTURE_WRITE_PERMISSIONS]};
  else if(url.endsWith('/databases/(default)'))data={name:'projects/empresa-clisa/databases/(default)',type:'FIRESTORE_NATIVE',locationId:'fixture'};
  else if(url.endsWith('/releases/cloud.firestore'))data={rulesetName:'projects/empresa-clisa/rulesets/current-fixture'};
  else if(url.includes('/rulesets/'))data={source:{files:[{name:'firestore.rules',content:'// Existing deployed reference, not the lock proposal'}]}};
  else if(url.endsWith('/indexes'))data={indexes:[]};
  else if(url.includes('/fields?'))data={fields:[]};
  else if(url.endsWith(':listCollectionIds'))data={collectionIds:Object.keys(rows)};
  else if(url.endsWith('/importaciones/_control_clisa')){if(control===null)status=404;else data=document('importaciones/_control_clisa',control);}
  else if(url.endsWith(':runQuery')){
   const q=JSON.parse(opts.body).structuredQuery,name=q.from[0].collectionId;
   data=rows[name].slice(0,q.limit).map(document=>({document}));
   if(duplicate&&name==='productos'&&q.where)data=[data[0],data[0]];
  }else if(url.endsWith('/accounts:lookup'))data={users:[{localId:'admin0',customAttributes:JSON.stringify({role:authRole,admin:true}),disabled:false}]};
  else throw Error('UNEXPECTED_ENDPOINT:'+url);
  return {ok:status===200,status,json:async()=>data};
 };
 return {calls,reader:new ProductionReadPreflight({project:'empresa-clisa',token:'fake-local-token',fetchImpl}),rows};
}
test('preflight project mismatch is rejected before requests',()=>{
 assert.throws(()=>new ProductionReadPreflight({project:'other',token:'fake',fetchImpl:()=>assert.fail()}),/WRONG_FIREBASE_PROJECT/);
});
test('successful bounded reads never become authorization; no write API is reachable',async()=>{
 const m=mock();const r=await runReadPreflight(m.reader,{expectedAccountEmail:'operator@fixture.invalid'});
 assert.equal(r.halted,undefined);assert.equal(r.status,'BLOQUEADO');assert.equal(r.productionExecutable,false);assert.ok(r.cost.reservedDocumentReadUnits<=24);
 assert.equal(r.evidence.samples.registros[2].formalization,'SIN_VERIFICAR');assert.equal(r.evidence.samples.productos[0].path,'productos/LEGACY_ID');
 for(const c of m.calls){assert.ok(['GET','POST'].includes(c.method));if(c.method==='POST')assert.match(c.url,/:(testIamPermissions|listCollectionIds|runQuery|lookup)$/);assert.equal(c.redirect,'error');}
 assert.equal(r.cost.documentWrites,0);assert.ok(!JSON.stringify(r).includes('fake-local-token'));
});
test('insufficient IAM stops immediately without any document requests or writes',async()=>{
 const m=mock({missingPermission:true}),r=await runReadPreflight(m.reader,{expectedAccountEmail:'operator@fixture.invalid'});assert.equal(r.halted,true);assert.equal(m.calls.length,2);assert.equal(r.cost.reservedDocumentReadUnits,0);
});
test('signed-in CLI identity does not bypass email or application profile mismatch',async()=>{
 const m=mock(),r=await runReadPreflight(m.reader,{expectedAccountEmail:'someone-else@fixture.invalid'});assert.equal(r.halted,true);assert.equal(m.calls.length,1);
 const n=mock({profileCount:0}),v=await runReadPreflight(n.reader,{expectedAccountEmail:'operator@fixture.invalid'});assert.ok(v.blockers.includes('APPLICATION_OPERATOR_PROFILE_UNVERIFIED'));assert.equal(v.evidence.samples,undefined);
});
test('USER and conflicting Auth claims stop before inventory sampling',async()=>{
 for(const config of [{role:'USER'},{authRole:'SUPER_ADMIN'}]){
  const m=mock(config),r=await runReadPreflight(m.reader,{expectedAccountEmail:'operator@fixture.invalid'});assert.equal(r.halted,true);assert.equal(r.evidence.samples,undefined);
 }
});
test('active or malformed control stops without trying to unlock it',async()=>{
 for(const control of [{locked:true,fence:5},{locked:false}]){
  const m=mock({control}),r=await runReadPreflight(m.reader,{expectedAccountEmail:'operator@fixture.invalid'});assert.equal(r.halted,true);assert.ok(r.blockers.includes('IMPORT_CONTROL_REQUIRES_REVIEW'));assert.equal(r.evidence.applicationProfile,undefined);
 }
});
test('cap prevents broad queries and arbitrary fields, including users listing',async()=>{
 const m=mock();await assert.rejects(m.reader.sample('existencias',22762),/UNBOUNDED/);await assert.rejects(m.reader.sample('usuarios',2),/USER_COLLECTION_SCAN_FORBIDDEN/);
 await assert.rejects(m.reader.sample('registros',2,{field:'estadoPago',value:'Pendiente'}),/QUERY_FILTER_NOT_ALLOWED/);assert.equal(m.calls.length,0);
 const r=new ProductionReadPreflight({project:'empresa-clisa',token:'fake',documentBudget:1,fetchImpl:async()=>assert.fail('No network allowed after budget exceeded')});await assert.rejects(r.sample('productos',2),/READ_BUDGET_EXCEEDED/);
});
test('foreign ruleset is not followed and transport errors stop preflight',async()=>{
 const m=mock();await assert.rejects(m.reader.ruleset('projects/other/rulesets/x'),/WRONG_RULESET_PROJECT/);assert.equal(m.calls.length,0);
 const n=mock({unavailable:true}),r=await runReadPreflight(n.reader,{expectedAccountEmail:'operator@fixture.invalid'});assert.equal(r.halted,true);assert.equal(n.calls.length,1);assert.equal(r.cost.documentsReturned,0);
});
test('sample compatibility keeps stock/remaining separate and duplicate checks bounded',async()=>{
 const m=mock({duplicate:true}),r=await runReadPreflight(m.reader,{expectedAccountEmail:'operator@fixture.invalid'});
 assert.ok(r.blockers.includes('SAMPLED_IDENTITY_AMBIGUOUS'));assert.ok(r.evidence.samples.productos[0].findings.includes('LEGACY_COUNTER_DIFFERS_PRESERVE'));
 assert.equal(r.evidence.samples.productos[0].fields.stock,4);assert.equal(r.evidence.samples.productos[0].fields.cantidadRestante,9);
});
test('malformed schema and legacy formalization never imply reconciled stock',()=>{
 const r=checkSample('registros',document('registros/x',{productoId:'p',cantidad:-1,estadoFormalizacion:'COMPLETADO'}));assert.ok(r.findings.includes('HISTORICAL_SCHEMA_REQUIRES_REVIEW'));assert.equal(r.inclusionInExcel,'UNPROVEN');
 const e=checkSample('existencias',document('existencias/x',{cantidad:-1,estado:'DISPONIBLE'}));assert.ok(e.findings.includes('EXISTENCE_SCHEMA_INCOMPATIBLE'));
});
for(const state of ['PENDIENTE','COMPLETADO','SIN_VERIFICAR'])for(const exported of [100,300])test(`reconciliation ${state}, Excel ${exported<200?'before':'after'} confirmation stays blocked`,()=>{
 const movement={id:'r1',productoId:'p',formalizadoEnMillis:200,...(state==='SIN_VERIFICAR'?{}:{estadoFormalizacion:state})};
 const before=JSON.stringify(movement);const r=reviewReconciliation({fileSha256:'a'.repeat(64),productId:'p',declaredExportMillis:exported,movements:[movement]});
 assert.equal(r.productionExecutable,false);assert.equal(r.rows[0].decision,'BLOCKED');assert.equal(r.rows[0].includedInExport,'UNPROVEN');assert.equal(JSON.stringify(movement),before);
});
test('manual/fake verification flags cannot enable reconciliation or arithmetic adjustments',()=>{
 const r=reviewReconciliation({fileSha256:'a'.repeat(64),productId:'p',declaredExportMillis:300,movements:[],exportEvidence:{verified:true,kind:'EMULATOR_FIXTURE_ONLY'}});
 assert.equal(r.status,'BLOQUEADO');assert.equal(r.productionExecutable,false);assert.ok(r.blockers.includes('FIXTURE_EVIDENCE_NOT_VALID_FOR_PRODUCTION'));assert.equal(r.proposedStock,undefined);
});
test('real 567-issue dossier keeps all rows, source literals and global completeness blocks',async()=>{
 const source=JSON.parse(await readFile(new URL('../../../docs/importacion-paso4b2a/issue-source.json',import.meta.url),'utf8'));const before=JSON.stringify(source);const r=buildIssueReview(source);
 assert.equal(r.total,567);assert.deepEqual(r.byKind,{DUPLICATE_SERIAL:48,DUPLICATE_SKU:15,MISSING_SKU:346,NEGATIVE_QUANTITY:2,SERIAL_WHITESPACE:156});
 assert.equal(new Set(r.rows.map(i=>i.issueId)).size,567);assert.equal(r.unknownScope,346);assert.equal(r.rows.every(i=>i.blocksFileCompleteness&&i.reviewState==='OPEN'),true);assert.equal(r.productionExecutable,false);
 assert.ok(r.rows.filter(i=>i.kind==='SERIAL_WHITESPACE').every(i=>i.serialCode!==i.serialCode.trim()));assert.equal(JSON.stringify(source),before);
});
test('issue product scope differs from unidentifiable file scope without authorizing a subset',()=>{
 const base={sheet:'Hoja',row:2,productCode:'SKU',productName:'Producto',serialCode:'0001 ',message:'Conservar literal'};
 const r=buildIssueReview({fileSha256:'a'.repeat(64),issues:[{...base,kind:'SERIAL_WHITESPACE'},{...base,kind:'MISSING_SKU',productCode:null}]});
 assert.equal(r.rows[0].scope,'PRODUCT_IDENTITY');assert.equal(r.rows[1].scope,'FILE_SCOPE_UNKNOWN');assert.ok(r.rows.every(i=>i.blocksFileCompleteness&&i.blocksProduct));
});
test('proposed IAM grants no destructive privilege and preflight role remains read-only',async()=>{
 const readRole=JSON.parse((await readFile(new URL('../../../security/paso4b2a/preflight-role.proposed.json',import.meta.url),'utf8')).replace(/^\uFEFF/,''));
 const workerRole=JSON.parse((await readFile(new URL('../../../security/paso4b2a/worker-role.proposed.json',import.meta.url),'utf8')).replace(/^\uFEFF/,''));
 assert.ok(readRole.includedPermissions.every(p=>!/(create|update|delete|setIamPolicy)$/.test(p)));
 for(const role of [readRole,workerRole])assert.ok(role.includedPermissions.every(p=>!['datastore.entities.delete','firebaserules.releases.update','firebaseauth.users.update','resourcemanager.projects.setIamPolicy'].includes(p)));
 assert.ok(workerRole.includedPermissions.includes('datastore.entities.update'));
});
