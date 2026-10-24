import test,{before,after,beforeEach} from 'node:test';
import assert from 'node:assert/strict';
import { readFile,writeFile,mkdtemp,rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { createEmulatorRuntime,HOST,AUTH_HOST,PROJECT } from '../runtime.mjs';
import { classify,loadRules,parseRules,catalogDigest,metadata,decisionId,applyClassification,previewClassification,readCatalog } from '../classification.mjs';

let rt,rules,fixture,catalog;const tokens={};
before(async()=>{
 rt=createEmulatorRuntime();rules=await loadRules();
 fixture=JSON.parse(await readFile(new URL('../../../docs/clasificacion-reglas/kotlin-fixture.json',import.meta.url),'utf8'));catalog=fixture.catalog;
 for(const uid of ['ADMIN','USER']){
  try{await rt.auth.deleteUser(uid);}catch{}
  const email=uid.toLowerCase()+'@classification.example';await rt.auth.createUser({uid,email,password:'LocalOnly123!'});
  await rt.auth.setCustomUserClaims(uid,{role:uid,admin:uid==='ADMIN'});
  const res=await fetch(`http://${AUTH_HOST}/identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key=local`,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({email,password:'LocalOnly123!',returnSecureToken:true})});
  const data=await res.json();assert.ok(data.idToken);tokens[uid]=data.idToken;
 }
});
after(async()=>{await rt?.close();});
beforeEach(async()=>{
 const r=await fetch(`http://${HOST}/emulator/v1/projects/${PROJECT}/databases/(default)/documents`,{method:'DELETE'});assert.equal(r.status,200);
 for(const role of ['ADMIN','USER'])await rt.db.doc('usuarios/'+role).set({role,estado:'ACTIVO'});
 for(const [key,path] of [['categories','categorias'],['brands','marcas']])for(const row of catalog[key])await rt.db.doc(path+'/'+row.id).set({nombre:row.name,activo:row.active});
 await rt.db.doc('productos/SFYRS_1000G').set({codigo:'SFYRS/1000G',nombre:'Parlante Creative Pebble',stock:2,cantidadRestante:99,estado:'ACTIVO'});
 await rt.db.doc('existencias/e1').set({productoId:'SFYRS_1000G',codigoProducto:'SFYRS/1000G',codigo:'0001',cantidad:2});
 await rt.db.doc('registros/r1').set({tipo:'SALIDA',estadoPago:'Pendiente',estadoFormalizacion:'PENDIENTE',cantidad:1});
});
const apply=m=>applyClassification(rt,m,{idToken:tokens.ADMIN});
function manifest(patches={},choices=[['categoriaId','audio-real','BULK'],['marcaId','creative-real','BULK']]){
 const m=structuredClone(fixture.manifest);m.products[0]={...m.products[0],...patches};m.decisions=[];
 const p=m.products[0],c=classify(p,catalog,rules);
 for(const [field,id,mode] of choices){const s=field==='categoriaId'?c.category:c.brand;
  m.decisions.push({productKey:p.key,field,id,mode,reason:'Revisión explícita de fixture',reviewer:'ADMIN',atMillis:1,
   original:JSON.stringify(metadata(m,p,field,s)),decisionId:decisionId(m,p,field,s)});
 }
 return m;
}
async function untouched(){return {stock:(await rt.db.doc('productos/SFYRS_1000G').get()).get('stock'),remaining:(await rt.db.doc('productos/SFYRS_1000G').get()).get('cantidadRestante'),existence:(await rt.db.doc('existencias/e1').get()).data(),record:(await rt.db.doc('registros/r1').get()).data()};}

test('Kotlin and Node share exact rules, catalog digest, suggestions and approval IDs',async()=>{
 assert.equal(parseRules(fixture.rulesText).digest,rules.digest);assert.equal(catalogDigest(catalog),fixture.manifest.catalogDigest);
 const p=fixture.manifest.products[0],c=classify(p,catalog,rules);assert.deepEqual(c.category,fixture.category);assert.deepEqual(c.brand,fixture.brand);
 const result=await apply(fixture.manifest);assert.equal(result.changes.length,1);assert.equal(result.changes[0].id,'SFYRS_1000G');
});
test('recognized product metadata changes never touch counters, series, records or payment',async()=>{
 const baseline=await untouched(),result=await apply(manifest());assert.deepEqual(await untouched(),baseline);
 assert.deepEqual(result.changes[0].patch,{categoriaId:'audio-real',marcaId:'creative-real'});assert.equal(result.stockOperations,0);
});
test('ambiguous name stays REVISAR and cannot be bulk-approved',async()=>{
 const m=manifest({name:'Parlante auriculares Creative HP'});assert.equal(classify(m.products[0],catalog,rules).category.state,'REVISAR');
 await assert.rejects(apply(m),/CONFLICT_REQUIRES_INDIVIDUAL_REVIEW/);
});
test('individual review may resolve conflicting names using an existing active ID',async()=>{
 const name='Parlante auriculares Creative HP';await rt.db.doc('productos/SFYRS_1000G').update({nombre:name});
 const m=manifest({name},[['categoriaId','audio-real','INDIVIDUAL'],['marcaId','hp-real','INDIVIDUAL']]);assert.equal((await apply(m)).changes.length,1);
});
test('unknown product stays unclassified and missing description remains pending',async()=>{
 const m=manifest({name:'Objeto genérico'},[]),p=await previewClassification(rt,m);
 assert.equal(p.products[0].category.state,'SIN_IDENTIFICAR');assert.equal(p.products[0].brand.state,'SIN_IDENTIFICAR');assert.equal(p.products[0].descriptionPending,true);assert.equal(p.writes,0);
});
test('valid completed fields remain locked even with contradictory name',async()=>{
 const m=manifest({categoryId:'headset-real',brandId:'hp-real'},[['marcaId','creative-real','INDIVIDUAL']]);
 assert.equal(classify(m.products[0],catalog,rules).brand.preserved,true);await assert.rejects(apply(m),/PROTECTED_EXISTING_OR_AMBIGUOUS_IDENTITY/);
});
test('multiple serials do not cause repeated product writes',async()=>{
 for(let i=2;i<8;i++)await rt.db.doc('existencias/e'+i).set({productoId:'SFYRS_1000G',codigo:'000'+i,cantidad:1});
 assert.equal((await apply(manifest())).changes.length,1);assert.equal((await rt.db.collection('existencias').get()).size,7);
});
test('similar brand names and substrings do not match incorrectly',async()=>{
 assert.equal(classify({...fixture.manifest.products[0],name:'Parlante Creativo'},catalog,rules).brand.proposed,'creativo-real');
 assert.equal(classify({...fixture.manifest.products[0],name:'CreativePro hpbook'},catalog,rules).brand.state,'SIN_IDENTIFICAR');
});
test('category absent from catalog is never invented or accepted',async()=>{
 const empty={...catalog,categories:[]};assert.equal(classify(fixture.manifest.products[0],empty,rules).category.state,'SIN_IDENTIFICAR');
 const m=manifest({},[['categoriaId','inventado','INDIVIDUAL']]);await assert.rejects(apply(m),/INVALID_REFERENCE/);
});
test('same Excel reimport is idempotent without metadata rewrites',async()=>{
 const m=manifest();await apply(m);const before=(await rt.db.doc('productos/SFYRS_1000G').get()).updateTime;
 const result=await apply(m);assert.deepEqual(result.unchanged,['SFYRS_1000G']);assert.equal(result.changes.length,0);assert.ok((await rt.db.doc('productos/SFYRS_1000G').get()).updateTime.isEqual(before));
});
for(const change of ['rename','inactive','added','removed'])test(`catalog ${change} invalidates previous approvals`,async()=>{
 const ref=rt.db.doc('marcas/creative-real');
 if(change==='rename')await ref.update({nombre:'Creative Technology'});
 if(change==='inactive')await ref.update({activo:false});
 if(change==='added')await rt.db.doc('marcas/new-real').set({nombre:'Nueva',activo:true});
 if(change==='removed')await ref.delete();
 await assert.rejects(apply(manifest()),/CATALOG_CHANGED/);assert.equal((await rt.db.doc('productos/SFYRS_1000G').get()).get('marcaId'),undefined);
});
test('USER and blocked administrator cannot approve metadata',async()=>{
 await assert.rejects(applyClassification(rt,manifest(),{idToken:tokens.USER}),/UNAUTHORIZED_ADMIN/);
 await rt.db.doc('usuarios/ADMIN').update({estado:'BLOQUEADO'});await assert.rejects(apply(manifest()),/UNAUTHORIZED_PROFILE/);
});
test('subsequent product metadata changes invalidate approval and no valid reference is overwritten',async()=>{
 await rt.db.doc('productos/SFYRS_1000G').update({marcaId:'hp-real'});await assert.rejects(apply(manifest()),/PRODUCT_METADATA_CHANGED/);
});
test('SKU duplicated after comparison cannot classify an arbitrarily selected document',async()=>{
 await rt.db.doc('productos/other-real').set({codigo:'SFYRS/1000G',nombre:'Parlante Creative Pebble'});
 await assert.rejects(apply(manifest()),/PRODUCT_SKU_BECAME_AMBIGUOUS/);
});
test('new product approval is pending creation and does not create inventory',async()=>{
 await rt.db.doc('productos/SFYRS_1000G').delete();const result=await apply(manifest());assert.deepEqual(result.pendingCreation,['SFYRS_1000G']);assert.equal((await rt.db.doc('productos/SFYRS_1000G').get()).exists,false);
});
test('forged stock field, reviewer, changed rule or source trace is rejected',async()=>{
 for(const [field,value,expected] of [['field','stock',/FORBIDDEN_METADATA_FIELD/],['reviewer','OTHER',/REVIEWER_MISMATCH/],['original','{}',/APPROVAL_SOURCE_CHANGED/]]){
  const m=manifest();m.decisions[0][field]=value;await assert.rejects(apply(m),expected);
 }
 const m=manifest();m.rulesDigest='0'.repeat(64);await assert.rejects(apply(m),/RULES_CHANGED/);
});
test('controlled catalog pagination reads beyond first page once',async()=>{
 const batch=rt.db.batch();for(let i=0;i<105;i++)batch.set(rt.db.doc('marcas/extra'+String(i).padStart(3,'0')),{nombre:'Marca '+i,activo:true});await batch.commit();
 assert.equal((await readCatalog(rt)).brands.length,catalog.brands.length+105);
});
test('actual CLI reads Android dossier and only applies metadata with explicit authenticated flag',async()=>{
 const dir=await mkdtemp(join(tmpdir(),'clisa-classification-'));
 try{
  await writeFile(join(dir,'review.json'),JSON.stringify({classification:JSON.stringify(fixture.manifest)}));await writeFile(join(dir,'token'),tokens.ADMIN);
  const run=promisify(execFile),args=['cli.mjs','--classification',join(dir,'review.json'),'--output',dir];
  await run(process.execPath,args);assert.equal((await rt.db.doc('productos/SFYRS_1000G').get()).get('marcaId'),undefined);
  await run(process.execPath,[...args,'--apply-classification','true','--id-token-file',join(dir,'token')]);
  assert.equal(JSON.parse(await readFile(join(dir,'classification-result.json'),'utf8')).stockOperations,0);
 }finally{await rm(dir,{recursive:true,force:true});}
});
