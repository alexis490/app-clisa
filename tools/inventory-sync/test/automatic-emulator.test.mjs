import test,{before,beforeEach,after} from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createEmulatorRuntime } from '../runtime.mjs';
import { createAutomaticImport } from '../automatic-import.mjs';
import { readInventory } from '../source-reader.mjs';
import { createHttpServer } from '../server.mjs';
import { readHttpConfig } from '../http-config.mjs';
import { once } from 'node:events';
import XLSX from 'xlsx';

const original=readInventory(readFileSync(process.env.CLISA_EXCEL_SAMPLE??'C:/Users/TUF/Documents/stock.production.lot123 - 2000.xlsx'),'stock.xlsx');
const sample=original.products.filter(p=>!p.critical&&p.serials.length>0&&p.serials.length<400);
const actor={uid:'auto-admin',role:'ADMIN',email:'auto-admin@example.test'};let rt,api;
const source=(products)=>({...original,products:structuredClone(products),issues:[],mappingRequired:[]});
const product=n=>{const p=structuredClone(sample[n]);p.serials=p.serials.slice(0,1);p.stock=p.serials[0].quantity;p.reportedQuantity=p.stock;p.reportedSerialCount=1;return p;};
async function preview(input){let j=await api.create(input,actor);for(let tries=0;['COMPARISON','CLASSIFICATION'].includes(j.status)&&tries<100;tries++)j=await api.advance(j.id,actor);assert.equal(j.status,'PREVIEW');return j;}
async function sync(j){j=await api.confirm(j.id,actor);for(let tries=0;j.status==='SYNCING'&&tries<100;tries++)j=await api.advance(j.id,actor);assert.ok(['COMPLETED','PARTIAL'].includes(j.status));return j;}
async function seed(p,id='legacy',extra={}){
 await rt.db.doc(`productos/${id}`).set({codigo:p.code,nombre:p.name,stock:p.stock+5,cantidadRestante:777,estado:'ACTIVO',...extra});
 await rt.db.doc(`existencias/${id}-serial`).set({productoId:id,codigoProducto:p.code,codigo:p.serials[0].code,cantidad:p.stock+2,estado:'DISPONIBLE'});
 await rt.db.doc(`existencias/${id}-absent`).set({productoId:id,codigoProducto:p.code,codigo:`absent-${id}`,cantidad:3,estado:'DISPONIBLE'});
}
before(()=>{rt=createEmulatorRuntime();api=createAutomaticImport({db:rt.db});});
beforeEach(async()=>{
 for(const col of ['productos','existencias','importaciones_excel','historial_importaciones','categorias','marcas','usuarios'])await rt.db.recursiveDelete(rt.db.collection(col));
 await rt.db.doc(`usuarios/${actor.uid}`).set({role:'ADMIN',estado:'ACTIVO'});
});
after(async()=>{await rt?.close();});

test('Emulator: vista previa no escribe inventario; confirmar crea productos y series',async()=>{
 const p=product(0),j=await preview(source([p]));assert.equal((await rt.db.collection('productos').get()).size,0);assert.equal((await rt.db.collection('existencias').get()).size,0);
 assert.equal(j.preview.productsCreated,1);const end=await sync(j);assert.equal(end.status,'COMPLETED');assert.equal(end.summary.productsCreated,1);assert.equal(end.summary.seriesCreated,1);
 const stored=(await rt.db.collection('productos').get()).docs[0];assert.equal(stored.get('stock'),p.stock);assert.equal(stored.get('codigo'),p.code);
});

test('Emulator: el análisis nuevo termina en revisión sin clasificar ni permitir sincronización',async()=>{
 const good=product(0),bad=product(1);bad.critical=true;let geminiCalls=0;const collections=[];
 const tracked=new Proxy(rt.db,{get(target,key){if(key==='collection')return name=>{collections.push(name);return target.collection(name);};const value=target[key];return typeof value==='function'?value.bind(target):value;}});
 const separated=createAutomaticImport({db:tracked,apiKey:'isolated-test-key',fetcher:async()=>{geminiCalls++;throw Error('No debe clasificar durante análisis');}});
 let j=await separated.create({...source([good,bad]),rowCount:5},actor,{reviewBeforeClassification:true});
 assert.equal(j.status,'COMPARISON');assert.equal(j.analysis.productsCompared,0);
 for(let i=0;j.status==='COMPARISON'&&i<10;i++)j=await separated.advance(j.id,actor);
 assert.equal(j.status,'REVIEW_READY');assert.equal(j.cursor,2);assert.equal(j.confirmed,false);
 assert.deepEqual(j.analysis,{rowsRead:5,validProducts:1,inventoryTarget:1,inventoryChecked:1,productsCompared:2,seriesTotal:2,seriesProcessed:2,conflictsChecked:2,excludedProducts:1});
 assert.equal(geminiCalls,0);assert.equal(collections.includes('categorias'),false);assert.equal(collections.includes('marcas'),false);
 assert.equal((await rt.db.collection('productos').get()).size,0);assert.equal((await rt.db.collection('existencias').get()).size,0);
 assert.equal(j.preview.productsCreated,1);assert.equal(j.preview.productsOmitted,1);
 await assert.rejects(separated.confirm(j.id,actor),/PREVIEW_REQUIRED/);
 const repeated=await separated.advance(j.id,actor);assert.equal(repeated.status,'REVIEW_READY');assert.deepEqual(repeated.analysis,j.analysis);
 const restored=await createAutomaticImport({db:rt.db}).view(j.id,actor);assert.equal(restored.status,'REVIEW_READY');
});

test('Emulator: revisión fiel, clasificación explícita y confirmación posterior conservan los ausentes',async()=>{
 const existing=product(0),fresh=product(1),outside=product(2),blocked=product(3);blocked.critical=true;
 await seed(existing,'existing');await seed(outside,'outside');await seed(blocked,'protected');
 const outsideBefore=await rt.db.doc('productos/outside').get(),protectedBefore=await rt.db.doc('productos/protected').get();
 const input={...source([existing,fresh,blocked]),rowCount:7,issues:[{type:'IDENTITY',productCode:blocked.code,productName:blocked.name,sheet:blocked.sheet,row:blocked.row,serial:null,description:'Dato de origen apartado',reason:'Corregir origen'}]};
 let j=await api.create(input,actor,{reviewBeforeClassification:true});
 for(let i=0;j.status==='COMPARISON'&&i<10;i++)j=await api.advance(j.id,actor);
 await assert.rejects(api.classify(j.id,actor),/REVIEW_REQUIRED/);await assert.rejects(api.confirm(j.id,actor),/PREVIEW_REQUIRED/);
 j=await api.review(j.id,actor);assert.equal(j.status,'REVIEW_READY');
 assert.equal(j.review.comparison.decreased,1);assert.equal(j.review.comparison.newProducts,1);assert.equal(j.review.comparison.absent,1);
 assert.equal(j.review.uniqueProducts,3);assert.deepEqual(j.review.validation,{validRecords:4,separatedRecords:2});
 assert.equal(j.review.quality.otherErrors,1);assert.equal(j.review.issueCount,1);
 assert.deepEqual((await api.review(j.id,actor)).review,j.review);
 assert.equal((await rt.db.doc('productos/existing').get()).get('stock'),existing.stock+5);
 j=await api.classify(j.id,actor);assert.deepEqual(j.classificationProgress,{processed:0,byRules:0,byAi:0,pending:0,excluded:0,rulesProcessed:0,aiProcessed:0});assert.equal(j.status,'CLASSIFICATION');assert.equal(j.preview.productsCreated,0);
 for(let i=0;j.status==='CLASSIFICATION'&&i<10;i++)j=await api.advance(j.id,actor);
 assert.equal(j.status,'PREVIEW');assert.deepEqual(j.classificationProgress,{processed:2,byRules:0,byAi:0,pending:2,excluded:1,rulesProcessed:2,aiProcessed:0});assert.deepEqual((await api.advance(j.id,actor)).classificationProgress,j.classificationProgress);assert.equal(j.preview.productsCreated,1);assert.equal(j.preview.productsUpdated,1);
 assert.deepEqual((await api.classify(j.id,actor)).preview,j.preview);
 assert.ok((await rt.db.doc('productos/outside').get()).updateTime.isEqual(outsideBefore.updateTime));
 assert.ok((await rt.db.doc('productos/protected').get()).updateTime.isEqual(protectedBefore.updateTime));
 j=await sync(j);assert.equal(j.status,'COMPLETED');assert.equal(j.summary.productsCreated,1);
 assert.ok((await rt.db.doc('productos/outside').get()).updateTime.isEqual(outsideBefore.updateTime));
 assert.ok((await rt.db.doc('productos/protected').get()).updateTime.isEqual(protectedBefore.updateTime));
});
test('Emulator: actualiza IDs legados y series ausentes a cero; preserva metadata y productos fuera del archivo',async()=>{
 const p=product(0),out=product(1);await seed(p,'legacy',{categoriaId:'cat',marcaId:'brand',descripcion:'Descripción existente'});await seed(out,'outside');
 await rt.db.doc('categorias/cat').set({nombre:'Categoría',activo:true});await rt.db.doc('marcas/brand').set({nombre:'Marca',activo:true});
 const outside=await rt.db.doc('productos/outside').get(),outsideSerial=await rt.db.doc('existencias/outside-absent').get();
 const j=await sync(await preview(source([p])));assert.equal(j.summary.productsUpdated,1);assert.equal(j.summary.seriesUpdated,1);assert.equal(j.summary.seriesZeroed,1);
 const stored=await rt.db.doc('productos/legacy').get();assert.equal(stored.get('stock'),p.stock);assert.equal(stored.get('cantidadRestante'),777);assert.equal(stored.get('categoriaId'),'cat');assert.equal(stored.get('descripcion'),'Descripción existente');
 assert.equal((await rt.db.doc('existencias/legacy-absent').get()).get('cantidad'),0);assert.equal((await rt.db.doc('existencias/legacy-absent').get()).get('estado'),'SIN_STOCK');
 assert.ok((await rt.db.doc('productos/outside').get()).updateTime.isEqual(outside.updateTime));assert.ok((await rt.db.doc('existencias/outside-absent').get()).updateTime.isEqual(outsideSerial.updateTime));
});
test('Emulator: producto crítico queda completamente intacto y el válido continúa',async()=>{
 const bad=product(0),good=product(1);bad.critical=true;await seed(bad,'protected');const before=await rt.db.doc('existencias/protected-absent').get();
 const result=await sync(await preview(source([bad,good])));assert.equal(result.summary.productsOmitted,1);assert.equal(result.summary.productsCreated,1);assert.equal(result.summary.syncErrors,0);
 assert.ok((await rt.db.doc('existencias/protected-absent').get()).updateTime.isEqual(before.updateTime));assert.equal((await rt.db.doc('productos/protected').get()).get('stock'),bad.stock+5);
});
test('Emulator: reimportación y avance repetido no duplican ni cuentan escrituras inexistentes',async()=>{
 const input=source([product(0)]),first=await sync(await preview(input));const before=(await rt.db.collection('productos').get()).docs[0];
 const second=await sync(await preview(input));assert.equal(second.summary.productsCreated,0);assert.equal(second.summary.seriesCreated,0);assert.equal(second.summary.productsUpdated,0);assert.equal(second.summary.productsUnchanged,1);
 assert.ok((await before.ref.get()).updateTime.isEqual(before.updateTime));const repeated=await api.advance(first.id,actor);assert.deepEqual(repeated.summary,first.summary);
 assert.equal((await rt.db.collection('productos').get()).size,1);assert.equal((await rt.db.collection('existencias').get()).size,1);
});
test('Emulator: cambios después de vista previa no producen actualización parcial',async()=>{
 const p=product(0);await seed(p);const j=await preview(source([p]));await rt.db.doc('productos/legacy').update({stock:999});const before=await rt.db.doc('existencias/legacy-serial').get();
 const result=await sync(j);assert.equal(result.status,'PARTIAL');assert.equal(result.summary.syncErrors,1);assert.equal(result.summary.productsUpdated,0);assert.equal((await rt.db.doc('productos/legacy').get()).get('stock'),999);
 assert.ok((await rt.db.doc('existencias/legacy-serial').get()).updateTime.isEqual(before.updateTime));assert.equal((await rt.db.doc('existencias/legacy-absent').get()).get('cantidad'),3);
});
test('Emulator: cambio de permisos bloquea escrituras; otro usuario no puede confirmar',async()=>{
 const j=await preview(source([product(0)]));await assert.rejects(api.confirm(j.id,{uid:'otro',role:'ADMIN'}),/IMPORT_NOT_FOUND/);
 await rt.db.doc(`usuarios/${actor.uid}`).update({estado:'BLOQUEADO'});const result=await sync(j);assert.equal(result.status,'PARTIAL');assert.equal(result.summary.syncErrors,1);assert.equal((await rt.db.collection('productos').get()).size,0);
});
test('Emulator: fallo de escritura revierte todo el producto y continúa con el siguiente',async()=>{
 const p=product(0),other=product(1);await seed(p);const j=await preview(source([p,other]));let failed=false;
 const faulty=new Proxy(rt.db,{get(target,key){if(key==='runTransaction')return callback=>target.runTransaction(tx=>callback(new Proxy(tx,{get(t,k){if(k==='update')return(ref,...args)=>{if(ref.path==='existencias/legacy-serial'&&!failed){failed=true;throw Error('injected-write-error');}return t.update(ref,...args);};const v=t[k];return typeof v==='function'?v.bind(t):v;}})));const v=target[key];return typeof v==='function'?v.bind(target):v;}});
 const broken=createAutomaticImport({db:faulty});await broken.confirm(j.id,actor);const end=await broken.advance(j.id,actor);assert.equal(end.status,'PARTIAL');assert.equal(end.summary.syncErrors,1);assert.equal(end.summary.productsCreated,1);
 assert.equal((await rt.db.doc('productos/legacy').get()).get('stock'),p.stock+5);assert.equal((await rt.db.doc('existencias/legacy-absent').get()).get('cantidad'),3);
});
test('Emulator: servicio recreado recupera propuesta y confirmación sin depender de memoria',async()=>{
 let j=await preview(source([product(0)]));await api.confirm(j.id,actor);api=createAutomaticImport({db:rt.db});j=await api.view(j.id,actor);assert.equal(j.status,'SYNCING');j=await api.advance(j.id,actor);assert.equal(j.status,'COMPLETED');assert.equal(j.summary.productsCreated,1);
});
test('Emulator: categoría desactivada después del análisis deja clasificación pendiente y permite stock válido',async()=>{
 const p=product(0);await rt.db.doc('categorias/cat').set({nombre:p.name,activo:true});const j=await preview(source([p]));
 await rt.db.doc('categorias/cat').update({activo:false});const result=await sync(j);assert.equal(result.status,'COMPLETED');assert.equal(result.summary.productsCreated,1);assert.equal(result.summary.classificationPending,1);
 const stored=(await rt.db.collection('productos').get()).docs[0];assert.equal(stored.get('stock'),p.stock);assert.equal(stored.get('categoriaId'),undefined);
});
test('Emulator: producto real de 629 series se escribe íntegro en una transacción',async()=>{
 const large=original.products.find(p=>!p.critical&&p.serials.length===629);assert.ok(large);
 const result=await sync(await preview(source([large])));assert.equal(result.status,'COMPLETED');assert.equal(result.summary.productsCreated,1);assert.equal(result.summary.seriesCreated,629);assert.equal(result.summary.productsOmitted,0);
 assert.equal((await rt.db.collection('existencias').get()).size,629);assert.equal((await rt.db.collection('productos').get()).docs[0].get('stock'),large.stock);
});
test('Emulator HTTP: archivo derivado de fuente real recorre análisis, confirmación y resultados',async t=>{
 const p=product(0),wb=XLSX.utils.book_new();XLSX.utils.book_append_sheet(wb,XLSX.utils.aoa_to_sheet([['SKU','Nombre','Serie','Cantidad'],[p.code,p.name,p.serials[0].code,p.serials[0].quantity]]),'Prueba');
 const config={...readHttpConfig({}).config,globalLimit:500,ipLimit:100,uidLimit:100};
 const firebase={verifyIdToken:async()=>({...actor,admin:true}),getUser:async()=>({email:actor.email,customClaims:{role:'ADMIN',admin:true}}),readProfile:async()=>({role:'ADMIN',estado:'ACTIVO'})};
 const server=createHttpServer({config,firebase,automaticImport:api});server.listen(0,'127.0.0.1');await once(server,'listening');t.after(()=>{server.closeAllConnections();return new Promise(r=>server.close(r));});
 const url=`http://127.0.0.1:${server.address().port}`,headers={Authorization:'Bearer '+'a'.repeat(40)};
 const data=new FormData();data.append('contract',new Blob(['{}']),'options.json');data.append('excel',new Blob([XLSX.write(wb,{type:'buffer',bookType:'xlsx'})]),'derivado.xlsx');
 let response=await fetch(url+'/v1/admin/excel-imports',{method:'POST',headers,body:data});assert.equal(response.status,200,await response.clone().text());let j=await response.json();
 async function action(name){const r=await fetch(`${url}/v1/admin/excel-imports/${j.id}/${name}`,{method:'POST',headers});assert.equal(r.status,200,await r.clone().text());j=await r.json();}
 for(let i=0;j.status!=='PREVIEW'&&i<10;i++)await action('advance');assert.equal(j.status,'PREVIEW');assert.equal((await rt.db.collection('productos').get()).size,0);
 await action('confirm');for(let i=0;j.status==='SYNCING'&&i<10;i++)await action('advance');assert.equal(j.status,'COMPLETED');assert.equal(j.summary.productsCreated,1);assert.equal(j.summary.seriesCreated,1);
});
test('Emulator: avances concurrentes no duplican recibos ni operaciones',async()=>{
 const j=await preview(source([product(0)]));await api.confirm(j.id,actor);
 const attempts=await Promise.allSettled([api.advance(j.id,actor),api.advance(j.id,actor)]);assert.ok(attempts.some(r=>r.status==='fulfilled'));
 const end=await api.view(j.id,actor);assert.equal(end.summary.productsCreated,1);assert.equal(end.summary.seriesCreated,1);assert.equal((await rt.db.collection('productos').get()).size,1);
});
