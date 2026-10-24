// Read-only backend audit. Never prints or saves credentials; no Admin SDK and no writes to Firebase.
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const cli = 'C:/Users/TUF/AppData/Local/npm-cache/_npx/7750544ccf494d8b/node_modules/firebase-tools/lib';
const project = 'empresa-clisa';
const out = __dirname;
const auth = require(path.join(cli, 'auth.js'));
const api = require(path.join(cli, 'apiv2.js'));
const hash = text => crypto.createHash('sha256').update(text.replace(/\r\n/g, '\n').trim()).digest('hex');

async function main() {
  const account = auth.getProjectDefaultAccount(process.cwd());
  if (!account) throw new Error('Firebase CLI has no signed-in account.');
  auth.setActiveAccount({}, account);
  const bearer = await api.getAccessToken();
  async function request(url, body) {
    const parsed = new URL(url);
    const allowed = ['firebaserules.googleapis.com', 'firestore.googleapis.com', 'cloudbilling.googleapis.com', 'firebase.googleapis.com'];
    if (parsed.protocol !== 'https:' || !allowed.includes(parsed.hostname) || !parsed.pathname.includes(project)) throw new Error('Audit endpoint outside requested project.');
    if (body && ![':runQuery', ':runAggregationQuery'].some(suffix => parsed.pathname.endsWith(suffix))) throw new Error('Only read-only query endpoints are permitted.');
    const response = await fetch(url, { method: body ? 'POST' : 'GET',
      headers: { Authorization: `Bearer ${bearer}`, ...(body ? {'Content-Type': 'application/json'} : {}) },
      body: body ? JSON.stringify(body) : undefined, signal: AbortSignal.timeout(30000) });
    const data = await response.json();
    return { status: response.status, data: response.ok ? data : { error: data.error?.status || 'REQUEST_FAILED' } };
  }
  fs.mkdirSync(out, { recursive: true });
  if (process.argv[2] === 'counts') {
    const counts = {};
    for (const collection of ['productos', 'existencias']) counts[collection] = await request(`https://firestore.googleapis.com/v1/projects/${project}/databases/(default)/documents:runAggregationQuery`, {
      structuredAggregationQuery: {structuredQuery: {from: [{collectionId: collection}]}, aggregations: [{alias: 'total', count: {}}]}
    });
    fs.writeFileSync(path.join(out, 'counts.json'), JSON.stringify(counts, null, 2));
    console.log(JSON.stringify(counts, null, 2));
    return;
  }
  if (process.argv[2] === 'quality') {
    const result = {};
    for (const code of ['ACC00009730', 'ACC00031673']) result[code] = await request(`https://firestore.googleapis.com/v1/projects/${project}/databases/(default)/documents:runQuery`, {
      structuredQuery: {from: [{collectionId: 'existencias'}],
        where: {fieldFilter: {field: {fieldPath: 'codigo'}, op: 'EQUAL', value: {stringValue: code}}}, limit: 3}
    });
    fs.writeFileSync(path.join(out, 'quality-samples.json'), JSON.stringify(result, null, 2));
    console.log(JSON.stringify(result, null, 2));
    return;
  }
  if (process.argv[2] === 'metadata') {
    const metadata = { checkedAt: new Date().toISOString(), project, authMode: 'Firebase CLI OAuth; management metadata only' };
    metadata.release = await request(`https://firebaserules.googleapis.com/v1/projects/${project}/releases/cloud.firestore`);
    if (metadata.release.status === 200) {
      const rulesetName = metadata.release.data.rulesetName;
      const ruleset = await request(`https://firebaserules.googleapis.com/v1/${rulesetName}`);
      metadata.rulesetStatus = ruleset.status;
      if (ruleset.status === 200) {
        metadata.rulesetCreated = ruleset.data.createTime;
        metadata.files = [];
        for (const [i, file] of (ruleset.data.source?.files || []).entries()) {
          const filename = `deployed-${i}-${path.basename(file.name)}`;
          fs.writeFileSync(path.join(out, filename), file.content);
          metadata.files.push({ sourceName: file.name, savedAs: filename, sha256: hash(file.content) });
        }
        metadata.localCandidates = ['security/firestore-user-signup.proposed.rules', 'security/roles-proposal.rules.fragment'].map(local => {
          const sha256 = hash(fs.readFileSync(local, 'utf8'));
          return { local, sha256, matchesDeployed: metadata.files.some(f => f.sha256 === sha256) };
        });
      }
    }
    metadata.database = await request(`https://firestore.googleapis.com/v1/projects/${project}/databases/(default)`);
    metadata.billing = await request(`https://cloudbilling.googleapis.com/v1/projects/${project}/billingInfo`);
    if (metadata.billing.status === 200) metadata.billing.data = { billingEnabled: metadata.billing.data.billingEnabled };
    metadata.indexes = await request(`https://firestore.googleapis.com/v1/projects/${project}/databases/(default)/collectionGroups/-/indexes?pageSize=100`);
    fs.writeFileSync(path.join(out, 'metadata.json'), JSON.stringify(metadata, null, 2));
    console.log(JSON.stringify(metadata, null, 2));
    return;
  }
  if (process.argv[2] === 'indexes') {
    const indexes = {};
    for (const collection of ['productos', 'existencias']) indexes[collection] = await request(`https://firestore.googleapis.com/v1/projects/${project}/databases/(default)/collectionGroups/${collection}/indexes?pageSize=100`);
    fs.writeFileSync(path.join(out, 'indexes.json'), JSON.stringify(indexes, null, 2));
    console.log(JSON.stringify(indexes, null, 2));
    return;
  }
  if (process.argv[2] === 'relations') {
    const base = `https://firestore.googleapis.com/v1/projects/${project}/databases/(default)/documents`;
    const sample = JSON.parse(fs.readFileSync(path.join(out, 'schema-samples.json'), 'utf8'));
    const first = sample.collections.productos.documents[0];
    const history = sample.collections.registros.documents[0];
    const targets = [
      ['categoria', 'categorias', first.fields.categoriaId.value],
      ['marca', 'marcas', first.fields.marcaId.value],
      ['productoDelRegistro', 'productos', history.fields.productoId.value],
      ['existenciaDelRegistro', 'existencias', history.fields.existenciaId.value]
    ];
    const result = { note: 'Four exact-document GETs plus two bounded read-only queries. Not a synchronization comparison or Android rules test.', targets: {} };
    for (const [label, collection, id] of targets) result.targets[label] = await request(`${base}/${collection}/${encodeURIComponent(id)}`);
    for (const [label, collection, field, value, limit] of [
      ['existenciasDelPrimerProducto', 'existencias', 'productoId', first.id, 5],
      ['skuConBarra', 'productos', 'codigo', 'SFYRS/1000G', 2]
    ]) result[label] = await request(`${base}:runQuery`, { structuredQuery: {
      from: [{collectionId: collection}], where: {fieldFilter: {field: {fieldPath: field}, op: 'EQUAL', value: {stringValue: value}}}, limit
    } });
    fs.writeFileSync(path.join(out, 'relations.json'), JSON.stringify(result, null, 2));
    console.log(JSON.stringify(result, null, 2));
    return;
  }
  if (process.argv[2] !== 'samples') throw new Error('Choose metadata, indexes or samples.');
  // OAuth/IAM schema inspection, not an Android client permission test. Do not fall back to other credentials.
  const base = `https://firestore.googleapis.com/v1/projects/${project}/databases/(default)/documents`;
  const collections = ['productos', 'existencias', 'registros', 'importaciones', 'categorias', 'marcas', 'usuarios', 'tiendas'];
  const report = { checkedAt: new Date().toISOString(), project, authMode: 'Firebase CLI OAuth/IAM, read-only schema samples; DOES NOT test Android security rules',
    pageSize: 3, maximumDocumentsRequested: 24, collections: {}, relations: [] };
  const sampled = {};
  function field(value, key, personal) {
    const type = Object.keys(value)[0];
    if (/email|displayName|username|registradoPorNombre|recibidoPor|realizadoPorNombre|registradoPorId/i.test(key)) return {type, value: '[redacted]'};
    if (personal && !['role', 'estado', 'activo', 'storeName', 'tiendaId', 'creadoEn', 'createdAt', 'actualizadoEn'].includes(key)) return {type, value: '[redacted]'};
    if (/token|password|secret|credential|apikey/i.test(key)) return {type, value: '[redacted]'};
    return { type, value: type === 'mapValue' || type === 'arrayValue' ? '[complex value]' : value[type] };
  }
  function summarize(doc, collection, index) {
    const personal = collection === 'usuarios';
    return { id: personal ? `[Firebase Auth uid sample ${index + 1}]` : doc.name.split('/').pop(),
      fields: Object.fromEntries(Object.entries(doc.fields || {}).map(([key, value]) => [key, field(value, key, personal)])) };
  }
  for (const collection of collections) {
    const response = await request(`${base}/${collection}?pageSize=3`);
    const docs = response.data.documents || [];
    sampled[collection] = docs;
    report.collections[collection] = { status: response.status, documentsReturned: docs.length, hasNextPage: !!response.data.nextPageToken,
      note: !docs.length && response.status === 200 ? 'No documents found; Firestore does not retain empty collections.' : undefined,
      documents: docs.map((doc, i) => summarize(doc, collection, i)) };
  }
  // Follow only the three sampled existence references, never scan the inventory.
  const seen = new Set();
  for (const entry of sampled.existencias || []) {
    const productId = entry.fields?.productoId?.stringValue;
    if (!productId || seen.has(productId) || productId.includes('/')) continue;
    seen.add(productId);
    const response = await request(`${base}/productos/${encodeURIComponent(productId)}`);
    report.relations.push({ existenceId: entry.name.split('/').pop(), productoId: productId,
      status: response.status, product: response.status === 200 ? summarize(response.data, 'productos', 0) : null });
  }
  report.maximumDocumentsRequested += seen.size;
  fs.writeFileSync(path.join(out, 'schema-samples.json'), JSON.stringify(report, null, 2));
  console.log(JSON.stringify(report, null, 2));
}
main().catch(() => { console.error('Read-only audit failed. No credential details are printed.'); process.exitCode = 1; });
