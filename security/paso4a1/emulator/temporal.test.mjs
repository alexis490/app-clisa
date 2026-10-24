import { before, after, beforeEach, test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { initializeTestEnvironment, assertFails } from '@firebase/rules-unit-testing';
import { doc, writeBatch, getDocs, collection, query, where, orderBy, limit, startAfter, Timestamp } from 'firebase/firestore';

// Same isolated local rules environment; no credentials, write API in Android or production fallback.
assert.equal(process.env.FIRESTORE_EMULATOR_HOST,'127.0.0.1:8087');
assert.equal(process.env.GCLOUD_PROJECT,'demo-clisa-4a1');
let env;
const cutoff=Date.UTC(2026,9,22,15,0);
before(async()=> { env=await initializeTestEnvironment({projectId:'demo-clisa-4a1',firestore:{host:'127.0.0.1',port:8087,
  rules:await readFile(new URL('../firestore.proposed.rules',import.meta.url),'utf8')}}); });
after(async()=> {await env?.cleanup();});
beforeEach(async()=> {
  await env.clearFirestore();
  await env.withSecurityRulesDisabled(async c=> {
    const f=c.firestore(), b=writeBatch(f);
    b.set(doc(f,'usuarios','admin'),{role:'ADMIN',estado:'ACTIVO'});
    b.set(doc(f,'usuarios','blocked'),{role:'ADMIN',estado:'BLOQUEADO'});
    await b.commit();
  });
});
const database=(uid='admin')=>env.authenticatedContext(uid,{role:'ADMIN',admin:true,email:'local@example.test'}).firestore();
const window=f=>query(collection(f,'registros'),where('fechaHora','>=',Timestamp.fromMillis(cutoff)),
  where('fechaHora','<=',Timestamp.fromMillis(cutoff+60_000)),orderBy('fechaHora'));
async function seed(n) {
  await env.withSecurityRulesDisabled(async c=> {
    const f=c.firestore(),b=writeBatch(f);
    for(let i=0;i<n;i++) b.set(doc(f,'registros',`r-${String(i).padStart(4,'0')}`),{
      tipo:'SALIDA',productoId:'p-local',cantidad:1,estado:'COMPLETADO',fechaHora:Timestamp.fromMillis(cutoff+Math.floor(i/3))});
    b.set(doc(f,'registros','before'),{fechaHora:Timestamp.fromMillis(cutoff-1)});
    b.set(doc(f,'registros','after-window'),{fechaHora:Timestamp.fromMillis(cutoff+60_001)});
    b.set(doc(f,'registros','missing-date'),{tipo:'SALIDA'});
    b.set(doc(f,'registros','string-date'),{fechaHora:'2026-10-22'});
    await b.commit();
  });
}
test('temporal range paginates 50 with shared timestamps, exact boundaries and no duplicates',async()=> {
  await seed(126);const base=window(database());let cursor;const sizes=[],ids=[];
  for(let i=0;i<4;i++) {
    const page=await getDocs(cursor?query(base,startAfter(cursor),limit(50)):query(base,limit(50)));
    sizes.push(page.size);ids.push(...page.docs.map(d=>d.id));
    if(page.size<50)break;cursor=page.docs.at(-1);
  }
  assert.deepEqual(sizes,[50,50,26]);assert.equal(new Set(ids).size,126);
  assert.ok(!ids.includes('before')&&!ids.includes('after-window'));
  // Demonstrates why this query must NOT claim a complete trustworthy history.
  assert.ok(!ids.includes('missing-date')&&!ids.includes('string-date'));
});
test('four-page cap reads only 200 and leaves history incomplete rather than scanning again',async()=> {
  await seed(201);const base=window(database());let cursor;const ids=[];
  for(let i=0;i<4;i++) {
    const page=await getDocs(cursor?query(base,startAfter(cursor),limit(50)):query(base,limit(50)));
    ids.push(...page.docs.map(d=>d.id));cursor=page.docs.at(-1);
  }
  assert.equal(ids.length,200);assert.equal(new Set(ids).size,200);
});
test('blocked account cannot read the temporal query; no privileged fallback',async()=> {
  await assertFails(getDocs(query(window(database('blocked')),limit(50))));
});
