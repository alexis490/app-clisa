import { quantity } from './canonical.mjs';

const rowKey=(sheet,row)=>JSON.stringify([sheet,row]);
const issueKey=i=>JSON.stringify([i.type,i.sheet,i.row,i.productCode,i.serial]);
export function productRecordRows(p){return new Set([rowKey(p.sheet,p.row),...p.serials.map(s=>rowKey(s.sheet,s.row))]);}

/** Summarizes saved comparison evidence. It cannot write inventory or classify products. */
export function reviewAccumulator(job,sourceIssues){
 const codes=new Set(),rows=new Set(),extraIssues=[],seen=new Set(sourceIssues.map(issueKey));
 const comparison={unchanged:0,increased:0,decreased:0,newProducts:0,absent:null,unknownStock:0};
 let validRecords=0,separatedRecords=0,recordCountsKnown=true,validProducts=0;
 function addIssue(issue){const key=issueKey(issue);if(!seen.has(key)){seen.add(key);extraIssues.push(issue);}}
 function add(unit){
  const p=unit.source,plan=unit.plan;if(!plan)throw Error('REVIEW_NOT_READY');
  if(typeof p.code==='string'&&p.code.trim())codes.add(p.code);
  const recordRows=productRecordRows(p);for(const key of recordRows)rows.add(key);
  const count=unit.recordRowCount??recordRows.size;
  if(p.serialCount!=null&&unit.recordRowCount==null)recordCountsKnown=false;
  if(plan.excluded){
   separatedRecords+=count;
   if(p.serialCount!=null)addIssue({type:'PRODUCT_LIMIT',productCode:p.code,productName:p.name,sheet:p.sheet,row:p.row,serial:null,description:'Producto apartado por superar el tamaño operativo seguro.',reason:'Su inventario se conserva íntegramente.'});
   else if(!p.critical)addIssue({type:'DATABASE_CONFLICT',productCode:p.code,productName:p.name,sheet:p.sheet,row:p.row,serial:null,description:plan.reason||'No se pudo verificar el producto con Firebase.',reason:'Producto apartado íntegramente; su inventario se conserva.'});
   return;
  }
  validProducts++;validRecords+=count;
  const previous=unit.input?.products?.[0]?.data;
  if(!previous)comparison.newProducts++;
  else if(!quantity(previous.stock))comparison.unknownStock++;
  else if(p.stock>previous.stock)comparison.increased++;
  else if(p.stock<previous.stock)comparison.decreased++;
  else comparison.unchanged++;
 }
 function finish(absent){
  comparison.absent=absent;
  const orphanRows=new Set(sourceIssues.filter(i=>Number.isInteger(i.row)&&!rows.has(rowKey(i.sheet,i.row))).map(i=>rowKey(i.sheet,i.row)));
  separatedRecords+=orphanRows.size;
  const incidents=[...sourceIssues,...extraIssues];
  const negativeOnly=!incidents.some(i=>i.type==='INVALID_QUANTITY'&&typeof i.negative!=='boolean');
  const quality={duplicateCodes:0,duplicateSeries:0,emptySeries:0,negativeQuantities:0,otherErrors:0,negativeOnly};
  for(const i of incidents){
   if(i.type==='DUPLICATE_SKU')quality.duplicateCodes++;
   else if(i.type==='DUPLICATE_SERIAL')quality.duplicateSeries++;
   else if(i.type==='INVALID_SERIAL'&&!String(i.serial??'').trim())quality.emptySeries++;
   else if(i.type==='INVALID_QUANTITY'&&(!negativeOnly||i.negative))quality.negativeQuantities++;
   else quality.otherErrors++;
  }
  return {report:{rowsProcessed:job.rowCount,uniqueProducts:codes.size,newProducts:comparison.newProducts,validProducts,issueCount:incidents.length,quality,comparison,
   validation:{validRecords:recordCountsKnown?validRecords:null,separatedRecords:recordCountsKnown?separatedRecords:null}},extraIssues};
 }
 return {add,finish,codes};
}
