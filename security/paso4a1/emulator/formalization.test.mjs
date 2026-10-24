import { initializeApp,deleteApp } from 'firebase/app';
import { before,after,beforeEach,test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { initializeTestEnvironment,assertSucceeds,assertFails } from '@firebase/rules-unit-testing';
import { doc,getDoc,getDocs,collection,setDoc,updateDoc,deleteDoc,writeBatch,runTransaction,serverTimestamp,Timestamp,getFirestore,connectFirestoreEmulator,terminate } from 'firebase/firestore';
assert.equal(process.env.FIRESTORE_EMULATOR_HOST,'127.0.0.1:8088');
assert.equal(process.env.GCLOUD_PROJECT,'demo-clisa-formalizacion');
let env;
const claims=role=>({role,email:`${role}@example.test`,admin:role!=='USER',superAdmin:role==='SUPER_ADMIN'});
const db=(role='ADMIN',uid=role,token=claims(role))=>env.authenticatedContext(uid,token).firestore();
const initial={estadoFormalizacion:'PENDIENTE',formalizadoEn:null,formalizadoPor:null};
const record=(role='ADMIN',id='existing')=>({registroId:id,tipo:'SALIDA',estado:'COMPLETADO',productoId:'p-real',codigoProducto:'SKU/1',nombreProducto:'Local ficticio',
 existenciaId:'e-real',codigoExistencia:'0001',cantidad:1,tiendaOrigenId:'clisa',tiendaOrigenNombre:'CLISA',tiendaDestinoId:'toqen',tiendaDestinoNombre:'TOQEN',
 registradoPorId:role,registradoPorNombre:role,registradoPorEmail:`${role}@example.test`,recibidoPor:'Receptor local',estadoPago:'Pendiente',fechaHora:Timestamp.fromMillis(100)});
const formalUpdate=uid=>({estadoFormalizacion:'COMPLETADO',formalizadoPor:uid,formalizadoEn:serverTimestamp()});
before(async()=> {env=await initializeTestEnvironment({projectId:'demo-clisa-formalizacion',firestore:{host:'127.0.0.1',port:8088,
 rules:await readFile(process.env.CLISA_FORMALIZATION_RULES || new URL('../../formalizacion-traspasos/firestore.proposed.rules',import.meta.url),'utf8')}});});
after(async()=>{await env?.cleanup();});
beforeEach(async()=>{
 await env.clearFirestore();
 await env.withSecurityRulesDisabled(async c=>{
  const f=c.firestore(),b=writeBatch(f);
  for(const role of ['USER','ADMIN','SUPER_ADMIN'])b.set(doc(f,'usuarios',role),{role,estado:'ACTIVO',uid:role,displayName:role});
  b.set(doc(f,'usuarios','blocked'),{role:'ADMIN',estado:'BLOQUEADO'});
  b.set(doc(f,'registros','existing'),{...record(),...initial});
  b.set(doc(f,'registros','legacy'),record('USER','legacy'));
  b.set(doc(f,'productos','p-real'),{codigo:'SKU/1',nombre:'Local ficticio',estado:'ACTIVO',stock:5,ultimaSalidaId:'existing'});
  b.set(doc(f,'existencias','e-real'),{codigo:'0001',productoId:'p-real',codigoProducto:'SKU/1',estado:'DISPONIBLE',cantidad:5,ultimaSalidaId:'existing'});
  b.set(doc(f,'tiendas','clisa'),{nombre:'CLISA',estado:'ACTIVA'});b.set(doc(f,'tiendas','toqen'),{nombre:'TOQEN',estado:'ACTIVA'});
  await b.commit();
 });
});
async function confirm(f,uid,id='existing') {
 try { return await runTransaction(f,async tx=>{
  const profile=await tx.get(doc(f,'usuarios',uid));
  if(!['ADMIN','SUPER_ADMIN'].includes(profile.data()?.role)||profile.data()?.estado!=='ACTIVO')throw Error('NOT_AUTHORIZED');
  const target=doc(f,'registros',id),r=await tx.get(target);
  if(!r.exists())throw Error('MISSING_RECORD');
  if(r.data().estadoFormalizacion==='COMPLETADO')return 'ALREADY_COMPLETED';
  tx.update(target,formalUpdate(uid));return 'CONFIRMED';
 }); } catch(error) {
  if(!['permission-denied','aborted'].includes(error.code))throw error;
  const profile=(await getDoc(doc(f,'usuarios',uid))).data();
  if(!['ADMIN','SUPER_ADMIN'].includes(profile?.role)||profile.estado!=='ACTIVO')throw error;
  const saved=(await getDoc(doc(f,'registros',id))).data();
  if(saved?.estadoFormalizacion==='COMPLETADO'&&saved.formalizadoEn instanceof Timestamp&&typeof saved.formalizadoPor==='string'&&saved.formalizadoPor.length>0)return 'ALREADY_COMPLETED';
  throw error;
 }
}
function salida(f,role,extras=initial,id='new-salida') {
 const b=writeBatch(f);
 b.set(doc(f,'registros',id),{...record(role,id),...extras,fechaHora:serverTimestamp()});
 b.update(doc(f,'productos','p-real'),{stock:4,ultimaSalidaId:id,actualizadoEn:serverTimestamp()});
 b.update(doc(f,'existencias','e-real'),{cantidad:4,estado:'DISPONIBLE',ultimaSalidaId:id,actualizadoEn:serverTimestamp()});
 return b.commit();
}
for(const role of ['USER','ADMIN','SUPER_ADMIN'])test(`${role}: atomic SALIDA creates PENDIENTE with null audit and original stock deduction`,async()=>{
 const f=db(role);await assertSucceeds(salida(f,role));const r=(await getDoc(doc(f,'registros','new-salida'))).data();
 assert.equal(r.estadoFormalizacion,'PENDIENTE');assert.equal(r.formalizadoEn,null);assert.equal(r.formalizadoPor,null);assert.equal(r.estadoPago,'Pendiente');
 assert.equal((await getDoc(doc(f,'productos','p-real'))).data().stock,4);
 assert.equal((await getDoc(doc(f,'existencias','e-real'))).data().cantidad,4);
});
test('old client SALIDA remains valid without migration; absent formalization remains absent',async()=>{
 const f=db('USER');await assertSucceeds(salida(f,'USER',{}));assert.ok(!('estadoFormalizacion' in (await getDoc(doc(f,'registros','new-salida'))).data()));
});
for(const bad of [{estadoFormalizacion:'COMPLETADO',formalizadoEn:serverTimestamp(),formalizadoPor:'ADMIN'},
 {estadoFormalizacion:'PENDIENTE'}, {estadoFormalizacion:'SIN_VERIFICAR',formalizadoEn:null,formalizadoPor:null},
 {...initial,formalizadoPor:'ADMIN'}, {...initial,formalizadoEn:Timestamp.fromMillis(1)}]) {
 test(`new SALIDA rejects forged initial formalization ${Object.keys(bad).join(',')}/${bad.estadoFormalizacion}`,async()=>{
  await assertFails(salida(db(),'ADMIN',bad));
 });
}
for(const role of ['ADMIN','SUPER_ADMIN'])test(`${role} confirms once, UID/time persisted, original data and inventory unchanged`,async()=>{
 const f=db(role),ref=doc(f,'registros','existing');const before=(await getDoc(ref)).data();
 const p=(await getDoc(doc(f,'productos','p-real'))).data(),e=(await getDoc(doc(f,'existencias','e-real'))).data();
 assert.equal(await confirm(f,role),'CONFIRMED');const r=(await getDoc(ref)).data();
 assert.equal(r.estadoFormalizacion,'COMPLETADO');assert.equal(r.formalizadoPor,role);assert.ok(r.formalizadoEn instanceof Timestamp);
 for(const [key,v] of Object.entries(before))if(!Object.keys(initial).includes(key))assert.deepEqual(r[key],v);
 assert.deepEqual((await getDoc(doc(f,'productos','p-real'))).data(),p);assert.deepEqual((await getDoc(doc(f,'existencias','e-real'))).data(),e);
 assert.equal((await getDocs(collection(f,'registros'))).size,2);
 assert.equal(await confirm(f,role),'ALREADY_COMPLETED');assert.deepEqual((await getDoc(ref)).data(),r);
});
for(const role of ['ADMIN','SUPER_ADMIN'])test(`${role} can confirm a missing-field historical record without altering legacy fields`,async()=>{
 const f=db(role),ref=doc(f,'registros','legacy'),before=(await getDoc(ref)).data();await assertSucceeds(confirm(f,role,'legacy'));
 const after=(await getDoc(ref)).data();for(const [key,v]of Object.entries(before))assert.deepEqual(after[key],v);
 await env.withSecurityRulesDisabled(c=>setDoc(doc(c.firestore(),'registros','explicit-unverified'),{
  ...record('USER','explicit-unverified'),estadoFormalizacion:'SIN_VERIFICAR'}));
 await assertSucceeds(confirm(f,role,'explicit-unverified'));
});
test('USER, blocked, unauthenticated and contradictory role/profile cannot confirm by direct write',async()=>{
 const clients=[[db('USER'),'USER'],[db('ADMIN','blocked'),'blocked'],[env.unauthenticatedContext().firestore(),'anon'],[db('ADMIN','USER'),'USER']];
 for(const [f,uid]of clients)await assertFails(updateDoc(doc(f,'registros','existing'),formalUpdate(uid)));
});
test('USER can read all three formalization states including legacy missing state',async()=>{
 const admin=db();await confirm(admin,'ADMIN');const f=db('USER');
 const r=(await getDocs(collection(f,'registros'))).docs.map(d=>d.data());assert.equal(r.length,2);
 assert.ok(r.some(d=>d.estadoFormalizacion==='COMPLETADO'));assert.ok(r.some(d=>!('estadoFormalizacion'in d)));
});
for(const [key,value]of [['cantidad',99],['estadoPago','Pagado'],['productoId','another'],['codigoProducto','wrong'],['codigoExistencia','wrong'],
 ['recibidoPor','another'],['tiendaDestinoId','other'],['tiendaOrigenId','other'],['fechaHora',Timestamp.fromMillis(999)],['estado','ANULADO'],['extra','injected']]) {
 test(`formalization cannot change original field ${key}`,async()=>{
  await assertFails(updateDoc(doc(db(),'registros','existing'),{...formalUpdate('ADMIN'),[key]:value}));
 });
}
test('UID spoof, client timestamp, missing audit or pending audit-only update rejected',async()=>{
 const f=db(),ref=doc(f,'registros','existing');
 for(const update of [{...formalUpdate('OTHER')},{...formalUpdate('ADMIN'),formalizadoEn:Timestamp.fromMillis(1)},
 {estadoFormalizacion:'COMPLETADO'},{formalizadoPor:'ADMIN',formalizadoEn:serverTimestamp()}]) await assertFails(updateDoc(ref,update));
});
test('completed is immutable: no revert, reconfirm, audit replacement or delete',async()=>{
 const f=db();await confirm(f,'ADMIN');const ref=doc(f,'registros','existing');
 await assertFails(updateDoc(ref,initial));await assertFails(updateDoc(ref,formalUpdate('ADMIN')));
 await assertFails(updateDoc(ref,{formalizadoPor:'SUPER_ADMIN'}));await assertFails(deleteDoc(ref));
});
test('two concurrent administrators yield one confirmed transaction and preserve its first audit',async()=>{
 const results=await Promise.all([confirm(db('ADMIN'),'ADMIN'),confirm(db('SUPER_ADMIN'),'SUPER_ADMIN')]);
 assert.deepEqual(results.sort(),['ALREADY_COMPLETED','CONFIRMED']);
 const r=(await getDoc(doc(db(),'registros','existing'))).data();assert.ok(['ADMIN','SUPER_ADMIN'].includes(r.formalizadoPor));
});
test('unreachable local transport rejects transaction with no false completion or queued update',async()=>{
 const app=initializeApp({projectId:'demo-clisa-formalizacion'},'offline-fixture');const f=getFirestore(app);
 connectFirestoreEmulator(f,'127.0.0.1',8089,{mockUserToken:{sub:'ADMIN',role:'ADMIN',admin:true,email:'ADMIN@example.test'}});
 try {
  await assert.rejects(runTransaction(f,async tx=>{await tx.get(doc(f,'registros','existing'));tx.update(doc(f,'registros','existing'),formalUpdate('ADMIN'));},{maxAttempts:1}),error=>error.code==='unavailable');
 } finally { await terminate(f);await deleteApp(app); }
 const r=(await getDoc(doc(db(),'registros','existing'))).data();assert.equal(r.estadoFormalizacion,'PENDIENTE');assert.equal(r.formalizadoEn,null);
});
test('formalization cannot be coupled to historical-record stock replay',async()=>{
 const f=db(),b=writeBatch(f);b.update(doc(f,'registros','existing'),formalUpdate('ADMIN'));
 b.update(doc(f,'productos','p-real'),{stock:4,ultimaSalidaId:'existing',actualizadoEn:serverTimestamp()});
 b.update(doc(f,'existencias','e-real'),{cantidad:4,estado:'DISPONIBLE',ultimaSalidaId:'existing',actualizadoEn:serverTimestamp()});
 await assertFails(b.commit());assert.equal((await getDoc(doc(f,'registros','existing'))).data().estadoFormalizacion,'PENDIENTE');
});
test('malformed legacy audit and non-transfer records do not obtain formalization permissions',async()=>{
 for(const [id,extra]of [['null-state',{estadoFormalizacion:null}],['prior-audit',{formalizadoPor:'someone'}],['wrong-type',{tipo:'AJUSTE_ENTRADA'}],['wrong-store',{tiendaDestinoId:'other'}]]) {
  await env.withSecurityRulesDisabled(c=>setDoc(doc(c.firestore(),'registros',id),{...record('ADMIN',id),...extra}));
  await assertFails(updateDoc(doc(db(),'registros',id),formalUpdate('ADMIN')));
 }
});
test('new SALIDA still rejects non-atomic or malformed stock movement',async()=>{
 await assertFails(setDoc(doc(db(),'registros','new-salida'),{...record('ADMIN','new-salida'),...initial,fechaHora:serverTimestamp()}));
 await assertFails(salida(db(),'ADMIN',{...initial,cantidad:2}));
});
