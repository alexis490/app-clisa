import test,{before,beforeEach,after} from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync,writeFileSync} from 'node:fs';
import {randomUUID} from 'node:crypto';
import {createEmulatorRuntime} from '../runtime.mjs';
import {createAutomaticImport} from '../automatic-import.mjs';
import {readInventory} from '../source-reader.mjs';
import {historyState} from '../import-history.mjs';
const original=readInventory(readFileSync('C:/Users/TUF/Documents/stock.production.lot123.xlsx'),'stock.xlsx');
const eligible=original.products.filter(p=>!p.critical&&p.serials.length);
const actor={uid:'history-admin',role:'ADMIN',email:'auth-admin@example.test'};
let rt,api;
const product=n=>{const p=structuredClone(eligible[n]);p.serials=p.serials.slice(0,1);p.stock=p.serials[0].quantity;p.reportedQuantity=p.stock;p.reportedSerialCount=1;return p;};
const source=ps=>({...original,products:ps,issues:[],mappingRequired:[]});
async function prepared(ps=[product(0)]){let j=await api.create(source(ps),actor);while(['COMPARISON','CLASSIFICATION'].includes(j.status))j=await api.advance(j.id,actor);return j;}
async function finish(j,service=api){await service.confirm(j.id,actor);j=await service.advance(j.id,actor);return j;}
before(()=>{rt=createEmulatorRuntime();api=createAutomaticImport({db:rt.db});});
beforeEach(async()=>{for(const c of ['productos','existencias','importaciones_excel','historial_importaciones','categorias','marcas','usuarios'])await rt.db.recursiveDelete(rt.db.collection(c));await rt.db.doc(`usuarios/${actor.uid}`).set({role:'ADMIN',estado:'ACTIVO'});});
after(async()=>rt.close());
test('historial: estado deriva de resultados reales',()=>{
 const base={productsCreated:1,productsUpdated:0,productsUnchanged:0,syncErrors:0,productsOmitted:0,classificationPending:0};
 assert.equal(historyState(base),'COMPLETADO');assert.equal(historyState(base,1),'COMPLETADO_CON_OBSERVACIONES');assert.equal(historyState({...base,classificationPending:1}),'COMPLETADO_CON_OBSERVACIONES');
 assert.equal(historyState({...base,productsOmitted:1}),'COMPLETADO_CON_OBSERVACIONES');assert.equal(historyState({...base,syncErrors:1}),'COMPLETADO_CON_OBSERVACIONES');
 assert.equal(historyState({...base,productsCreated:0,syncErrors:1}),'FALLIDO');
});
test('historial: colección implícita, Timestamp, siete campos, reintento y correo inmutables',async()=>{
 let j=await prepared();assert.equal((await rt.db.collection('historial_importaciones').get()).size,0);assert.equal(j.history,null);
 await rt.db.doc(`importaciones_excel/${j.id}/productos/00000`).update({'plan.classification.pending':[]});
 j=await finish(j);assert.equal(j.history.estado,'COMPLETADO');
 const ref=rt.db.doc(`historial_importaciones/${j.id}`),d=await ref.get();assert.ok(d.exists);assert.equal(typeof d.get('fecha').toMillis(),'number');
 assert.deepEqual(Object.keys(d.data()).sort(),['idImportacion','archivo','fecha','usuarioUid','usuario','destino','estado'].sort());
 assert.equal(d.get('idImportacion'),j.id);assert.equal(d.get('archivo'),'stock.xlsx');assert.equal(d.get('usuario'),actor.email);assert.equal(d.get('usuarioUid'),actor.uid);assert.equal(d.get('destino'),'Firebase Firestore');
 assert.equal(j.history.fecha,d.get('fecha').toDate().toISOString());
 writeFileSync(new URL('../../../docs/importacion-maestro/history-emulator-result.json',import.meta.url),JSON.stringify(j,null,2));
 for(let i=0;i<2;i++){await api.confirm(j.id,{...actor,email:'changed@example.test'});await api.advance(j.id,actor);}
 const after=await ref.get();assert.ok(after.updateTime.isEqual(d.updateTime));assert.equal((await rt.db.collection('historial_importaciones').get()).size,1);
 await assert.rejects(api.view(j.id,{...actor,uid:'intruso'}),/IMPORT_NOT_FOUND/);
});
test('historial: fallos parciales y totales conservan el estado sin inventar éxito',async()=>{
 for(const mixed of [true,false]){
  let j=await prepared(mixed?[product(0),product(1)]:[product(0)]);
  const ref=rt.db.doc(`importaciones_excel/${j.id}/productos/00000`),u=await ref.get();
  // Make the saved baseline stale so the real per-product validation rejects it.
  await ref.update({'plan.baseline':[{path:'productos/missing',version:'1:0'}]});
  j=await finish(j);assert.equal(j.status,'PARTIAL');assert.equal(j.summary.syncErrors,1);
  assert.equal(j.history.estado,mixed?'COMPLETADO_CON_OBSERVACIONES':'FALLIDO');
  assert.equal((await rt.db.doc(`historial_importaciones/${j.id}`).get()).get('estado'),j.history.estado);
 }
});
test('historial: fallo al guardar impide cierre falso y se recupera sin repetir escrituras',async()=>{
 let j=await prepared(),failed=false;
 const faulty=new Proxy(rt.db,{get(t,k){if(k==='runTransaction')return cb=>t.runTransaction(tx=>cb(new Proxy(tx,{get(x,key){if(key==='create')return(ref,...args)=>{if(ref.path.startsWith('historial_importaciones/')&&!failed){failed=true;throw Error('injected-history-failure');}return x.create(ref,...args);};const v=x[key];return typeof v==='function'?v.bind(x):v;}})));const v=t[k];return typeof v==='function'?v.bind(t):v;}});
 const broken=createAutomaticImport({db:faulty});await broken.confirm(j.id,actor);await assert.rejects(broken.advance(j.id,actor),/injected-history-failure/);
 const draft=await rt.db.doc(`importaciones_excel/${j.id}`).get();assert.equal(draft.get('status'),'SYNCING');assert.equal(draft.get('cursor'),1);assert.equal((await rt.db.collection('historial_importaciones').get()).size,0);
 const before=(await rt.db.collection('productos').get()).docs[0];j=await api.advance(j.id,actor);assert.equal(j.status,'COMPLETED');
 assert.ok((await before.ref.get()).updateTime.isEqual(before.updateTime));assert.equal(j.summary.productsCreated,1);assert.equal((await rt.db.collection('historial_importaciones').get()).size,1);
});
test('historial: rechaza confirmación sin email Auth y no registra borradores',async()=>{
 const j=await prepared();await assert.rejects(api.confirm(j.id,{uid:actor.uid,role:actor.role}),/AUTH_EMAIL_REQUIRED/);
 assert.equal((await rt.db.collection('historial_importaciones').get()).size,0);assert.equal((await rt.db.collection('productos').get()).size,0);
});
test('idempotencia: mismo ID de carga, propietario, hash y contenido; no reinicia el expediente',async()=>{
 const id=randomUUID(),input=source([product(0)]);let j=await api.create(input,actor,{importId:id});assert.equal(j.id,id);
 j=await api.advance(j.id,actor);const cursor=j.cursor,status=j.status;
 const repeated=await api.create(input,actor,{importId:id});assert.equal(repeated.cursor,cursor);assert.equal(repeated.status,status);
 assert.equal((await rt.db.collection('importaciones_excel').get()).size,1);assert.equal((await rt.db.doc(`importaciones_excel/${id}`).collection('productos').get()).size,1);
 await assert.rejects(api.create({...input,fileSha256:'different'},actor,{importId:id}),/IMPORT_CHANGED/);
 await assert.rejects(api.create({...input,products:[product(1)]},actor,{importId:id}),/IMPORT_CHANGED/);
 await assert.rejects(api.create(input,{...actor,uid:'intruso'},{importId:id}),/IMPORT_NOT_FOUND/);
});
test('idempotencia: una carga interrumpida en STAGING se recupera con el mismo ID',async()=>{
 const id=randomUUID(),input=source([product(0)]);let failed=false;
 const faulty=new Proxy(rt.db,{get(t,k){if(k==='runTransaction')return cb=>t.runTransaction(tx=>cb(new Proxy(tx,{get(x,key){if(key==='set')return(ref,...args)=>{if(ref.path.includes('/productos/')&&!failed){failed=true;throw Error('injected-staging-failure');}return x.set(ref,...args);};const v=x[key];return typeof v==='function'?v.bind(x):v;}})));const v=t[k];return typeof v==='function'?v.bind(t):v;}});
 await assert.rejects(createAutomaticImport({db:faulty}).create(input,actor,{importId:id}),/injected-staging-failure/);
 assert.equal((await rt.db.doc(`importaciones_excel/${id}`).get()).get('status'),'STAGING');
 const j=await api.create(input,actor,{importId:id});assert.equal(j.id,id);assert.equal(j.status,'COMPARISON');assert.equal((await rt.db.collection('importaciones_excel').get()).size,1);
});
test('historial: avances concurrentes crean un único registro',async()=>{
 const j=await prepared();await api.confirm(j.id,actor);const results=await Promise.allSettled([api.advance(j.id,actor),api.advance(j.id,actor)]);assert.ok(results.some(r=>r.status==='fulfilled'));
 assert.equal((await rt.db.collection('historial_importaciones').get()).size,1);assert.equal((await api.view(j.id,actor)).summary.productsCreated,1);
});

test('idempotencia: conserva datos de diagnóstico sin exigir enteros al hash de origen',async()=>{
 const p=product(0);p.reportedSerialCount=Number.POSITIVE_INFINITY;p.critical=true;
 const j=await api.create(source([p]),actor,{importId:randomUUID(),reviewBeforeClassification:true});assert.equal(j.status,'COMPARISON');
 assert.equal((await rt.db.doc(`importaciones_excel/${j.id}/productos/00000`).get()).get('source.critical'),true);
 assert.equal((await api.advance(j.id,actor)).analysis.excludedProducts,1);
});
