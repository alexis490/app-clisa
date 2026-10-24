const fs = require('node:fs');
const path = require('node:path');
const rules = fs.readFileSync(path.join(__dirname, 'deployed-0-firestore.rules'), 'utf8');
const result = {};
for (const name of ['registroSalidaValido', 'registroAjusteValido']) {
  const start = rules.indexOf(`function ${name}(`);
  const next = rules.indexOf('function ', start + 10);
  const body = rules.slice(start, next < 0 ? rules.length : next);
  const fields = {};
  for (const match of body.matchAll(/r\.keys\(\)\.has(All|Only)\(\[([\s\S]*?)\]\)/g)) {
    // The first hasAll is the document schema; later hasAll clauses are conditional (e.g. importacionId).
    if (!fields[match[1]]) fields[match[1]] = [...match[2].matchAll(/"([^"]+)"/g)].map(item => item[1]);
  }
  if (!fields.All?.length || !fields.Only?.length) throw new Error('Could not parse the deployed allowlists.');
  result[name] = { required: fields.All, allowed: fields.Only,
    requiredButForbidden: fields.All.filter(field => !fields.Only.includes(field)),
    paymentValidated: body.includes('r.estadoPago'), paymentAllowed: fields.Only.includes('estadoPago') };
}
fs.writeFileSync(path.join(__dirname, 'static-findings.json'), JSON.stringify(result, null, 2));
console.log(JSON.stringify(result, null, 2));
