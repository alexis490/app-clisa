import { Worker } from 'node:worker_threads';
import { randomUUID } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import { FieldValue } from 'firebase-admin/firestore';
import { parseRules } from './classification.mjs';
import { classifyBatch } from './automatic-classification.mjs';
import { document,version,planProduct,countersFor,emptyCounters } from './automatic-plan.mjs';
import { HttpError } from './http-proposal.mjs';
import { reviewAccumulator,productRecordRows } from './review-report.mjs';
import { sha } from './canonical.mjs';
import { historyCollection,historyRecord,historyView,validEmail } from './import-history.mjs';
import { exportUnit,exportIssue } from './import-export.mjs';

const chunks=(items,n)=>Array.from({length:Math.ceil(items.length/n)},(_,i)=>items.slice(i*n,(i+1)*n));
const index=i=>String(i).padStart(5,'0');
const check=(ok,code='IMPORT_CHANGED')=>{if(!ok)throw new HttpError(409,code);};
const plus=(a,b)=>Object.fromEntries(Object.keys(emptyCounters()).map(k=>[k,(a[k]??0)+(b[k]??0)]));
const path='importaciones_excel';

export function readSourceWorker(bytes,filename,mappings,timeoutMs=15000,formatOnly=false){
 return new Promise((resolve,reject)=>{
  const worker=new Worker(new URL('./source-worker.mjs',import.meta.url),{workerData:{bytes,filename,mappings,formatOnly},env:{},resourceLimits:{maxOldGenerationSizeMb:128,maxYoungGenerationSizeMb:16}});
  let done=false;const end=(error,value)=>{if(done)return;done=true;clearTimeout(timer);worker.terminate();error?reject(error):resolve(value);};
  const timer=setTimeout(()=>end(new HttpError(422,'SOURCE_TIMEOUT')),timeoutMs);
  worker.once('message',m=>m.error?end(new HttpError(422,m.error)):end(null,m.source));
  worker.once('error',()=>end(new HttpError(422,'INVALID_SOURCE')));worker.once('exit',()=>{if(!done)end(new HttpError(422,'INVALID_SOURCE'));});
 });
}

/** Durable plans and receipts. Each request advances a bounded number of products. */
export function createAutomaticImport({db,apiKey,model,fetcher}){
 const jobRef=id=>{check(typeof id==='string'&&/^[0-9a-f-]{36}$/.test(id),'INVALID_IMPORT');return db.collection(path).doc(id);};
 const units=ref=>ref.collection('productos');
 async function own(ref,actor){const d=await ref.get();check(d.exists&&d.get('uid')===actor.uid,'IMPORT_NOT_FOUND');return d.data();}
 async function catalog(){
  const result={};for(const [key,col] of [['categories','categorias'],['brands','marcas']]){
   const page=await db.collection(col).limit(1001).get();check(page.size<=1000,'CATALOG_LIMIT');
   result[key]=page.docs.filter(d=>typeof d.get('nombre')==='string').map(d=>({id:d.id,name:d.get('nombre'),active:d.get('activo')===true}));
  }return result;
 }
 async function create(source,actor,{reviewBeforeClassification=false,importId=randomUUID()}={}){
  const ref=jobRef(importId);let cats={categories:[],brands:[]},catalogWarning=null;
  if(!reviewBeforeClassification)try{cats=await catalog();}catch{catalogWarning='No se pudo completar el catálogo; clasificación pendiente.';}
  const oversized=p=>p.serials.length>1000||Buffer.byteLength(JSON.stringify(p))>400000;
  const analysis={rowsRead:source.rowCount,validProducts:source.products.filter(p=>!p.critical).length,inventoryTarget:source.products.filter(p=>!p.critical&&!oversized(p)).length,inventoryChecked:0,productsCompared:0,seriesTotal:source.products.reduce((n,p)=>n+p.serials.length,0),seriesProcessed:0,conflictsChecked:0,excludedProducts:0};
  const sourceSignature=sha(Buffer.from(JSON.stringify({products:source.products,issues:source.issues})));
  const initial={sourceSignature,uid:actor.uid,role:actor.role,fileSha256:source.fileSha256,filename:source.filename,bytes:source.bytes,rowCount:source.rowCount,total:source.products.length,issueCount:source.issues.length,status:'STAGING',cursor:0,summary:emptyCounters(),preview:emptyCounters(),catalog:cats,catalogWarning,analysis,reviewBeforeClassification,createdAt:FieldValue.serverTimestamp(),confirmed:false};
  const token=randomUUID();
  const stage=await db.runTransaction(async tx=>{
   const doc=await tx.get(ref);
   if(doc.exists){const saved=doc.data();check(saved.uid===actor.uid,'IMPORT_NOT_FOUND');check(saved.fileSha256===source.fileSha256&&saved.filename===source.filename&&saved.reviewBeforeClassification===reviewBeforeClassification&&(!saved.sourceSignature||saved.sourceSignature===sourceSignature),'IMPORT_CHANGED');
    if(saved.status!=='STAGING')return false;
    check((saved.leaseUntil??0)<Date.now(),'IMPORT_BUSY');tx.update(ref,{lease:token,leaseUntil:Date.now()+60000});
   }else tx.create(ref,{...initial,lease:token,leaseUntil:Date.now()+60000});
   return true;
  });
  if(!stage)return view(ref.id,actor);
  try{
  for(const batch of chunks(source.products.map((p,i)=>({p,i})),200)){
   await db.runTransaction(async write=>{const guard=await write.get(ref);check(guard.get("lease")===token&&guard.get("status")==="STAGING","IMPORT_BUSY");for(const {p,i} of batch){
    const incidents=source.issues.filter(e=>e.productCode===p.code&&(p.code!==null||e.sheet===p.sheet&&e.row===p.row));
    // A hostile oversized group must not exceed Firestore's document limit or block other products.
    const tooLarge=oversized(p);
    const safeSource=tooLarge?{...p,critical:true,serials:[],serialCount:p.serials.length}:p;
    if(tooLarge)incidents.push({productCode:p.code,productName:p.name,sheet:p.sheet,row:p.row,serial:null,type:'PRODUCT_LIMIT',description:'El producto excede el tamaño seguro de una transacción.',reason:'Producto excluido íntegramente; dividir el archivo no cambia este límite por producto.'});
    write.set(units(ref).doc(index(i)),{source:safeSource,recordRowCount:productRecordRows(p).size,issues:incidents.slice(0,100),issueCount:incidents.length,receipt:null});
   }});
  }
  // Keep unassociated diagnostics separately; never silently lose them.
  for(const [i,batch] of chunks(source.issues,100).entries())await db.runTransaction(async tx=>{const guard=await tx.get(ref);check(guard.get('lease')===token&&guard.get('status')==='STAGING','IMPORT_BUSY');tx.set(ref.collection('incidencias').doc(index(i)),{items:batch});});
  await db.runTransaction(async tx=>{const current=await tx.get(ref);check(current.get('lease')===token&&current.get('status')==='STAGING','IMPORT_CHANGED');tx.update(ref,{status:'COMPARISON'});});
  }finally{await unlock(ref,token);}
  return view(ref.id,actor);
 }
 async function inspect(source){
  if(source.critical)return {products:[],entries:[],serialMatches:[]};
  const matches=await db.collection('productos').where('codigo','==',source.code).limit(2).get();
  const products=matches.docs.map(document),entries=[];
  if(products.length===1){const page=await db.collection('existencias').where('productoId','==',products[0].id).limit(1001).get();entries.push(...page.docs.map(document));}
  const serialMatches=[];
  for(const group of chunks([...new Set(source.serials.map(s=>s.code))],30)){
   const page=await db.collection('existencias').where('codigo','in',group).limit(900).get();check(page.size<900,'SERIAL_QUERY_LIMIT');serialMatches.push(...page.docs.map(document));
  }
  // Avoid serializing SDK timestamps or unknown legacy fields into proposal documents.
  const simplify=d=>({...d,data:Object.fromEntries(Object.entries(d.data).filter(([k,v])=>['codigo','nombre','stock','estado','productoId','codigoProducto','cantidad','categoriaId','marcaId','descripcion'].includes(k)&&['string','number','boolean'].includes(typeof v)))});
  return {products:products.map(simplify),entries:entries.map(simplify),serialMatches:serialMatches.map(simplify)};
 }
 async function lock(ref,actor){
  const token=randomUUID();await db.runTransaction(async tx=>{const d=await tx.get(ref);check(d.exists&&d.get('uid')===actor.uid,'IMPORT_NOT_FOUND');check((d.get('leaseUntil')??0)<Date.now(),'IMPORT_BUSY');tx.update(ref,{lease:token,leaseUntil:Date.now()+60000});});return token;
 }
 async function unlock(ref,token){await db.runTransaction(async tx=>{const d=await tx.get(ref);if(d.get('lease')===token)tx.update(ref,{lease:null,leaseUntil:0});});}
 async function advance(id,actor){
  const ref=jobRef(id),token=await lock(ref,actor);
  try{
   const job=await own(ref,actor);
   if(['COMPLETED','PARTIAL','PREVIEW','REVIEW_READY'].includes(job.status))return await view(id,actor);
   check(['COMPARISON','CLASSIFICATION','SYNCING'].includes(job.status),'IMPORT_NOT_READY');
   let end=Math.min(job.cursor+(job.status==='SYNCING'?5:20),job.total);const started=Date.now();
   if(job.status==='COMPARISON'){
    const analysis=job.analysis?{...job.analysis}:null;let preview=job.preview;
    for(let i=job.cursor;i<end;i++){
     const uref=units(ref).doc(index(i)),u=(await uref.get()).data();let input,plan;
     try{input=await inspect(u.source);plan=planProduct(u.source,input.products,input.entries,input.serialMatches,null);}
     catch{plan={code:u.source.code,name:u.source.name,sheet:u.source.sheet,row:u.source.row,excluded:true,reason:'No se pudo verificar el inventario de este producto.',operations:[]};}
     if(input&&Buffer.byteLength(JSON.stringify({source:u.source,input,plan}))>600000){input=null;plan={code:u.source.code,name:u.source.name,sheet:u.source.sheet,row:u.source.row,excluded:true,reason:'La propuesta excede el tamaño seguro de un documento; producto excluido íntegramente.',operations:[]};}
     await uref.update(input?{input,plan}:{plan});
     if(analysis){analysis.productsCompared++;analysis.conflictsChecked++;analysis.seriesProcessed+=u.source.serialCount??u.source.serials.length;if(!u.source.critical)analysis.inventoryChecked++;if(plan.excluded)analysis.excludedProducts++;}
     if(job.reviewBeforeClassification)preview=plus(preview,countersFor(plan));
     if(Date.now()-started>12000){end=i+1;break;}
    }
    const completed=end===job.total;
    await ref.update({cursor:completed&&!job.reviewBeforeClassification?0:end,status:completed?(job.reviewBeforeClassification?'REVIEW_READY':'CLASSIFICATION'):'COMPARISON',...(analysis?{analysis}:{}),...(job.reviewBeforeClassification?{preview}:{})});
   }else if(job.status==='CLASSIFICATION'){
    const rows=[];for(let i=job.cursor;i<end;i++){const uref=units(ref).doc(index(i)),u=(await uref.get()).data();rows.push({uref,u});}
    let rules;try{rules=parseRules(await readFile(new URL('./classification-rules.tsv',import.meta.url),'utf8'));}catch{rules={aliases:[]};}
    const classified=await classifyBatch(rows.filter(r=>!r.u.plan.excluded).map(({u})=>({...u.input.products[0]?.data,code:u.source.code,name:u.source.name})),job.catalog,rules,{apiKey,model,fetcher});
    let preview=job.preview;
    // Counters describe durable results, never simulated stages or Gemini requests.
    // Legacy jobs resumed midway cannot reconstruct previous origins from a page.
    const progress=job.classificationProgress?{...job.classificationProgress}:job.cursor===0?{processed:0,byRules:0,byAi:0,pending:0,excluded:0,rulesProcessed:0,aiProcessed:0}:null;
    for(const {uref,u} of rows){const c=classified.find(c=>c.code===u.source.code);const plan=u.plan.excluded?u.plan:planProduct(u.source,u.input.products,u.input.entries,u.input.serialMatches,c);preview=plus(preview,countersFor(plan));await uref.update({plan});
     if(progress){if(plan.excluded)progress.excluded++;else{progress.processed++;const ai=c?.source==='reglas y Gemini';progress[ai?'aiProcessed':'rulesProcessed']++;if(c?.pending.length)progress.pending++;else progress[ai?'byAi':'byRules']++;}}
    }
    await ref.update({cursor:end===job.total?0:end,status:end===job.total?'PREVIEW':'CLASSIFICATION',preview,...(progress?{classificationProgress:progress}:{})});
   }else{
    for(let i=job.cursor;i<end;i++)await execute(ref,i,actor);
    if(end===job.total)await finish(ref,actor);
   }
   return await view(id,actor);
  }finally{await unlock(ref,token);}
 }
 async function confirm(id,actor){
  const ref=jobRef(id);await db.runTransaction(async tx=>{
   const d=await tx.get(ref);check(d.exists&&d.get('uid')===actor.uid,'IMPORT_NOT_FOUND');
   if(d.get('confirmed')===true)return;
   check(d.get('status')==='PREVIEW','PREVIEW_REQUIRED');
   if(!validEmail(actor.email))throw new HttpError(403,'AUTH_EMAIL_REQUIRED');
   tx.update(ref,{confirmed:true,status:'SYNCING',cursor:0,summary:emptyCounters(),importUserEmail:actor.email,confirmedAt:FieldValue.serverTimestamp()});
  });return view(id,actor);
 }
 async function finish(ref,actor){
  // Job closure and the single immutable audit record either both commit or neither does.
  await db.runTransaction(async tx=>{
   const history=db.collection(historyCollection).doc(ref.id),[j,h]=await tx.getAll(ref,history),job=j.data();
   check(job?.uid===actor.uid&&job.confirmed===true,'CONFIRMATION_REQUIRED');
   if(['COMPLETED','PARTIAL'].includes(job.status))return;
   check(job.status==='SYNCING'&&job.cursor===job.total,'IMPORT_NOT_READY');check(!h.exists,'HISTORY_CONFLICT');
   // A pre-deployment confirmed draft can finish with the current authenticated identity.
   const identity={...job,importUserEmail:job.importUserEmail??actor.email};
   tx.create(history,historyRecord(ref.id,identity));
   tx.update(ref,{status:job.summary.syncErrors?'PARTIAL':'COMPLETED',finishedAt:FieldValue.serverTimestamp()});
  });
 }
 async function review(id,actor){
  const ref=jobRef(id),token=await lock(ref,actor);
  try{
   const job=await own(ref,actor);check(job.status==='REVIEW_READY'&&job.reviewBeforeClassification===true,'REVIEW_NOT_READY');
   if(job.review)return await view(id,actor);
   const diagnostic=await ref.collection('incidencias').get();
   const original=diagnostic.docs.filter(d=>!d.id.startsWith('review-')).flatMap(d=>d.get('items'));
   const accumulator=reviewAccumulator(job,original);
   for(let offset=0;offset<job.total;offset+=50){
    let endOffset=offset;
   const page=await units(ref).orderBy('__name__').startAt(index(offset)).limit(50).select('source','plan.excluded','plan.reason','input.products','recordRowCount').get();
    for(const d of page.docs)accumulator.add(d.data());
   }
   let absent=null;
   try{const inventory=await db.collection('productos').select('codigo').limit(5001).get();
    if(inventory.size<=5000)absent=inventory.docs.filter(d=>typeof d.get('codigo')==='string'&&d.get('codigo').trim()&&!accumulator.codes.has(d.get('codigo'))).length;
   }catch{ /* Unknown is displayed as unavailable, never as zero. */ }
   const {report,extraIssues}=accumulator.finish(absent);
   const write=db.batch();for(const [i,items] of chunks(extraIssues,100).entries())write.set(ref.collection('incidencias').doc(`review-${index(i)}`),{items});
   write.update(ref,{review:report});await write.commit();return await view(id,actor);
  }finally{await unlock(ref,token);}
 }
 async function classify(id,actor){
  const ref=jobRef(id),token=await lock(ref,actor);
  try{
   const job=await own(ref,actor);check(job.reviewBeforeClassification===true,'REVIEW_REQUIRED');
   if(['CLASSIFICATION','PREVIEW','SYNCING','COMPLETED','PARTIAL'].includes(job.status))return await view(id,actor);
   check(job.status==='REVIEW_READY'&&job.review&&job.review.validProducts>0&&!job.confirmed,'REVIEW_REQUIRED');
   let cats={categories:[],brands:[]},catalogWarning=null;
   try{cats=await catalog();}catch{catalogWarning='No se pudo completar el catálogo; clasificación pendiente.';}
   await ref.update({status:'CLASSIFICATION',cursor:0,preview:emptyCounters(),classificationProgress:{processed:0,byRules:0,byAi:0,pending:0,excluded:0,rulesProcessed:0,aiProcessed:0},catalog:cats,catalogWarning,classificationRequestedAt:FieldValue.serverTimestamp()});
   return await view(id,actor);
  }finally{await unlock(ref,token);}
 }
 async function execute(ref,i,actor){
  const uref=units(ref).doc(index(i));
  try{
   await db.runTransaction(async tx=>{
    const [jobDoc,unitDoc,profile]=await tx.getAll(ref,uref,db.doc(`usuarios/${actor.uid}`));
    const job=jobDoc.data(),unit=unitDoc.data();let plan=unit.plan;
    check(job.uid===actor.uid&&job.confirmed===true&&job.status==='SYNCING','CONFIRMATION_REQUIRED');
    check(profile.get('estado')==='ACTIVO'&&profile.get('role')===actor.role&&['ADMIN','SUPER_ADMIN'].includes(actor.role),'PERMISSION_CHANGED');
    if(unit.receipt)return;
    if(!plan.excluded){
     // All authoritative reads precede all writes, including query revalidation.
     const productDocs=await tx.get(db.collection('productos').where('codigo','==',plan.code).limit(2));
     const entryDocs=await tx.get(db.collection('existencias').where('productoId','==',plan.productId).limit(1001));
     const actual=[...productDocs.docs,...entryDocs.docs].map(d=>({path:d.ref.path,version:version(d)}));
     check(JSON.stringify(actual.sort((a,b)=>a.path.localeCompare(b.path)))===JSON.stringify([...plan.baseline].sort((a,b)=>a.path.localeCompare(b.path))),'INVENTORY_CHANGED');
     for(const group of chunks(plan.serialCodes,30)){
      const matches=await tx.get(db.collection('existencias').where('codigo','in',group).limit(900));
      check(matches.size<900&&matches.docs.every(d=>d.get('productoId')===plan.productId&&d.get('codigoProducto')===plan.code),'SERIAL_CHANGED');
      check(new Set(matches.docs.map(d=>d.get('codigo'))).size===matches.size,'SERIAL_CHANGED');
     }
     const creations=plan.operations.filter(o=>o.type.startsWith('CREATE_'));
     if(creations.length){const docs=await tx.getAll(...creations.map(o=>db.doc(o.path)));check(docs.every(d=>!d.exists),'IDENTITY_COLLISION');}
     // Catalog references must still be active at the moment metadata is written.
     const refs=[['categoriaId','categorias'],['marcaId','marcas']].flatMap(([key,col])=>plan.operations.filter(o=>o.fields[key]).map(o=>db.doc(`${col}/${o.fields[key]}`)));
     if(refs.length){
      const docs=await tx.getAll(...refs),inactive=new Set(docs.filter(d=>!d.exists||d.get('activo')!==true).map(d=>d.ref.path));
      if(inactive.size){
       const classification=structuredClone(plan.classification);
       const operations=plan.operations.map(op=>{const fields={...op.fields};for(const [key,col] of [['categoriaId','categorias'],['marcaId','marcas']])if(fields[key]&&inactive.has(`${col}/${fields[key]}`)){delete fields[key];delete classification.fields[key];if(!classification.pending.includes(key))classification.pending.push(key);}return {...op,fields};}).filter(op=>Object.keys(op.fields).length);
       plan={...plan,operations,classification,classificationWarning:'El catálogo cambió; se guardó inventario con clasificación pendiente.'};
      }
     }
     for(const op of plan.operations){const data={...op.fields,actualizadoEn:FieldValue.serverTimestamp()};op.type.startsWith('CREATE_')?tx.create(db.doc(op.path),data):tx.update(db.doc(op.path),data);}
    }
    const counts=countersFor(plan);tx.update(uref,{plan,receipt:{status:plan.excluded?'OMITTED':'APPLIED',counts,at:FieldValue.serverTimestamp()}});
    tx.update(ref,{cursor:i+1,summary:plus(job.summary,counts)});
   });
  }catch(error){
   // A committed receipt resolves an ambiguous network failure without reporting a false failure.
   await db.runTransaction(async tx=>{const [j,u]=await tx.getAll(ref,uref);if(u.get('receipt'))return;
    const counts=emptyCounters();counts.syncErrors=1;
    tx.update(uref,{receipt:{status:'FAILED',reason:error instanceof HttpError?error.code:'WRITE_FAILED',counts}});tx.update(ref,{cursor:i+1,summary:plus(j.get('summary'),counts)});
   });
  }
 }
 async function view(id,actor,offset=0){
  const ref=jobRef(id),job=await own(ref,actor);check(Number.isInteger(offset)&&offset>=0&&offset<=job.total,'INVALID_OFFSET');
  const page=await units(ref).orderBy('__name__').startAt(index(offset)).limit(10).get();
  const items=page.docs.map(d=>{const u=d.data(),c=u.plan?.classification;return {code:u.source.code,name:u.source.name,sheet:u.source.sheet,row:u.source.row,series:u.source.serialCount??u.source.serials.length,stock:u.source.stock,issues:u.issues,issueCount:u.issueCount,plan:u.plan?{excluded:u.plan.excluded,reason:u.plan.reason??null,classification:c?{...c,categoryName:job.catalog.categories.find(e=>e.id===c.fields.categoriaId)?.name??null,brandName:job.catalog.brands.find(e=>e.id===c.fields.marcaId)?.name??null}:null,counts:countersFor(u.plan),operations:u.plan.operations.map(o=>({type:o.type,path:o.path,fields:o.fields,serial:o.fields.codigo??u.input?.entries.find(e=>e.path===o.path)?.data.codigo??null,quantity:o.fields.cantidad??null,beforeQuantity:u.input?.entries.find(e=>e.path===o.path)?.data.cantidad??null}))}:null,receipt:u.receipt??null};});
  const diagnostic=offset===0?await ref.collection('incidencias').get():null;
  const history=['COMPLETED','PARTIAL'].includes(job.status)?await db.collection(historyCollection).doc(id).get():null;
  return {id,status:job.status,confirmed:job.confirmed,filename:job.filename,bytes:job.bytes,rowCount:job.rowCount,fileSha256:job.fileSha256,total:job.total,cursor:job.cursor,issueCount:job.issueCount,analysis:job.analysis??null,review:job.review??null,history:historyView(history?.exists?history.data():null),classificationProgress:job.classificationProgress??null,reviewBeforeClassification:job.reviewBeforeClassification===true,catalogWarning:job.catalogWarning,preview:job.preview,summary:job.summary,items,allIssues:diagnostic?.docs.flatMap(d=>d.get('items'))??[],unassociatedIssues:diagnostic?.docs.flatMap(d=>d.get('items')).filter(e=>e.productCode===null)??[],nextOffset:offset+items.length<job.total?offset+items.length:null};
 }
 async function exportPage(id,actor,section='products',offset=0){
  const ref=jobRef(id),job=await own(ref,actor);
  check(['COMPLETED','PARTIAL'].includes(job.status)&&job.confirmed===true,'EXPORT_NOT_READY');
  check(['products','issues'].includes(section)&&Number.isInteger(offset)&&offset>=0&&offset<=(section==='products'?job.total:1000),'INVALID_OFFSET');
  const history=await db.collection(historyCollection).doc(id).get();
  const metadata={id,status:job.status,total:job.total,cursor:job.cursor,confirmed:job.confirmed,filename:job.filename,rowCount:job.rowCount,
   completedAt:job.finishedAt?.toDate?.().toISOString()??null,responsibleUser:validEmail(job.importUserEmail)?job.importUserEmail:null,
   summary:Object.fromEntries(Object.keys(emptyCounters()).filter(k=>Object.hasOwn(job.summary??{},k)).map(k=>[k,job.summary[k]])),preview:{},items:[],history:historyView(history.exists?history.data():null)};
  const data={products:[],operations:[],issues:[]};let bytes=0,consumed=0,complete=true,nextOffset=null;
  if(section==='products'){
   let endOffset=offset;
   const page=await units(ref).orderBy('__name__').startAt(index(offset)).limit(50)
    .select('source.code','source.name','source.sheet','source.row','source.stock','source.serialCount','input.products','plan.operations','plan.excluded','plan.reason','plan.productId','receipt','issues').get();
   // A gap in old/corrupted evidence is reported, never filled with fabricated rows.
   for(const d of page.docs){
    const position=Number(d.id);if(!Number.isInteger(position)||d.id!==index(position)||position>=job.total){complete=false;continue;}
    if(position!==endOffset)complete=false;
    const row=exportUnit(d.data(),`${id}/productos/${d.id}`),size=Buffer.byteLength(JSON.stringify(row));
    check(size<=3*1024*1024,'EXPORT_ROW_LIMIT');
    if(consumed&&bytes+size>3*1024*1024)break;
    data.products.push(row.product);data.operations.push(...row.operations);data.issues.push(...row.issues);bytes+=size;consumed++;endOffset=position+1;
   }
   if(endOffset<job.total){if(consumed)nextOffset=endOffset;else complete=false;}
  }else{
   const page=await ref.collection('incidencias').orderBy('__name__').offset(offset).limit(10).get();
   for(const d of page.docs){const rows=(d.get('items')??[]).map(exportIssue),size=Buffer.byteLength(JSON.stringify(rows));check(size<=3*1024*1024,'EXPORT_ROW_LIMIT');
    if(consumed&&bytes+size>3*1024*1024)break;data.issues.push(...rows);bytes+=size;consumed++;
   }
   if(consumed<page.size||page.size===10)nextOffset=offset+consumed;
  }
  return {schemaVersion:1,section,offset,nextOffset,complete,job:metadata,data};
 }
 return {create,advance,review,classify,confirm,view,exportPage};
}
