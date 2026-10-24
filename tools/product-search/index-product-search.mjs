// Administrative migration only. Never bundled in Android; default mode does not write.
import { initializeApp, applicationDefault } from 'firebase-admin/app';
import { getFirestore, FieldPath } from 'firebase-admin/firestore';
import { pathToFileURL } from 'node:url';

export function normalize(text) {
  return text.normalize('NFD').replace(/\p{M}+/gu, '').toLowerCase().trim().replace(/\s+/gu, ' ');
}
export function trigrams(name, code) {
  const result = new Set();
  for (const value of [name, code]) {
    const text = normalize(value);
    for (let i = 0; i + 3 <= text.length; i++) result.add(text.slice(i, i + 3));
  }
  return [...result].sort();
}

async function main() {
  const args = process.argv.slice(2);
  const option = flag => args.includes(flag) ? args[args.indexOf(flag) + 1] : undefined;
  const project = option('--project');
  const limit = Number(option('--limit') ?? 1000);
  const apply = args.includes('--apply');
  const afterId = option('--after');
  if (!project || !Number.isInteger(limit) || limit < 1 || limit > 5000) {
    throw new Error('Use --project empresa-clisa [--limit 1000 (máximo 5000)] [--after ID] [--apply]. Sin --apply no se escribe.');
  }
  initializeApp({ credential: applicationDefault(), projectId: project });
  const db = getFirestore();
  const collection = db.collection('productos');
  let cursor = afterId ? collection.doc(afterId) : null;
  let inspected = 0, updates = 0, unchanged = 0, gramsTotal = 0;
  while (inspected < limit) {
    let query = collection.orderBy(FieldPath.documentId()).limit(Math.min(50, limit - inspected));
    if (cursor) query = query.startAfter(cursor.id);
    const snapshot = await query.get();
    if (snapshot.empty) break;
    for (const document of snapshot.docs) {
      function fields(fresh) {
        const name = fresh.get('nombre') ?? 'Producto sin nombre';
        const code = fresh.get('codigo') ?? fresh.id;
        if (typeof name !== 'string' || typeof code !== 'string') throw new Error(`Nombre/código inválido en ${fresh.id}`);
        const values = trigrams(name, code);
        if (values.length > 2000) throw new Error(`Revisar ${fresh.id}: demasiados fragmentos (${values.length}). No se truncará el índice.`);
        return values;
      }
      const values = fields(document);
      gramsTotal += values.length;
      if (apply) {
        // Re-read inside the transaction so concurrent name/code edits cannot create a stale index.
        const changed = await db.runTransaction(async transaction => {
          const fresh = await transaction.get(document.ref);
          if (!fresh.exists) return false;
          const current = fields(fresh);
          if (fresh.get('busquedaVersion') === 1 && JSON.stringify(fresh.get('busquedaTrigramas')) === JSON.stringify(current)) return false;
          transaction.update(document.ref, { busquedaTrigramas: current, busquedaVersion: 1 });
          return true;
        });
        if (changed) updates++; else unchanged++;
      } else {
        if (document.get('busquedaVersion') === 1 && JSON.stringify(document.get('busquedaTrigramas')) === JSON.stringify(values)) unchanged++;
        else updates++;
      }
      inspected++;
      cursor = document;
    }
    if (snapshot.size < 50) break;
  }
  console.log(JSON.stringify({ mode: apply ? 'APLICADO' : 'SIMULACIÓN SIN ESCRITURAS', project,
    inspected, updates, unchanged, averageTrigrams: inspected ? Math.round(gramsTotal / inspected) : 0,
    nextAfter: cursor?.id ?? afterId ?? null,
    note: 'El cursor es de este modo de ejecución. Una simulación NO indexa documentos: al aplicar, empieza desde el mismo --after usado en la simulación.' }, null, 2));
}
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main().catch(error => { console.error(error.message); process.exitCode = 1; });
}
