import test,{before,beforeEach,after} from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync,writeFileSync} from 'node:fs';
import {createEmulatorRuntime} from '../runtime.mjs';
import {createAutomaticImport} from '../automatic-import.mjs';
import {readInventory} from '../source-reader.mjs';
const source=readInventory(readFileSync('C:/Users/TUF/Documents/stock.production.lot123.xlsx'),'stock.xlsx');
const valid=source.products.filter(p=>!p.critical&&p.serials.length);
const actor={uid:'export-admin',email:'export-admin@example.test',role:'ADMIN'};let rt,api;
function p(n){const value=structuredClone(valid[n]);value.serials=value.serials.slice(0,1);value.stock=value.serials[0].quantity;value.reportedQuantity=value.stock;value.reportedSerialCount=1;return value;}
before(()=>{rt=createEmulatorRuntime();api=createAutomaticImport({db:rt.db});});
after(async()=>rt.close());
beforeEach(async()=>{for(const col of ['productos','existencias','importaciones_excel','historial_importaciones','categorias','marcas','usuarios'])await rt.db.recursiveDelete(rt.db.collection(col));await rt.db.doc(`usuarios/${actor.uid}`).set({role:'ADMIN',estado:'ACTIVO'});});
async function finish(products,issues=[]){let j=await api.create({...source,products,issues},actor);while(['COMPARISON','CLASSIFICATION'].includes(j.status))j=await api.advance(j.id,actor);await api.confirm(j.id,actor);while(j.status!=='COMPLETED'&&j.status!=='PARTIAL')j=await api.advance(j.id,actor);return j;}
test('exportación paginada solo lee resultados guardados; produce evidencia para el XLSX Android',async()=>{
 const existing=p(0),fresh=p(1),bad=p(2),failed=p(3);bad.critical=true;
 await rt.db.doc('productos/existing').set({codigo:existing.code,nombre:existing.name,stock:existing.stock+2,estado:'ACTIVO'});
 await rt.db.doc('existencias/existing').set({productoId:'existing',codigoProducto:existing.code,codigo:existing.serials[0].code,cantidad:existing.stock+2,estado:'DISPONIBLE'});
 let j=await api.create({...source,products:[existing,fresh,bad,failed],issues:[{sheet:bad.sheet,row:bad.row,productCode:bad.code,productName:bad.name,serial:null,type:'INVALID_QUANTITY',description:'Cantidad negativa de prueba',reason:'Producto apartado'}]},actor);
 while(['COMPARISON','CLASSIFICATION'].includes(j.status))j=await api.advance(j.id,actor);
 await rt.db.doc(`importaciones_excel/${j.id}/productos/00003`).update({'plan.baseline':[{path:'productos/missing',version:'1:0'}]});
 await api.confirm(j.id,actor);j=await api.advance(j.id,actor);assert.equal(j.status,'PARTIAL');
 // A later external change must not replace historical stock in the export.
 await rt.db.doc('productos/existing').update({stock:999});
 const before=await rt.db.doc('productos/existing').get(),draft=await rt.db.doc(`importaciones_excel/${j.id}`).get(),history=await rt.db.doc(`historial_importaciones/${j.id}`).get();
 const pages=[];for(const section of ['products','issues']){let offset=0;do{const page=await api.exportPage(j.id,actor,section,offset);pages.push(page);offset=page.nextOffset;}while(offset!==null);}
 const products=pages[0].data.products;assert.equal(products[0].beforeStock,existing.stock+2);assert.equal(products[0].newStock,existing.stock);assert.equal(products[1].previousExists,false);assert.equal(products[2].state,'OMITIDO');assert.equal(products[3].newStock,null);assert.equal(products[3].state,'FALLIDO');
 assert.ok(pages[0].data.operations.some(o=>o.collection==='existencias'));assert.equal(pages[1].data.issues.length,1);
 assert.ok((await before.ref.get()).updateTime.isEqual(before.updateTime));assert.ok((await draft.ref.get()).updateTime.isEqual(draft.updateTime));assert.ok((await history.ref.get()).updateTime.isEqual(history.updateTime));
 await assert.rejects(api.exportPage(j.id,{...actor,uid:'intruso'}),/IMPORT_NOT_FOUND/);
 await assert.rejects(api.exportPage(j.id,actor,'products',-1),/INVALID_OFFSET/);
 writeFileSync(new URL('../../../docs/importacion-maestro/export-fixture.json',import.meta.url),JSON.stringify(pages,null,2));
});
test('solo permite exportar terminados y detecta evidencia faltante',async()=>{
 let j=await api.create({...source,products:[p(0),p(1)],issues:[]},actor);await assert.rejects(api.exportPage(j.id,actor),/EXPORT_NOT_READY/);
 while(['COMPARISON','CLASSIFICATION'].includes(j.status))j=await api.advance(j.id,actor);await api.confirm(j.id,actor);j=await api.advance(j.id,actor);
 await rt.db.doc(`historial_importaciones/${j.id}`).delete();
 await rt.db.doc(`importaciones_excel/${j.id}/productos/00000`).delete();const page=await api.exportPage(j.id,actor);assert.equal(page.complete,false);assert.equal(page.data.products.length,1);assert.equal(page.data.products[0].code,p(1).code);assert.equal(page.job.history,null);assert.equal(page.job.responsibleUser,actor.email);assert.ok(page.job.completedAt.endsWith('Z'));
});
