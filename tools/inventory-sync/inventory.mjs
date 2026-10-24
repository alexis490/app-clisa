import { FieldPath } from 'firebase-admin/firestore';
import { check, hash, canonical, productId, existenceId, quantity, state, validCode, validId } from './canonical.mjs';
export const stamp=t=>t&&Number.isSafeInteger(t.seconds)?`${t.seconds}:${t.nanoseconds}`:null;
const fieldTime=t=>t&&Number.isSafeInteger(t.seconds)?`Timestamp(seconds=${t.seconds}, nanoseconds=${t.nanoseconds})`:null;
export const serializable=v=>v&&Number.isSafeInteger(v.seconds)?{seconds:v.seconds,nanoseconds:v.nanoseconds}:Array.isArray(v)?v.map(serializable):v&&typeof v==='object'?Object.fromEntries(Object.keys(v).sort().map(k=>[k,serializable(v[k])])):v;
export const historyHash=docs=>hash(docs.map(d=>[d.id,serializable(d.data())]).sort((a,b)=>a[0].localeCompare(b[0])));
export async function scan(db,collection,limit){
  let last=null,docs=[];
  for(;;){
    let q=db.collection(collection).orderBy(FieldPath.documentId()).limit(Math.min(500,limit-docs.length+1));
    if(last)q=q.startAfter(last);
    const page=await q.get();docs.push(...page.docs);check(docs.length<=limit,'SCAN_BUDGET_EXCEEDED');
    if(page.size<500||docs.length===limit){if(page.size===500&&docs.length===limit){const extra=await db.collection(collection).orderBy(FieldPath.documentId()).startAfter(page.docs.at(-1)).limit(1).get();check(extra.empty,'SCAN_BUDGET_EXCEEDED');}break;}
    last=page.docs.at(-1);
  }
  return docs;
}
export function baseline(products,entries){return {
  products:products.map(d=>{const p=d.data();return {id:d.id,code:p.codigo??null,name:p.nombre??null,stock:p.stock??null,remaining:p.cantidadRestante??null,version:fieldTime(p.actualizadoEn)};}),
  existences:entries.map(d=>{const e=d.data();return {id:d.id,productId:e.productoId??null,productCode:e.codigoProducto??null,code:e.codigo??null,quantity:e.cantidad??null,state:e.estado??null,version:fieldTime(e.actualizadoEn)};}),complete:true
};}
export const fingerprint=b=>hash({complete:b.complete,products:b.products.map(p=>canonical([p.id,p.code,p.name,p.stock,p.remaining,p.version])).sort(),existences:b.existences.map(e=>canonical([e.id,e.productId,e.productCode,e.code,e.quantity,e.state,e.version])).sort()});
export function validateIdentities(b){
  const ids=new Map(),skus=new Set(),serials=new Set();
  for(const p of b.products){check(validId(p.id)&&validCode(p.code)&&!skus.has(p.code)&&quantity(p.stock),'AMBIGUOUS_DATABASE_SKU');skus.add(p.code);ids.set(p.id,p);}
  for(const e of b.existences){check(validId(e.id)&&validCode(e.code)&&!serials.has(e.code),'AMBIGUOUS_DATABASE_SERIAL');serials.add(e.code);check(ids.get(e.productId)?.code===e.productCode&&quantity(e.quantity)&&e.state===state(e.quantity),'INCONSISTENT_DATABASE_EXISTENCE');}
}
export function buildUnit(contract,selection,pid,products,entries){
  const b=baseline(products,entries);validateIdentities(b);
  const ops=selection.allowed.filter(o=>o.productId===pid&&o.type!=='SKIP_UNCHANGED');
  const source=contract.source.products.find(p=>p.code===ops[0]?.sku);check(source,'SOURCE_PRODUCT_MISSING');
  const p=products.find(d=>d.data().codigo===source.code);
  if(p)check(p.get('estado')==='ACTIVO','INACTIVE_PRODUCT_REQUIRES_REVIEW');
  check((p?p.id:productId(source.code))===pid,'PRODUCT_IDENTITY_CHANGED');
  check(!products.some(d=>d.id===pid&&d.id!==p?.id),'PRODUCT_ID_COLLISION');
  let stock=0;const serials=new Set();
  for(const row of source.serials){check(validCode(row.code)&&!serials.has(row.code)&&row.valid===true&&quantity(row.quantity),'INVALID_SOURCE_SERIAL');serials.add(row.code);stock+=row.quantity;}
  check(quantity(stock)&&stock===source.reportedQuantity,'SUMMARY_MISMATCH');
  const expected=[];
  if(!p)expected.push({type:'CREATE_PRODUCT',path:`productos/${pid}`,expected:{codigo:source.code,nombre:source.name,stock,estado:'ACTIVO'}});
  else if(p.data().stock!==stock)expected.push({type:'UPDATE_PRODUCT_STOCK',path:p.ref.path,expected:{stock}});
  const bySerial=new Map(entries.map(d=>[d.data().codigo,d]));
  for(const row of source.serials){
    const e=bySerial.get(row.code);
    if(e){check(e.data().productoId===pid&&e.data().codigoProducto===source.code,'SERIAL_OTHER_PRODUCT');
      if(e.data().cantidad!==row.quantity)expected.push({type:'UPDATE_EXISTENCE',path:e.ref.path,expected:{cantidad:row.quantity,estado:state(row.quantity)}});
    }else{
      const id=existenceId(pid,row.code);check(!entries.some(d=>d.id===id),'EXISTENCE_ID_COLLISION');
      expected.push({type:'CREATE_EXISTENCE',path:`existencias/${id}`,expected:{productoId:pid,codigoProducto:source.code,codigo:row.code,cantidad:row.quantity,estado:state(row.quantity)}});
    }
  }
  check(hash(expected.sort((a,b)=>a.path.localeCompare(b.path)))===hash(ops.map(o=>({type:o.type,path:o.path,expected:o.expected})).sort((a,b)=>a.path.localeCompare(b.path))),'PROPOSED_OPERATIONS_DO_NOT_MATCH_SOURCE');
  for(const op of ops){
    const doc=[...products,...entries].find(d=>d.ref.path===op.path);
    if(op.type.startsWith('CREATE_'))check(!doc,'CREATE_ALREADY_EXISTS');
    else{check(doc&&op.observedVersion!==null,'MISSING_DOCUMENT_VERSION');check(op.observedVersion===fieldTime(doc.data().actualizadoEn),'DOCUMENT_FIELD_VERSION_CHANGED');
      check(Object.entries(op.before).every(([k,v])=>canonical(doc.data()[k]??null)===canonical(v)),'BEFORE_CHANGED');}
    check(op.collection===(op.type.includes('EXISTENCE')?'existencias':'productos'),'INVALID_COLLECTION');
  }
  const affected=[...products.filter(d=>d.id===pid),...entries.filter(d=>d.data().productoId===pid)];
  const beforeVersions=Object.fromEntries(affected.map(d=>[d.ref.path,stamp(d.updateTime)]));
  // Include absent targets as well: a competing creator must not replace them.
  for(const op of ops)if(!Object.hasOwn(beforeVersions,op.path))beforeVersions[op.path]=null;
  const retained=entries.filter(d=>d.data().productoId===pid&&!serials.has(d.data().codigo)).map(d=>d.ref.path).sort();
  const unit={productId:pid,inventoryPolicy:'EXCEL_REFERENCE_V1',operations:ops,beforeVersions,retainedAbsences:retained};
  check(ops.length*2+3<=400,'PRODUCT_TOO_MANY_WRITES');
  check(Buffer.byteLength(JSON.stringify(unit))<=600_000,'PRODUCT_TOO_LARGE');
  return unit;
}
