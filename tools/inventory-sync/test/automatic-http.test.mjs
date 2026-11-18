import test from 'node:test';
import assert from 'node:assert/strict';
import { once } from 'node:events';
import { readFile } from 'node:fs/promises';
import { createHttpServer } from '../server.mjs';
import { readHttpConfig } from '../http-config.mjs';
import { readInventory } from '../source-reader.mjs';

const headers={Authorization:'Bearer '+'a'.repeat(40)};
const fake={verifyIdToken:async()=>({uid:'ADMIN',role:'ADMIN',admin:true}),getUser:async()=>({customClaims:{role:'ADMIN',admin:true}}),readProfile:async()=>({role:'ADMIN',estado:'ACTIVO'})};
async function running(t,{firebase=fake,service,logger=()=>{}}={}){
 const config={...readHttpConfig({}).config,globalLimit:500,ipLimit:100,uidLimit:100};
 const server=createHttpServer({config,firebase,automaticImport:service,logger});server.listen(0,'127.0.0.1');await once(server,'listening');
 t.after(()=>{server.closeAllConnections();return new Promise(r=>server.close(r));});return `http://127.0.0.1:${server.address().port}`;
}
test('Nuevo canal conserva verificación administrativa independiente',async t=>{
 let called=0;const service={view:async()=>{called++;return {};}};
 const url=await running(t,{service});assert.equal((await fetch(url+'/v1/admin/excel-imports/x')).status,401);assert.equal(called,0);
 const denied=await running(t,{service,firebase:{...fake,readProfile:async()=>({role:'ADMIN',estado:'BLOQUEADO'})}});assert.equal((await fetch(denied+'/v1/admin/excel-imports/x',{headers})).status,403);assert.equal(called,0);
});

test('HTTP transmite la pausa de revisión antes de clasificar',async t=>{
 let captured;const url=await running(t,{service:{create:async(source,actor,options)=>{captured=options;return {status:'COMPARISON'};}}});
 const data=new FormData();data.append('contract',new Blob([JSON.stringify({reviewBeforeClassification:true})]),'options.json');
 data.append('excel',new Blob([await readFile(new URL('../../../app/src/androidTest/assets/upload-source.xlsx',import.meta.url))]),'stock.xlsx');
 const response=await fetch(url+'/v1/admin/excel-imports',{method:'POST',headers,body:data});assert.equal(response.status,200);assert.deepEqual(captured,{reviewBeforeClassification:true});
});

test('Comprobar formato no crea expediente ni compara ni clasifica productos',async t=>{
 let called=0;const service=Object.fromEntries(['create','advance','confirm','view'].map(key=>[key,async()=>{called++;throw Error('No debe llamarse');}]));
 const url=await running(t,{service});
 assert.equal((await fetch(url+'/v1/admin/excel-format',{method:'POST'})).status,401);
 const bytes=await readFile(new URL('../../../app/src/androidTest/assets/upload-source.xlsx',import.meta.url));
 const data=new FormData();data.append('contract',new Blob(['{}']),'options.json');data.append('excel',new Blob([bytes]),'stock.xlsx');
 const response=await fetch(url+'/v1/admin/excel-format',{method:'POST',headers,body:data});
 assert.equal(response.status,200,await response.clone().text());assert.equal((await response.json()).ready,true);assert.equal(called,0);
});

test('La carga conserva la conexión durante una preparación de más de diez segundos',async t=>{
 const logs=[];
 const url=await running(t,{logger:e=>logs.push(e),service:{create:async source=>{
   await new Promise(resolve=>setTimeout(resolve,11000));return {status:'COMPARISON',total:source.products.length};
 }}});
 const bytes=await readFile(new URL('../../../app/src/androidTest/assets/upload-source.xlsx',import.meta.url));
 const data=new FormData();data.append('contract',new Blob(['{}']),'options.json');data.append('excel',new Blob([bytes]),'stock.production.lot123.xlsx');
 const response=await fetch(url+'/v1/admin/excel-imports',{method:'POST',headers,body:data});
 assert.equal(response.status,200,await response.clone().text());assert.equal((await response.json()).total,366);
 assert.ok(logs.some(e=>e.event==='http_request'&&e.method==='POST'&&e.status===200));
 assert.ok(logs.some(e=>e.event==='http_request_started'&&e.route==='excel-upload'));
 assert.ok(logs.some(e=>e.event==='http_request'&&e.phase==='saving-analysis'));
 assert.equal(JSON.stringify(logs).includes(headers.Authorization),false);
 assert.equal(JSON.stringify(logs).includes('stock.production.lot123.xlsx'),false);
});
test('HTTP analiza bytes originales del Excel autorizado en Worker y no confirma automáticamente',async t=>{
 let received,confirmed=0;const service={create:async(source,actor)=>{received={source,actor};return {id:'job',status:'COMPARISON'};},confirm:async()=>{confirmed++;return {};}};
 const url=await running(t,{service});const data=new FormData();data.append('contract',new Blob(['{}']),'options.json');data.append('excel',new Blob([await readFile(process.env.CLISA_EXCEL_SAMPLE??'C:/Users/TUF/Documents/stock.production.lot123 - 2000.xlsx')]),'stock.xlsx');
 const response=await fetch(url+'/v1/admin/excel-imports',{method:'POST',headers,body:data});assert.equal(response.status,200,await response.clone().text());assert.equal(received.source.products.length,1814);assert.equal(received.actor.uid,'ADMIN');assert.equal(confirmed,0);
});
test('HTTP solicita mapeo solo cuando faltan encabezados; no persiste propuesta parcial',async t=>{
 const source=readInventory(await readFile(process.env.CLISA_EXCEL_SAMPLE??'C:/Users/TUF/Documents/stock.production.lot123 - 2000.xlsx'),'stock.xlsx'),p=source.products.find(p=>!p.critical&&p.serials.length);
 const csv=[['a','b','c','d'],[p.code,p.name,p.serials[0].code,p.serials[0].quantity]].map(row=>row.map(v=>'"'+String(v).replaceAll('"','""')+'"').join(',')).join('\n');
 let created=0;const url=await running(t,{service:{create:async()=>{created++;return {};}}});const data=new FormData();data.append('contract',new Blob(['{}']),'options.json');data.append('excel',new Blob([csv]),'stock.csv');
 const response=await fetch(url+'/v1/admin/excel-imports',{method:'POST',headers,body:data});assert.equal(response.status,200);assert.equal((await response.json()).status,'MAPPING_REQUIRED');assert.equal(created,0);
});

test('El Excel de 175 KB del usuario se acepta y conserva las incidencias por producto',async t=>{
 const bytes=await readFile(new URL('../../../app/src/androidTest/assets/upload-source.xlsx',import.meta.url));
 let received;const url=await running(t,{service:{create:async source=>{received=source;return {status:'COMPARISON',total:source.products.length};}}});
 const data=new FormData();data.append('contract',new Blob(['{}']),'options.json');data.append('excel',new Blob([bytes]),'stock.production.lot123.xlsx');
 const response=await fetch(url+'/v1/admin/excel-imports',{method:'POST',headers,body:data});
 assert.equal(response.status,200,await response.clone().text());assert.equal((await response.json()).status,'COMPARISON');
 assert.equal(received.bytes,179937);assert.equal(received.products.length,366);assert.equal(received.products.filter(p=>!p.critical).length,348);
 assert.equal(received.issues.length,35);assert.equal(received.mappingRequired.length,0);
});
test('HTTP consulta, resumen, clasificación y confirmación son acciones separadas',async t=>{
 const actions=[];const states={view:'PREVIEW',advance:'COMPARISON',review:'REVIEW_READY',classify:'CLASSIFICATION',confirm:'SYNCING'};
 const service=Object.fromEntries(Object.keys(states).map(k=>[k,async(id,actor)=>{actions.push([k,id,actor.uid]);return {status:states[k]};}]));
 const url=await running(t,{service});assert.equal((await fetch(url+'/v1/admin/status',{headers})).status,200);
 for(const action of [null,'advance','review','classify','confirm']){const response=await fetch(url+'/v1/admin/excel-imports/abc'+(action?'/'+action:''),{method:action?'POST':'GET',headers});assert.equal(response.status,200);assert.equal((await response.json()).status,states[action??'view']);}
 assert.deepEqual(actions.map(a=>a[0]),['view','advance','review','classify','confirm']);
});
test('Canal automático no hereda el tope antiguo de 20 peticiones y conserva límite por UID',async t=>{
 let calls=0;const url=await running(t,{service:{advance:async()=>{calls++;return {status:'COMPARISON'};}}});
 for(let i=0;i<91;i++){const r=await fetch(url+'/v1/admin/excel-imports/abc/advance',{method:'POST',headers});assert.equal(r.status,i<90?200:429);}
 assert.equal(calls,90);
});


test('Historial: importId se conserva y la identidad procede de Auth, no del contrato ni del email del token',async t=>{
 let captured;const id='e8244195-b626-46b6-8d64-7df84b44f6e3';
 const firebase={...fake,verifyIdToken:async()=>({uid:'ADMIN',role:'ADMIN',admin:true,email:'stale@example.test'}),getUser:async()=>({email:'authoritative@example.test',customClaims:{role:'ADMIN',admin:true}})};
 const url=await running(t,{firebase,service:{create:async(source,actor,options)=>{captured={actor,options};return {status:'COMPARISON'};}}});
 const data=new FormData();data.append('contract',new Blob([JSON.stringify({importId:id,usuarioUid:'intruso',usuario:'forged@example.test',reviewBeforeClassification:true})]),'options.json');
 data.append('excel',new Blob([await readFile(new URL('../../../app/src/androidTest/assets/upload-source.xlsx',import.meta.url))]),'stock.xlsx');
 const r=await fetch(url+'/v1/admin/excel-imports',{method:'POST',headers,body:data});assert.equal(r.status,200);
 assert.deepEqual(captured.actor,{uid:'ADMIN',role:'ADMIN',email:'authoritative@example.test'});assert.deepEqual(captured.options,{reviewBeforeClassification:true,importId:id});
 assert.equal((await fetch(url+'/v1/admin/historial_importaciones',{method:'POST',headers})).status,404);
});

test('Exportación solo GET, con token y rol vigentes, sin llamadas de sincronización',async t=>{
 const calls=[];const url=await running(t,{service:{exportPage:async(...args)=>{calls.push(args);return {schemaVersion:1};}}});
 assert.equal((await fetch(url+'/v1/admin/excel-imports/x/export')).status,401);
 const response=await fetch(url+'/v1/admin/excel-imports/x/export?section=issues&offset=3',{headers});assert.equal(response.status,200);
 assert.equal(calls.length,1);assert.equal(calls[0][0],'x');assert.equal(calls[0][1].uid,'ADMIN');assert.equal(calls[0][2],'issues');assert.equal(calls[0][3],3);
 assert.equal((await fetch(url+'/v1/admin/excel-imports/x/export',{method:'POST',headers})).status,404);assert.equal(calls.length,1);
 const denied=await running(t,{firebase:{...fake,readProfile:async()=>({role:'ADMIN',estado:'BLOQUEADO'})},service:{exportPage:async()=>{throw Error('No debe ejecutarse');}}});
 assert.equal((await fetch(denied+'/v1/admin/excel-imports/x/export',{headers})).status,403);
});
