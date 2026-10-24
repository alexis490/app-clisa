import { before, after, beforeEach, test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { initializeTestEnvironment, assertSucceeds, assertFails } from '@firebase/rules-unit-testing';
import { doc, setDoc, updateDoc, deleteDoc, getDoc, getDocs, collection, writeBatch, serverTimestamp, runTransaction } from 'firebase/firestore';

// No fallback endpoint or production project is accepted, including for fixture operations.
const projectId = 'demo-clisa-4a1';
assert.equal(process.env.FIRESTORE_EMULATOR_HOST, '127.0.0.1:8087');
assert.equal(process.env.GCLOUD_PROJECT, projectId);
let env;
const claims = role => ({ role, email: `${role}@example.test`, admin: role !== 'USER', superAdmin: role === 'SUPER_ADMIN' });
const db = (role = 'USER', uid = role, token = claims(role)) => env.authenticatedContext(uid, token).firestore();
const p = { codigo: 'SKU/1', nombre: 'Producto ficticio', stock: 5, cantidadRestante: 9, estado: 'ACTIVO' };
const e = { productoId: 'SKU_1', codigoProducto: 'SKU/1', codigo: '0001', cantidad: 5, estado: 'DISPONIBLE' };
before(async () => {
  env = await initializeTestEnvironment({ projectId, firestore: { host: '127.0.0.1', port: 8087,
    rules: await readFile(new URL('../firestore.proposed.rules', import.meta.url), 'utf8') } });
});
after(async () => { await env?.cleanup(); });
beforeEach(async () => {
  await env.clearFirestore();
  await env.withSecurityRulesDisabled(async c => {
    const f = c.firestore(); const batch = writeBatch(f);
    for (const role of ['USER','ADMIN','SUPER_ADMIN']) batch.set(doc(f, 'usuarios', role), {
      uid: role, displayName: role, email: `${role}@example.test`, role, estado: 'ACTIVO'
    });
    batch.set(doc(f, 'usuarios', 'blocked'), { role: 'ADMIN', estado: 'BLOQUEADO', displayName: 'blocked' });
    batch.set(doc(f, 'productos', 'SKU_1'), p); batch.set(doc(f, 'existencias', 'old-entry'), e);
    batch.set(doc(f, 'tiendas', 'clisa'), { nombre: 'CLISA', estado: 'ACTIVA' });
    batch.set(doc(f, 'tiendas', 'toqen'), { nombre: 'TOQEN', estado: 'ACTIVA' });
    await batch.commit();
  });
});
function payload(role = 'USER', id = 'movement', adjustment = false) {
  const r = { registroId: id, tipo: adjustment ? 'AJUSTE_ENTRADA' : 'SALIDA', productoId: 'SKU_1', codigoProducto: 'SKU/1',
    nombreProducto: p.nombre, existenciaId: 'old-entry', codigoExistencia: '0001', cantidad: 1,
    registradoPorId: role, registradoPorNombre: role, registradoPorEmail: `${role}@example.test`,
    fechaHora: serverTimestamp(), estado: 'COMPLETADO' };
  return adjustment ? { ...r, motivo: 'Recuento ficticio', origen: 'MANUAL' } : { ...r,
    tiendaOrigenId: 'clisa', tiendaOrigenNombre: 'CLISA', tiendaDestinoId: 'toqen', tiendaDestinoNombre: 'TOQEN',
    recibidoPor: 'Receptor ficticio', estadoPago: 'Pendiente' };
}
function move(f, r, { product = true, entry = true, record = true, quantity = r.tipo === 'AJUSTE_ENTRADA' ? 6 : 4 } = {}) {
  const batch = writeBatch(f); const link = r.tipo === 'SALIDA' ? 'ultimaSalidaId' : 'ultimoAjusteId';
  if (record) batch.set(doc(f, 'registros', r.registroId), r);
  if (product) batch.update(doc(f, 'productos', 'SKU_1'), { stock: quantity, [link]: r.registroId, actualizadoEn: serverTimestamp() });
  if (entry) batch.update(doc(f, 'existencias', 'old-entry'), { cantidad: quantity,
    estado: quantity === 0 ? 'SIN_STOCK' : 'DISPONIBLE', [link]: r.registroId, actualizadoEn: serverTimestamp() });
  return batch.commit();
}
test('USER: direct inventory mutation denied, legitimate atomic SALIDA allowed', async () => {
  const f = db();
  await assertFails(updateDoc(doc(f, 'productos','SKU_1'), { stock: 4 }));
  await assertFails(updateDoc(doc(f, 'existencias','old-entry'), { cantidad: 4 }));
  await assertSucceeds(move(f, payload()));
  assert.equal((await getDoc(doc(f,'productos','SKU_1'))).data().cantidadRestante, 9);
});
for (const role of ['ADMIN','SUPER_ADMIN']) {
  test(`${role}: explicit metadata and atomic adjustment permitted`, async () => {
    const f = db(role);
    await assertSucceeds(updateDoc(doc(f,'productos','SKU_1'), { nombre: p.nombre, actualizadoEn: serverTimestamp() }));
    await assertSucceeds(move(f, payload(role, 'adjustment', true)));
  });
  test(`${role}: direct stock, legacy counter, creates and deletes denied`, async () => {
    const f = db(role);
    await assertFails(updateDoc(doc(f,'productos','SKU_1'), { stock: 999, actualizadoEn: serverTimestamp() }));
    await assertFails(updateDoc(doc(f,'productos','SKU_1'), { cantidadRestante: 999, actualizadoEn: serverTimestamp() }));
    await assertFails(setDoc(doc(f,'productos','new'), p));
    await assertFails(setDoc(doc(f,'existencias','new'), e));
    await assertFails(deleteDoc(doc(f,'existencias','old-entry')));
    await assertFails(setDoc(doc(f,'importaciones','fake-job'), { estado: 'COMPLETADO' }));
  });
}
test('USER cannot perform adjustment or metadata edit', async () => {
  const f = db();
  await assertFails(move(f, payload('USER','adjustment',true)));
  await assertFails(updateDoc(doc(f,'productos','SKU_1'), { nombre: 'Otro', actualizadoEn: serverTimestamp() }));
});
test('blocked, unauthenticated, missing profile and contradictory claims denied', async () => {
  const clients = [db('ADMIN','blocked'), env.unauthenticatedContext().firestore(), db('ADMIN','missing'),
    db('USER','USER',{ ...claims('USER'), admin: true }), db('ADMIN','USER'),
    db('ADMIN','ADMIN',{ ...claims('ADMIN'), superAdmin: true })];
  for (const f of clients) {
    await assertFails(getDoc(doc(f,'productos','SKU_1')));
    await assertFails(updateDoc(doc(f,'productos','SKU_1'), { stock: 999 }));
  }
});
test('initial USER self profile creation allowed without role claim; escalation/reactivation denied', async () => {
  const f = db('USER','new',{ email: 'new@example.test' });
  await assertSucceeds(getDoc(doc(f,'usuarios','new')));
  const profile = { uid: 'new', email: 'new@example.test', displayName: 'Nuevo', role: 'USER', estado: 'ACTIVO', creadoEn: serverTimestamp() };
  await assertFails(setDoc(doc(f,'usuarios','new'), { ...profile, role: 'ADMIN' }));
  await assertSucceeds(setDoc(doc(f,'usuarios','new'), profile));
  await assertFails(updateDoc(doc(f,'usuarios','new'), { role: 'SUPER_ADMIN' }));
  await assertFails(updateDoc(doc(db('ADMIN','blocked'),'usuarios','blocked'), { estado: 'ACTIVO', actualizadoEn: serverTimestamp() }));
});
test('only SUPER_ADMIN can list and change other USER/ADMIN state; roles/self/SUPER protected', async () => {
  for (const role of ['USER','ADMIN']) {
    const f = db(role);
    await assertFails(getDocs(collection(f,'usuarios')));
    await assertFails(updateDoc(doc(f,'usuarios','USER'), { role: 'ADMIN' }));
    await assertFails(updateDoc(doc(f,'usuarios','ADMIN'), { estado: 'BLOQUEADO', actualizadoEn: serverTimestamp() }));
  }
  const f = db('SUPER_ADMIN');
  await assertSucceeds(getDocs(collection(f,'usuarios')));
  await assertSucceeds(updateDoc(doc(f,'usuarios','USER'), { estado: 'BLOQUEADO', actualizadoEn: serverTimestamp() }));
  await assertFails(updateDoc(doc(f,'usuarios','ADMIN'), { role: 'USER', actualizadoEn: serverTimestamp() }));
  await assertFails(updateDoc(doc(f,'usuarios','SUPER_ADMIN'), { estado: 'BLOQUEADO', actualizadoEn: serverTimestamp() }));
});
for (const missing of ['product','entry','record']) test(`partial atomic movement missing ${missing} rejected`, async () => {
  await assertFails(move(db(), payload(), { [missing]: false }));
});
for (const [field,value] of [['cantidad',0], ['cantidad',-1], ['cantidad',1.5], ['codigoProducto','wrong'],
  ['codigoExistencia','wrong'], ['nombreProducto','wrong'], ['estadoPago','Invalid'], ['recibidoPor',''],
  ['tiendaOrigenNombre','wrong'], ['tiendaDestinoId','clisa'], ['registradoPorNombre','wrong'],
  ['registradoPorId','other'], ['registradoPorEmail','wrong'], ['fechaHora',new Date(0)], ['extra','injected']]) {
  test(`invalid SALIDA ${field}=${value} rejected`, async () => {
    await assertFails(move(db(), { ...payload(), [field]: value }));
  });
}
test('missing estadoPago rejected; corrected SALIDA schema is satisfiable', async () => {
  const r = payload(); delete r.estadoPago;
  await assertFails(move(db(),r));
  await assertSucceeds(move(db(),payload()));
});
test('negative remaining stock and mismatched deltas rejected', async () => {
  await assertFails(move(db(),payload(),{ quantity: -1 }));
  await assertFails(move(db(),payload(),{ quantity: 3 }));
});
test('historical records immutable; old record cannot authorize a second deduction', async () => {
  const f = db(); await assertSucceeds(move(f,payload()));
  await assertFails(updateDoc(doc(f,'registros','movement'), { cantidad: 2 }));
  await assertFails(deleteDoc(doc(f,'registros','movement')));
  await assertFails(updateDoc(doc(f,'productos','SKU_1'), { stock: 3, ultimaSalidaId: 'movement', actualizadoEn: serverTimestamp() }));
  await assertFails(updateDoc(doc(f,'existencias','old-entry'), { cantidad: 3, ultimaSalidaId: 'movement', actualizadoEn: serverTimestamp() }));
});
test('client cannot acquire/remove lock; trusted local fixture lock blocks movements and metadata', async () => {
  const f = db('SUPER_ADMIN'); const lock = doc(f,'importaciones','_control_clisa');
  await assertFails(setDoc(lock,{ job: 'fake' }));
  await env.withSecurityRulesDisabled(c => setDoc(doc(c.firestore(),'importaciones','_control_clisa'), { job: 'test', expiresAt: 0 }));
  await assertFails(deleteDoc(lock));
  await assertFails(move(db(),payload()));
  await assertFails(move(db('ADMIN'),payload('ADMIN','adjustment',true)));
  await assertFails(updateDoc(doc(f,'productos','SKU_1'), { nombre: 'Otro', actualizadoEn: serverTimestamp() }));
});
test('local future-worker lock prototype: two concurrent claims yield one owner; stale fencing rejected', async () => {
  // Local fixture only, NOT a production executor. Validates Firestore transaction conflict semantics.
  await env.withSecurityRulesDisabled(async c => {
    const f = c.firestore(); const ref = doc(f,'importaciones','_control_clisa');
    const claim = job => runTransaction(f, async tx => {
      const lock = await tx.get(ref);
      if (lock.exists()) return false;
      tx.set(ref,{ job, fence: 1 }); return true;
    });
    const results = await Promise.all([claim('one'),claim('two')]);
    assert.equal(results.filter(Boolean).length,1);
    const owner = (await getDoc(ref)).data().job;
    const writeWithFence = fence => runTransaction(f,async tx => {
      const lock = (await tx.get(ref)).data();
      if (lock.job !== owner || lock.fence !== fence) throw Error('STALE_FENCE');
      tx.update(ref,{ fence: fence+1 });
    });
    await writeWithFence(1);
    await assert.rejects(writeWithFence(1),/STALE_FENCE/);
  });
});
test('frozen audited rules reproduce contradictory SALIDA/AJUSTE schemas; proposal restores satisfiable schemas', async () => {
  const frozen = await readFile(new URL('../../audit-2026-10-21/deployed-0-firestore.rules', import.meta.url),'utf8');
  const old = await initializeTestEnvironment({ projectId, firestore: { host: '127.0.0.1', port: 8087, rules: frozen } });
  try {
    await assertFails(move(old.authenticatedContext('USER',claims('USER')).firestore(),payload()));
    await assertFails(move(old.authenticatedContext('ADMIN',claims('ADMIN')).firestore(),payload('ADMIN','adjustment',true)));
  } finally {
    await old.cleanup();
    const restored = await initializeTestEnvironment({ projectId, firestore: { host: '127.0.0.1', port: 8087,
      rules: await readFile(new URL('../firestore.proposed.rules', import.meta.url),'utf8') } });
    await restored.cleanup();
  }
});
