import { Worker } from 'node:worker_threads';
import { randomUUID } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import { FieldValue } from 'firebase-admin/firestore';
import { parseRules } from './classification.mjs';
import { classifyBatch } from './automatic-classification.mjs';
import { document,version,planProduct,countersFor,emptyCounters } from './automatic-plan.mjs';
import { HttpError } from './http-proposal.mjs';

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
 async function create(source,actor){
  const ref=jobRef(randomUUID());let cats={categories:[],brands:[]},catalogWarning=null;
  try{cats=await catalog();}catch{catalogWarning='No se pudo completar el catálogo; clasificación pendiente.';}
  const initial={uid:actor.uid,role:actor.role,fileSha256:source.fileSha256,filename:source.filename,bytes:source.bytes,rowCount:source.rowCount,total:source.products.length,issueCount:source.issues.length,status:'STAGING',cursor:0,summary:emptyCounters(),preview:emptyCounters(),catalog:cats,catalogWarning,createdAt:FieldValue.serverTimestamp(),confirmed:false};
  await ref.create(initial);
  for(const batch of chunks(source.products.map((p,i)=>({p,i})),200)){
   const write=db.batch();for(const {p,i} of batch){
    const incidents=source.issues.filter(e=>e.productCode===p.code&&(p.code!==null||e.sheet===p.sheet&&e.row===p.row));
    // A hostile oversized group must not exceed Firestore's document limit or block other products.
    const tooLarge=p.serials.length>1000||Buffer.byteLength(JSON.stringify(p))>400000;
    const safeSource=tooLarge?{...p,critical:true,serials:[],serialCount:p.serials.length}:p;
    if(tooLarge)incidents.push({productCode:p.code,productName:p.name,sheet:p.sheet,row:p.row,serial:null,type:'PRODUCT_LIMIT',description:'El producto excede el tamaño seguro de una transacción.',reason:'Producto excluido íntegramente; dividir el archivo no cambia este límite por producto.'});
    write.create(units(ref).doc(index(i)),{source:safeSource,issues:incidents.slice(0,100),issueCount:incidents.length,receipt:null});
   }await write.commit();
  }
  // Keep unassociated diagnostics separately; never silently lose them.
  for(const [i,batch] of chunks(source.issues,100).entries())await ref.collection('incidencias').doc(index(i)).set({items:batch});
  await ref.update({status:'COMPARISON'});return view(ref.id,actor);
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
   if(['COMPLETED','PARTIAL','PREVIEW'].includes(job.status))return await view(id,actor);
   check(['COMPARISON','CLASSIFICATION','SYNCING'].includes(job.status),'IMPORT_NOT_READY');
   let end=Math.min(job.cursor+(job.status==='SYNCING'?5:20),job.total);const started=Date.now();
   if(job.status==='COMPARISON'){
    for(let i=job.cursor;i<end;i++){
     const uref=units(ref).doc(index(i)),u=(await uref.get()).data();let input,plan;
     try{input=await inspect(u.source);plan=planProduct(u.source,input.products,input.entries,input.serialMatches,null);}
     catch{plan={code:u.source.code,name:u.source.name,sheet:u.source.sheet,row:u.source.row,excluded:true,reason:'No se pudo verificar el inventario de este producto.',operations:[]};}
     if(input&&Buffer.byteLength(JSON.stringify({source:u.source,input,plan}))>600000){input=null;plan={code:u.source.code,name:u.source.name,sheet:u.source.sheet,row:u.source.row,excluded:true,reason:'La propuesta excede el tamaño seguro de un documento; producto excluido íntegramente.',operations:[]};}
     await uref.update(input?{input,plan}:{plan});
     if(Date.now()-started>12000){end=i+1;break;}
    }
    await ref.update({cursor:end===job.total?0:end,status:end===job.total?'CLASSIFICATION':'COMPARISON'});
   }else if(job.status==='CLASSIFICATION'){
    const rows=[];for(let i=job.cursor;i<end;i++){const uref=units(ref).doc(index(i)),u=(await uref.get()).data();rows.push({uref,u});}
    let rules;try{rules=parseRules(await readFile(new URL('./classification-rules.tsv',import.meta.url),'utf8'));}catch{rules={aliases:[]};}
    const classified=await classifyBatch(rows.filter(r=>!r.u.plan.excluded).map(({u})=>({...u.input.products[0]?.data,code:u.source.code,name:u.source.name})),job.catalog,rules,{apiKey,model,fetcher});
    let preview=job.preview;
    for(const {uref,u} of rows){const c=classified.find(c=>c.code===u.source.code);const plan=u.plan.excluded?u.plan:planProduct(u.source,u.input.products,u.input.entries,u.input.serialMatches,c);preview=plus(preview,countersFor(plan));await uref.update({plan});}
    await ref.update({cursor:end===job.total?0:end,status:end===job.total?'PREVIEW':'CLASSIFICATION',preview});
   }else{
    for(let i=job.cursor;i<end;i++)await execute(ref,i,actor);
    if(end===job.total){const now=await own(ref,actor);await ref.update({status:now.summary.syncErrors?'PARTIAL':'COMPLETED',finishedAt:FieldValue.serverTimestamp()});}
   }
   return await view(id,actor);
  }finally{await unlock(ref,token);}
 }
 async function confirm(id,actor){
  const ref=jobRef(id);await db.runTransaction(async tx=>{
   const d=await tx.get(ref);check(d.exists&&d.get('uid')===actor.uid,'IMPORT_NOT_FOUND');
   if(d.get('confirmed')===true)return;
   check(d.get('status')==='PREVIEW','PREVIEW_REQUIRED');
   tx.update(ref,{confirmed:true,status:'SYNCING',cursor:0,summary:emptyCounters(),confirmedAt:FieldValue.serverTimestamp()});
  });return view(id,actor);
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
  return {id,status:job.status,confirmed:job.confirmed,filename:job.filename,bytes:job.bytes,rowCount:job.rowCount,fileSha256:job.fileSha256,total:job.total,cursor:job.cursor,issueCount:job.issueCount,catalogWarning:job.catalogWarning,preview:job.preview,summary:job.summary,items,allIssues:diagnostic?.docs.flatMap(d=>d.get('items'))??[],unassociatedIssues:diagnostic?.docs.flatMap(d=>d.get('items')).filter(e=>e.productCode===null)??[],nextOffset:offset+items.length<job.total?offset+items.length:null};
 }
 return {create,advance,confirm,view};
}
