import { check,hash,validCode,quantity,productId,existenceId,state } from './canonical.mjs';
import { fingerprint,validateIdentities } from './inventory.mjs';
import { operationIdentity,planIdentity } from './contract.mjs';

const LOCAL=new Set(['NEGATIVE_QUANTITY','DUPLICATE_SKU','DUPLICATE_SERIAL','SERIAL_WHITESPACE','SKU_WHITESPACE']);
/** Technical isolation only. Never certifies completeness or external inclusion. */
export function independentSourceScope(contract,codes){
 const source=contract.source,selected=new Set(codes),groups=source.products;
 check(source.errorCount===source.issues.length,'TRUNCATED_SOURCE_ISSUES');
 check(groups.every(p=>validCode(p.code)),'UNKNOWN_PRODUCT_SCOPE');
 const selectedRows=groups.filter(p=>selected.has(p.code));
 check(selectedRows.length===selected.size,'AMBIGUOUS_SELECTED_SKU');
 const serials=new Set();
 for(const p of selectedRows){
  check(validCode(p.name)&&quantity(p.reportedQuantity),'INVALID_SOURCE_PRODUCT');let sum=0;
  for(const s of p.serials){check(validCode(s.code)&&!serials.has(s.code)&&s.valid===true&&quantity(s.quantity),'DUPLICATE_OR_INVALID_SERIAL');serials.add(s.code);sum+=s.quantity;}
  check(quantity(sum)&&sum===p.reportedQuantity,'SUMMARY_MISMATCH');
 }
 check(groups.filter(p=>!selected.has(p.code)).every(p=>p.serials.every(s=>!serials.has(s.code?.trim()))),'CROSS_PRODUCT_SERIAL_CONFLICT');
 check(source.issues.every(i=>LOCAL.has(i.kind)&&validCode(i.productCode)&&!selected.has(i.productCode)&&!serials.has(i.serialCode?.trim())),'UNPROVEN_ISSUE_ISOLATION');
 if(source.issues.length){const s=source.structure;check(s?.xlsxValidated===true&&s.sheets>0&&s.sheetsWithHeaders===s.sheets&&s.uninterpretedRows===0&&s.preambleRows===0,'STRUCTURAL_SCOPE_UNPROVEN');}
 return {schemaVersion:1,sourceHash:hash(source),issuesHash:hash(source.issues),skus:[...selected].sort(),noAbsenceInference:true};
}

/** Unprivileged proposal compiler. Its output STILL needs independent signed approval and live revalidation. */
export function prepareAdministrativeContract(original,targets=null){
 const c=structuredClone(original),b=c.comparison;
 check(c.schemaVersion===1&&c.plan.schemaVersion===4&&c.plan.inventoryPolicy==='EXCEL_REFERENCE_V1'&&c.plan.planId===planIdentity(c)&&c.plan.operations.every(o=>o.operationId===operationIdentity(o)),'INVALID_OR_TAMPERED_ANDROID_PLAN');
 check(b.complete&&fingerprint(b)===c.plan.baselineFingerprint,'INCOMPLETE_OR_CHANGED_BASELINE');validateIdentities(b);
 targets??=Object.fromEntries(c.source.products.map(p=>[p.code,{stockExcel:p.reportedQuantity,serialTargets:Object.fromEntries(p.serials.map(s=>[s.code,s.quantity]))}]));
 const codes=Object.keys(targets);check(codes.length>0,'NO_SELECTED_PRODUCTS');
 const scope=independentSourceScope(c,codes),operations=[];
 function emit(type,path,pid,sku,serial,before,expected,version){
  const [collection,documentId]=path.split('/');const o={type,collection,documentId,path,productId:pid,sku,serial,before,expected,observedVersion:version,
   reason:'Inventario de referencia: cantidades válidas del Excel, sin descuentos históricos. Requiere aprobación administrativa firmada.',validation:type==='SKIP_UNCHANGED'?'SKIPPED':'CANDIDATE',risk:type==='SKIP_UNCHANGED'?'NONE':'HIGH',blockers:[],references:[],atomicGroup:`product:${pid}`};
  o.operationId=operationIdentity(o);operations.push(o);
 }
 for(const sku of codes){
  const p=c.source.products.find(p=>p.code===sku),existing=b.products.find(p=>p.code===sku),pid=existing?.id??productId(sku),t=targets[sku];
  check(hash(Object.keys(t.serialTargets).sort())===hash(p.serials.map(s=>s.code).sort()),'TARGET_SERIAL_SET_CHANGED');
  check(p.serials.every(s=>quantity(t.serialTargets[s.code])&&t.serialTargets[s.code]===s.quantity),'TARGET_MUST_EQUAL_EXCEL');
  const total=Object.values(t.serialTargets).reduce((a,n)=>a+n,0);check(quantity(total)&&total===p.reportedQuantity&&total===(t.stockExcel??t.stockConciliado),'TARGET_TOTAL_MISMATCH');
  if(!existing){check(!b.products.some(p=>p.id===pid),'PRODUCT_ID_COLLISION');emit('CREATE_PRODUCT',`productos/${pid}`,pid,sku,null,{}, {codigo:sku,nombre:p.name,stock:total,estado:'ACTIVO'},null);}
  else if(existing.stock!==total){check(existing.version,'MISSING_DOCUMENT_VERSION');emit('UPDATE_PRODUCT_STOCK',`productos/${pid}`,pid,sku,null,{stock:existing.stock},{stock:total},existing.version);}
  for(const row of p.serials){
   const e=b.existences.find(e=>e.code===row.code),q=t.serialTargets[row.code];
   if(e){check(e.productId===pid&&e.productCode===sku,'SERIAL_OTHER_PRODUCT');check(e.version,'MISSING_DOCUMENT_VERSION');
    emit(e.quantity===q?'SKIP_UNCHANGED':'UPDATE_EXISTENCE',`existencias/${e.id}`,pid,sku,row.code,
     {cantidad:e.quantity,estado:e.state,productoId:pid,codigoProducto:sku,codigo:row.code},e.quantity===q?{cantidad:q}:{cantidad:q,estado:state(q)},e.version);
   }else{const id=existenceId(pid,row.code);check(!b.existences.some(e=>e.id===id),'EXISTENCE_ID_COLLISION');emit('CREATE_EXISTENCE',`existencias/${id}`,pid,sku,row.code,{}, {productoId:pid,codigoProducto:sku,codigo:row.code,cantidad:q,estado:state(q)},null);}
  }
 }
 // Preserve informational absences even when compiling a selected, independently isolated scope.
 for(const o of original.plan.operations.filter(o=>['REVIEW_MISSING_EXISTENCE','REVIEW_MISSING_PRODUCT'].includes(o.type)))operations.push(structuredClone(o));
 c.preparation={schemaVersion:1,state:'AWAITING_SIGNED_APPROVAL',originalPlanId:original.plan.planId,originalPlanHash:hash(original.plan),scopeRequest:scope,productionExecutable:false};
 c.plan.operations=operations;c.plan.productionExecutable=false;c.plan.planId=planIdentity(c);
 return c;
}
