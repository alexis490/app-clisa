// Deterministic metadata review. This module never invokes the stock engine.
import { readFile } from 'node:fs/promises';
import { hash,sha,check,validId } from './canonical.mjs';
import { assertEmulator } from './runtime.mjs';

export const normalize=value=>(value.normalize('NFD').replace(/\p{M}+/gu,'').toLowerCase().match(/[\p{L}\p{N}]+/gu)??[]).join(' ');
const matches=(name,phrase)=>{const n=normalize(phrase);return n.length>0&&` ${name} `.includes(` ${n} `);};
export function parseRules(text){
 const normalized=text.replaceAll('\r\n','\n'),lines=normalized.split('\n').filter(l=>l.trim()&&!l.startsWith('#'));
 const header=lines.shift().split('\t');check(header.length===2&&header[0]==='version','INVALID_RULES');
 const aliases=lines.map(l=>{const p=l.split('\t');check(p.length>=3&&p.length<=4&&['category','brand'].includes(p[0]),'INVALID_RULES');
  return {type:p[0],name:p[1],aliases:p[2].split('|').filter(s=>s.trim()),exclusions:(p[3]??'').split('|').filter(s=>s.trim())};});
 return {version:header[1],digest:sha(normalized),aliases};
}
export const loadRules=async()=>parseRules(await readFile(new URL('../../app/src/main/assets/classification-rules.tsv',import.meta.url),'utf8'));
export const catalogDigest=c=>hash(Object.fromEntries(['categories','brands'].map(k=>[k,[...c[k]].sort((a,b)=>a.id<b.id?-1:a.id>b.id?1:0).map(r=>[r.id,r.name,r.active])])));
export function classify(product,catalog,rules){
 const name=normalize(product.name);
 function field(type,original,entries){
  const candidates=entries.filter(r=>r.active).flatMap(entry=>{
   const configured=rules.aliases.filter(a=>a.type===type&&normalize(a.name)===normalize(entry.name));
   if(configured.some(a=>a.exclusions.some(s=>matches(name,s))))return [];
   const applied=[...new Set([
    ...(matches(name,entry.name)?['catalog-name:'+normalize(entry.name)]:[]),
    ...configured.flatMap(a=>a.aliases).filter(s=>matches(name,s)).map(s=>'explicit-alias:'+normalize(s))])].sort();
   return applied.length?[{id:entry.id,rules:applied}]:[];
  }).sort((a,b)=>a.id<b.id?-1:a.id>b.id?1:0);
  const preserved=entries.some(r=>r.id===original&&r.active),contradictory=candidates.some(c=>c.id!==original);
  const state=!product.identityValid?'REVISAR':preserved?(contradictory?'REVISAR':'ASIGNADO'):original?.trim()?'REVISAR':candidates.length>1?'REVISAR':candidates.length===1?'ASIGNADO':'SIN_IDENTIFICAR';
  return {field:type==='category'?'categoriaId':'marcaId',original,state,proposed:preserved?original:state==='ASIGNADO'?candidates[0].id:null,preserved,candidates};
 }
 return {product,category:field('category',product.categoryId,catalog.categories),brand:field('brand',product.brandId,catalog.brands),descriptionPending:!product.description?.trim()};
}
const entry=d=>{const v=d.data();check(typeof v.nombre==='string'&&v.nombre.trim()&&typeof v.activo==='boolean','INVALID_CATALOG_DOCUMENT');return {id:d.id,name:v.nombre,active:v.activo};};
export async function readCatalog(runtime){
 assertEmulator(runtime);const result={};
 for(const [key,path] of [['categories','categorias'],['brands','marcas']]){
  const rows=[];let cursor=null,complete=false;
  for(let i=0;i<10;i++){
   let q=runtime.db.collection(path).orderBy('__name__').limit(100);if(cursor)q=q.startAfter(cursor);
   const page=await q.get();rows.push(...page.docs.map(entry));if(page.size<100){complete=true;break;}cursor=page.docs.at(-1);
  }
  check(complete,'CATALOG_LIMIT_REQUIRES_REVIEW');result[key]=rows;
 }
 return result;
}
const stableJSON=v=>JSON.stringify(sortJSON(v));
function sortJSON(v){if(Array.isArray(v))return v.map(sortJSON);if(v&&typeof v==='object')return Object.fromEntries(Object.keys(v).sort().map(k=>[k,sortJSON(v[k])]));return v;}
export function metadata(manifest,p,field,suggestion){return {schemaVersion:1,fileSha256:manifest.fileSha256,product:p,field,catalogDigest:manifest.catalogDigest,rulesDigest:manifest.rulesDigest,rulesVersion:manifest.rulesVersion,suggestion};}
export function decisionId(manifest,p,field,suggestion){
 const index=manifest.products.findIndex(v=>v.key===p.key)*2+(field==='marcaId'?1:0),message=stableJSON(metadata(manifest,p,field,suggestion));
 return hash(['CLISA_ISSUE_REVIEW_V1',manifest.fileSha256,index,{sheet:'CLASIFICACION',row:null,message,kind:'OTHER',productCode:null,productName:p.key,serialCode:field}]);
}
const productKeys=['key','documentId','sku','name','categoryId','brandId','description','identityValid'].sort();
function validateManifest(m,catalog,rules){
 check(m?.schemaVersion===1&&/^[a-f0-9]{64}$/.test(m.fileSha256),'INVALID_CLASSIFICATION_MANIFEST');
 check(m.rulesDigest===rules.digest&&m.rulesVersion===rules.version,'RULES_CHANGED');check(m.catalogDigest===catalogDigest(catalog),'CATALOG_CHANGED');
 check(Array.isArray(m.products)&&m.products.length<=10000&&Array.isArray(m.decisions)&&m.decisions.length<=20000,'CLASSIFICATION_LIMIT');
 check(new Set(m.products.map(p=>p.key)).size===m.products.length,'DUPLICATE_PRODUCT_KEY');
 for(const p of m.products){
  check(JSON.stringify(Object.keys(p).sort())===JSON.stringify(productKeys)&&typeof p.key==='string'&&typeof p.name==='string'&&typeof p.identityValid==='boolean','INVALID_PRODUCT_METADATA');
  check([p.sku,p.categoryId,p.brandId,p.description].every(v=>v===null||typeof v==='string')&&(p.documentId===null||validId(p.documentId)),'INVALID_PRODUCT_METADATA');
 }
 check(new Set(m.decisions.map(d=>`${d.productKey}\0${d.field}`)).size===m.decisions.length,'DUPLICATE_APPROVAL');
}
function validateDecision(m,d,classified,uid){
 check(['categoriaId','marcaId'].includes(d.field),'FORBIDDEN_METADATA_FIELD');check(d.reviewer===uid,'REVIEWER_MISMATCH');
 check(typeof d.reason==='string'&&d.reason.trim()&&d.reason.length<=4000&&Number.isSafeInteger(d.atMillis)&&d.atMillis>0,'INVALID_APPROVAL_TRACE');
 const s=d.field==='categoriaId'?classified.category:classified.brand;
 check(classified.product.identityValid&&!s.preserved,'PROTECTED_EXISTING_OR_AMBIGUOUS_IDENTITY');
 check(d.mode==='INDIVIDUAL'||d.mode==='BULK'&&s.state==='ASIGNADO'&&s.proposed===d.id,'CONFLICT_REQUIRES_INDIVIDUAL_REVIEW');
 check(typeof d.original==='string'&&hash(JSON.parse(d.original))===hash(metadata(m,classified.product,d.field,s)),'APPROVAL_SOURCE_CHANGED');
 check(d.decisionId===decisionId(m,classified.product,d.field,s),'APPROVAL_TRACE_CHANGED');
}
async function authenticate(runtime,idToken){
 const claims=await runtime.auth.verifyIdToken(idToken,true),user=await runtime.auth.getUser(claims.uid);
 check(!user.disabled&&['ADMIN','SUPER_ADMIN'].includes(claims.role)&&claims.admin===true&&(claims.role!=='SUPER_ADMIN'||claims.superAdmin===true),'UNAUTHORIZED_ADMIN');
 // Check current administrative claims too; a previously issued elevated token is insufficient after demotion.
 check(user.customClaims?.role===claims.role&&user.customClaims?.admin===true&&(claims.role!=='SUPER_ADMIN'||user.customClaims?.superAdmin===true),'ADMIN_CLAIMS_CHANGED');
 return claims;
}
/** At most 100 products per explicit administrative invocation. Atomic metadata-only writes in Emulator. */
export async function applyClassification(runtime,manifest,{idToken}){
 assertEmulator(runtime);const rules=await loadRules(),actor=await authenticate(runtime,idToken);
 return runtime.db.runTransaction(async tx=>{
  const profile=await tx.get(runtime.db.doc(`usuarios/${actor.uid}`));
  check(profile.get('role')===actor.role&&profile.get('estado')==='ACTIVO','UNAUTHORIZED_PROFILE');
  const catalog={};for(const [key,path] of [['categories','categorias'],['brands','marcas']]){
   const page=await tx.get(runtime.db.collection(path).orderBy('__name__').limit(1001));check(page.size<1000,'CATALOG_LIMIT_REQUIRES_REVIEW');catalog[key]=page.docs.map(entry);
  }
  validateManifest(manifest,catalog,rules);
  const classified=new Map(manifest.products.map(p=>[p.key,classify(p,catalog,rules)]));const grouped=new Map();
  for(const d of manifest.decisions){
   const c=classified.get(d.productKey);check(c,'UNKNOWN_PRODUCT');validateDecision(manifest,d,c,actor.uid);
   const refs=d.field==='categoriaId'?catalog.categories:catalog.brands;check(refs.some(r=>r.id===d.id&&r.active),'INVALID_REFERENCE');
   check(c.product.documentId&&typeof c.product.sku==='string'&&c.product.sku.trim(),'MISSING_PRODUCT_IDENTITY');
   if(!grouped.has(c.product.documentId))grouped.set(c.product.documentId,{product:c.product,decisions:[]});
   const group=grouped.get(c.product.documentId);check(group.product.key===c.product.key,'DUPLICATE_DOCUMENT_TARGET');group.decisions.push(d);
  }
  check(grouped.size<=100,'METADATA_BATCH_LIMIT_100');
  const read=[];for(const [id,group] of grouped){
   const snapshot=await tx.get(runtime.db.doc(`productos/${id}`));
   const matching=await tx.get(runtime.db.collection('productos').where('codigo','==',group.product.sku).limit(2));
   check(matching.size<=1&&matching.docs.every(d=>d.id===id),'PRODUCT_SKU_BECAME_AMBIGUOUS');
   read.push({id,...group,snapshot,identityReads:Math.max(1,matching.size)});
  }
  const changes=[],unchanged=[],pendingCreation=[];
  for(const row of read){
   if(!row.snapshot.exists){pendingCreation.push(row.id);continue;}
   const p=row.product,v=row.snapshot.data();check(v.codigo===p.sku&&v.nombre===p.name&&(v.descripcion??null)===p.description,'PRODUCT_METADATA_CHANGED');
   const patch={};for(const [field,original] of [['categoriaId',p.categoryId],['marcaId',p.brandId]]){
    const current=v[field]??null,d=row.decisions.find(d=>d.field===field);
    check(current===original||(d&&current===d.id),'PRODUCT_METADATA_CHANGED');
    if(d&&current!==d.id){const refs=field==='categoriaId'?catalog.categories:catalog.brands;check(!refs.some(r=>r.id===current&&r.active),'PROTECTED_EXISTING_REFERENCE');patch[field]=d.id;}
   }
   if(Object.keys(patch).length)changes.push({id:row.id,patch});else unchanged.push(row.id);
  }
  for(const change of changes)tx.update(runtime.db.doc(`productos/${change.id}`),change.patch);
  return {schemaVersion:1,environment:'EMULATOR',production:false,fileSha256:manifest.fileSha256,catalogDigest:manifest.catalogDigest,rulesDigest:rules.digest,reviewer:actor.uid,changes,unchanged,pendingCreation,
   stockOperations:0,estimatedCatalogReads:catalog.categories.length+catalog.brands.length,productReads:read.reduce((n,r)=>n+1+r.identityReads,0)};
 });
}
export async function previewClassification(runtime,manifest){
 const rules=await loadRules(),catalog=await readCatalog(runtime);validateManifest(manifest,catalog,rules);
 return {environment:'EMULATOR',production:false,catalogDigest:catalogDigest(catalog),products:manifest.products.map(p=>classify(p,catalog,rules)),writes:0};
}
