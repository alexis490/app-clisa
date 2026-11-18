import test,{before,beforeEach,after} from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {initializeTestEnvironment,assertFails,assertSucceeds} from '@firebase/rules-unit-testing';
import {doc,setDoc,getDoc,updateDoc,deleteDoc,collection,getDocs} from 'firebase/firestore';
assert.equal(process.env.GCLOUD_PROJECT,'demo-clisa-sync-4b1');assert.equal(process.env.FIRESTORE_EMULATOR_HOST,'127.0.0.1:8086');
let env;
before(async()=>{env=await initializeTestEnvironment({projectId:'demo-clisa-sync-4b1',firestore:{host:'127.0.0.1',port:8086,rules:await readFile(new URL('../../../security/historial-importaciones/firestore.rules',import.meta.url),'utf8')}});});
after(async()=>env.cleanup());
beforeEach(async()=>{await env.clearFirestore();await env.withSecurityRulesDisabled(async c=>{
 const f=c.firestore();for(const role of ['USER','ADMIN','SUPER_ADMIN'])await setDoc(doc(f,'usuarios',role),{uid:role,role,estado:'ACTIVO',email:`${role}@example.test`,displayName:role});
 await setDoc(doc(f,'historial_importaciones','existing'),{usuarioUid:'ADMIN',usuario:'ADMIN@example.test',archivo:'stock.xlsx'});
 await setDoc(doc(f,'productos','p'),{codigo:'p',nombre:'Producto',stock:1,estado:'ACTIVO'});
});});
for(const role of ['USER','ADMIN','SUPER_ADMIN',null])test(`rules: ${role??'sin sesión'} no puede crear, leer, modificar ni eliminar historial`,async()=>{
 const f=role?env.authenticatedContext(role,{role,admin:role!=='USER',superAdmin:role==='SUPER_ADMIN'}).firestore():env.unauthenticatedContext().firestore();
 await assertFails(setDoc(doc(f,'historial_importaciones','fake'),{usuarioUid:role??'x'}));
 await assertFails(getDoc(doc(f,'historial_importaciones','existing')));await assertFails(getDocs(collection(f,'historial_importaciones')));
 await assertFails(updateDoc(doc(f,'historial_importaciones','existing'),{archivo:'forged.xlsx'}));await assertFails(deleteDoc(doc(f,'historial_importaciones','existing')));
 await assertFails(setDoc(doc(f,'historial_importaciones','existing','extra','fake'),{producto:'no'}));
});
test('rules: mantiene lectura de inventario activo y bloqueo de stock directo',async()=>{
 for(const role of ['USER','ADMIN','SUPER_ADMIN']){const f=env.authenticatedContext(role,{role,admin:role!=='USER',superAdmin:role==='SUPER_ADMIN'}).firestore();await assertSucceeds(getDoc(doc(f,'productos','p')));await assertFails(updateDoc(doc(f,'productos','p'),{stock:999}));}
 const base=await readFile(new URL('../../../security/historial-importaciones/pre-deploy/deployed-0-firestore.proposed.rules',import.meta.url),'utf8');
 const proposal=await readFile(new URL('../../../security/historial-importaciones/firestore.rules',import.meta.url),'utf8'),block=await readFile(new URL('../../../security/historial-importaciones/history-denial.fragment',import.meta.url),'utf8');
 assert.equal(proposal.replace(block,''),base);
});
