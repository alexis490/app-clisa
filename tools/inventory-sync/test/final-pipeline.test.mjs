import test,{before,after,beforeEach} from 'node:test';
import assert from 'node:assert/strict';
import { readFile,mkdtemp,writeFile,rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join,relative } from 'node:path';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { generateKeyPairSync,sign } from 'node:crypto';
import { Timestamp } from 'firebase-admin/firestore';
import { createEmulatorRuntime,HOST,PROJECT } from '../runtime.mjs';
import { InventorySyncEngine } from '../engine.mjs';
import { prepareAdministrativeContract } from '../preparation.mjs';
import { canonical,hash } from '../canonical.mjs';
import { productId,existenceId } from '../canonical.mjs';
import { historyHash,stamp } from '../inventory.mjs';

let rt;const {publicKey,privateKey}=generateKeyPairSync('ed25519');
before(async()=>{rt=createEmulatorRuntime();for(const uid of ['ADMIN','USER']){try{await rt.auth.deleteUser(uid);}catch{}await rt.auth.createUser({uid});await rt.auth.setCustomUserClaims(uid,{role:uid,admin:uid==='ADMIN'});}});
after(async()=>{await rt?.close();});
beforeEach(async()=>{const r=await fetch(`http://${HOST}/emulator/v1/projects/${PROJECT}/databases/(default)/documents`,{method:'DELETE'});assert.equal(r.status,200);for(const uid of ['ADMIN','USER'])await rt.db.doc(`usuarios/${uid}`).set({role:uid,estado:'ACTIVO'});});
const engine=(fault=()=>{})=>new InventorySyncEngine(rt,{trust:{operator:publicKey},fault});
async function fixture(name='valid',mode=null){
 const base=new URL('../../../docs/importacion-paso4b-final/fixtures/',import.meta.url);
 const raw=JSON.parse(await readFile(new URL(name+'.json',base),'utf8')),bytes=await readFile(new URL(name+'.xlsx',base));
 for(const p of raw.comparison.products)await rt.db.doc(`productos/${p.id}`).set({codigo:p.code,nombre:p.name,stock:p.stock,cantidadRestante:p.remaining,estado:'ACTIVO',actualizadoEn:new Timestamp(1,0)});
 for(const e of raw.comparison.existences)await rt.db.doc(`existencias/${e.id}`).set({productoId:e.productId,codigoProducto:e.productCode,codigo:e.code,cantidad:e.quantity,estado:e.state,actualizadoEn:new Timestamp(1,0)});
 if(['pending','completed','ambiguous'].includes(name))await rt.db.doc('registros/m1').set({tipo:'SALIDA',estado:'COMPLETADO',productoId:'p1',codigoProducto:'SKU/1',existenciaId:'e-p1',codigoExistencia:'0001',cantidad:2,fechaHora:new Timestamp(1,0),tiendaOrigenId:'clisa',tiendaDestinoId:'toqen',estadoPago:'Pendiente',estadoFormalizacion:name==='completed'?'COMPLETADO':'PENDIENTE',formalizadoEn:name==='completed'?new Timestamp(2,0):null,formalizadoPor:name==='completed'?'ADMIN':null});
 // A fresh fictitious baseline with exactly the field versions emitted by Kotlin has just been seeded.
 raw.plan.observedAtMillis=Date.now();
 const target=raw.source.products[0].reportedQuantity;
 const contract=prepareAdministrativeContract(raw,{'SKU/1':{stockConciliado:target,serialTargets:{'0001':target}}});
 if(mode==='USER')contract.requester.uid='USER';
 const now=Date.now();
 const payload={contractHash:hash(contract),fileSha256:contract.plan.fileSha256,sourceHash:hash(contract.source),requesterUid:contract.requester.uid,issuedAt:now-1,expiresAt:now+60000,
 evidenceKind:'EMULATOR_FIXTURE_ONLY',allowedOperationIds:contract.plan.operations.filter(o=>['CREATE_PRODUCT','CREATE_EXISTENCE','UPDATE_EXISTENCE','UPDATE_PRODUCT_STOCK','SKIP_UNCHANGED'].includes(o.type)).map(o=>o.operationId),allowPartial:true,
 budget:{reads:50000,writes:20000,deletes:20000,reserveReads:500,reserveWrites:500,measuredAt:now},
 ...(contract.source.issues.length?{independentScope:{scope:structuredClone(contract.preparation.scopeRequest),reference:'fixture:verified-disjoint-local-issues'}}:{})};
 if(mode==='QUOTA')payload.budget.reads=1;
 const approval={keyId:'operator',payload,signature:sign(null,Buffer.from(canonical(payload)),privateKey).toString('base64')};
 return {contract,bytes,approval,raw};
}
test('FINAL unchanged XLSX -> reader -> comparison -> skip without job or writes',async()=>{const f=await fixture('unchanged');const before=(await rt.db.doc('productos/p1').get()).updateTime;await assert.rejects(engine().start(f),/NO_AUTHORIZED_CHANGES/);assert.ok((await rt.db.doc('productos/p1').get()).updateTime.isEqual(before));assert.equal((await rt.db.collection('importaciones').get()).size,0);});

test('FINAL new XLSX product and series are created atomically from Kotlin reader IDs',async()=>{
 const f=await fixture('new'),pid=productId('SKU/1');await engine().start(f);
 assert.equal((await rt.db.doc('productos/'+pid).get()).get('stock'),6);
 assert.equal((await rt.db.doc('existencias/'+existenceId(pid,'0001')).get()).get('cantidad'),6);
});
test('FINAL absent positive series remains unchanged and does not block reference stock or next import lock',async()=>{
 const f=await fixture('retained-absence'),before=await rt.db.doc('existencias/retained').get();
 f.approval.payload.allowPartial=false;f.approval.signature=sign(null,Buffer.from(canonical(f.approval.payload)),privateKey).toString('base64');
 const result=await engine().start(f);
 assert.equal((await rt.db.doc('productos/p1').get()).get('stock'),6);
 assert.deepEqual((await rt.db.doc('existencias/retained').get()).data(),before.data());
 assert.ok((await rt.db.doc('existencias/retained').get()).updateTime.isEqual(before.updateTime));
 assert.equal(result.status,'REQUIRES_REVIEW');assert.equal((await rt.db.doc('importaciones/_control_clisa').get()).get('locked'),false);
});
test('FINAL valid XLSX -> administrative plan -> signed reconciliation -> transaction -> audit',async()=>{const f=await fixture();const r=await engine().start(f);assert.equal(r.status,'COMPLETED');assert.equal((await rt.db.doc('productos/p1').get()).get('stock'),6);assert.equal((await rt.db.collection(`importaciones/${r.runId}/journal`).get()).size,2);assert.equal((await rt.db.doc('productos/p1').get()).get('cantidadRestante'),99);});
test('FINAL proposal is derived from Excel without manual targets and refuses lower historical targets',async()=>{
 const f=await fixture('pending');const proposal=prepareAdministrativeContract(f.raw);
 assert.equal(proposal.plan.operations.find(o=>o.type==='UPDATE_PRODUCT_STOCK').expected.stock,10);
 assert.throws(()=>prepareAdministrativeContract(f.raw,{'SKU/1':{stockConciliado:8,serialTargets:{'0001':8}}}),/TARGET_MUST_EQUAL_EXCEL/);
});
test('FINAL pending uses Excel 10 without another deduction',async()=>{const f=await fixture('pending');await engine().start(f);assert.equal((await rt.db.doc('productos/p1').get()).get('stock'),10);assert.equal((await rt.db.doc('existencias/e-p1').get()).get('cantidad'),10);assert.equal((await rt.db.doc('registros/m1').get()).get('estadoFormalizacion'),'PENDIENTE');});
test('FINAL completed included updates to 8 without second deduction',async()=>{const f=await fixture('completed');const original=(await rt.db.doc('registros/m1').get()).data();await engine().start(f);assert.equal((await rt.db.doc('productos/p1').get()).get('stock'),8);assert.deepEqual((await rt.db.doc('registros/m1').get()).data(),original);});
test('FINAL ambiguous historical inclusion does not change the reference inventory',async()=>{const f=await fixture('ambiguous');await engine().start(f);assert.equal((await rt.db.doc('productos/p1').get()).get('stock'),10);});

for(const name of ['duplicate','incomplete'])test(`FINAL ${name} XLSX is not made complete by preparation`,async()=>{await assert.rejects(fixture(name),/DUPLICATE|SUMMARY|UNKNOWN_PRODUCT_SCOPE|UNPROVEN_ISSUE/);assert.equal((await rt.db.collection('importaciones').get()).size,0);});
test('FINAL independently proven local issues do not block unrelated valid product',async()=>{const f=await fixture('local-issue');assert.ok(f.contract.source.errorCount>0);assert.equal(f.contract.source.completeness.state,'INCOMPLETE');const r=await engine().start(f);assert.equal(r.status,'COMPLETED');assert.equal((await rt.db.doc('productos/p1').get()).get('stock'),6);assert.equal((await rt.db.doc('productos/p2').get()).exists,false);});
test('FINAL execution failure -> supervised retry -> idempotent replay and one audit per operation',async()=>{const f=await fixture();let failed=false;await assert.rejects(engine(point=>{if(point==='insideUnit'&&!failed){failed=true;throw Error('TEST_FAILURE');}}).start({...f,runId:'run_final_retry'}),/TEST_FAILURE/);assert.equal((await rt.db.doc('productos/p1').get()).get('stock'),5);await engine().recover({...f,runId:'run_final_retry',supervised:true});const before=(await rt.db.doc('productos/p1').get()).updateTime;await engine().start({...f,runId:'run_final_retry'});assert.ok((await rt.db.doc('productos/p1').get()).updateTime.isEqual(before));assert.equal((await rt.db.collection('importaciones/run_final_retry/journal').get()).size,2);});
test('FINAL concurrent inventory change still invalidates prepared native versions',async()=>{const f=await fixture();await assert.rejects(engine(async point=>{if(point==='beforeUnit')await rt.db.doc('productos/p1').update({stock:4});}).start(f),/DOCUMENT_VERSION_CONFLICT/);assert.equal((await rt.db.doc('productos/p1').get()).get('stock'),4);});
for(const mode of ['QUOTA','USER'])test(`FINAL ${mode} cannot execute imported plan`,async()=>{const f=await fixture('valid',mode);await assert.rejects(engine().start(f),mode==='QUOTA'?/QUOTA_INSUFFICIENT/:/UNAUTHORIZED_ADMIN/);assert.equal((await rt.db.doc('productos/p1').get()).get('stock'),5);});
for(const mode of ['missing','changed'])test(`FINAL ${mode} isolation certificate cannot bypass source issues`,async()=>{
 const f=await fixture('local-issue');
 if(mode==='missing')delete f.approval.payload.independentScope;else f.approval.payload.independentScope.scope.skus=['SKU/2'];
 f.approval.signature=sign(null,Buffer.from(canonical(f.approval.payload)),privateKey).toString('base64');
 await assert.rejects(engine().start(f),/SOURCE_HAS_ISSUES/);assert.equal((await rt.db.doc('productos/p1').get()).get('stock'),5);
});
test('FINAL actual CLI prepares exported Kotlin proposal, executes signed Emulator request and returns bound simulation report',async()=>{
 const f=await fixture(),dir=await mkdtemp(join(tmpdir(),'clisa-final-cli-')),execute=promisify(execFile);
 try{
  await writeFile(join(dir,'raw.json'),JSON.stringify(f.raw));
  await writeFile(join(dir,'source.xlsx'),f.bytes);
  await execute(process.execPath,['cli.mjs','--contract',join(dir,'raw.json'),'--excel',join(dir,'source.xlsx'),'--prepare','true','--output',join(dir,'prepared')]);
  assert.equal((await rt.db.doc('productos/p1').get()).get('stock'),5);
  const prepared=JSON.parse(await readFile(join(dir,'prepared','proposal.json'),'utf8'));assert.equal(hash(prepared),hash(f.contract));
  await writeFile(join(dir,'source.xlsx'),f.bytes);await writeFile(join(dir,'approval.json'),JSON.stringify(f.approval));
  await writeFile(join(dir,'public.pem'),publicKey.export({type:'spki',format:'pem'}));
  await execute(process.execPath,['cli.mjs','--contract',join(dir,'prepared','proposal.json'),'--excel',join(dir,'source.xlsx'),'--approval',join(dir,'approval.json'),'--public-key',join(dir,'public.pem'),'--output',join(dir,'result'),'--run-id','run_final_cli']);
  const report=JSON.parse(await readFile(join(dir,'result','simulation-summary.json'),'utf8'));
  assert.equal(report.environment,'EMULATOR');assert.equal(report.production,false);assert.equal(report.sourcePlanId,f.raw.plan.planId);assert.equal(report.status,'COMPLETED');
  assert.equal((await rt.db.collection('importaciones/run_final_cli/journal').get()).size,2);
 }finally{const child=relative(tmpdir(),dir);assert.ok(child&&!child.startsWith('..'));await rm(dir,{recursive:true,force:true});}
});
