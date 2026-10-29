import { existenceId,productId,quantity,state,validCode,hash } from './canonical.mjs';

export const version=d=>d.updateTime?`${d.updateTime.seconds}:${d.updateTime.nanoseconds}`:null;
export const document=d=>({id:d.id,path:d.ref.path,data:d.data(),version:version(d)});
/** Scope is one valid source product. No absent product can enter this function. */
export function planProduct(source,products,entries,serialMatches,classification){
 const fail=reason=>({code:source.code,name:source.name,sheet:source.sheet,row:source.row,excluded:true,reason,operations:[]});
 if(source.critical||!validCode(source.code)||!source.name.trim()||!quantity(source.stock))return fail('Producto con incidencias críticas de origen.');
 if(!source.serials.length&&source.reportedQuantity!==0)return fail('No existe evidencia de stock cero ni detalle de series.');
 if(source.serials.length>1000||entries.length>1000)return fail('El producto supera el límite operativo de 1000 series por transacción.');
 if(products.length>1)return fail('Código duplicado en Firestore.');
 const previous=products[0],pid=previous?.id??productId(source.code);
 if(previous&&previous.data.estado!=='ACTIVO')return fail('Producto existente inactivo o sin estado válido.');
 const bySerial=new Map(),seen=new Set();let stock=0;
 for(const e of entries){
  if(!validCode(e.data.codigo)||e.data.productoId!==pid||e.data.codigoProducto!==source.code||!quantity(e.data.cantidad)||bySerial.has(e.data.codigo))return fail('Existencias actuales ambiguas o inconsistentes.');
  bySerial.set(e.data.codigo,e);
 }
 for(const s of source.serials){
  if(!validCode(s.code)||!quantity(s.quantity)||seen.has(s.code)||!quantity(stock+s.quantity))return fail('Series de origen inválidas.');seen.add(s.code);stock+=s.quantity;
  const matches=serialMatches.filter(e=>e.data.codigo===s.code);
  if(matches.length>1||matches.some(e=>e.data.productoId!==pid||e.data.codigoProducto!==source.code))return fail('Serie duplicada o asociada a otro producto en Firestore.');
 }
 if(stock!==source.stock||source.reportedQuantity!==null&&source.reportedQuantity!==stock)return fail('El total no coincide con las series validadas.');
 const operations=[],metadata=classification?.fields??{};
 const fields={codigo:source.code,nombre:source.name,stock,estado:'ACTIVO',...metadata};
 if(!previous)operations.push({type:'CREATE_PRODUCT',path:`productos/${pid}`,fields});
 else{
  const patch={};if(previous.data.stock!==stock)patch.stock=stock;
  if(typeof previous.data.nombre!=='string'||!previous.data.nombre.trim())patch.nombre=source.name;
  for(const [k,v] of Object.entries(metadata))if(v&&previous.data[k]!==v)patch[k]=v;
  if(Object.keys(patch).length)operations.push({type:'UPDATE_PRODUCT',path:previous.path,fields:patch});
 }
 for(const row of source.serials){
  const e=bySerial.get(row.code),fields={cantidad:row.quantity,estado:state(row.quantity)};
  if(!e)operations.push({type:'CREATE_EXISTENCE',path:`existencias/${existenceId(pid,row.code)}`,fields:{productoId:pid,codigoProducto:source.code,codigo:row.code,...fields}});
  else if(e.data.cantidad!==row.quantity||e.data.estado!==state(row.quantity))operations.push({type:'UPDATE_EXISTENCE',path:e.path,fields});
 }
 for(const e of entries)if(!seen.has(e.data.codigo)&&(e.data.cantidad!==0||e.data.estado!=='SIN_STOCK'))operations.push({type:'ZERO_EXISTENCE',path:e.path,fields:{cantidad:0,estado:'SIN_STOCK'}});
 if(previous&&operations.length&&!operations.some(o=>o.type==='UPDATE_PRODUCT'))operations.unshift({type:'UPDATE_PRODUCT',path:previous.path,fields:{stock}});
 if(operations.length>2001)return fail('Demasiadas escrituras para una transacción de producto.');
 const baseline=[...products,...entries].map(d=>({path:d.path,version:d.version}));
 return {code:source.code,name:source.name,sheet:source.sheet,row:source.row,productId:pid,excluded:false,operations,baseline,serialCodes:source.serials.map(s=>s.code),classification:classification??{fields:{},pending:['categoriaId','marcaId','descripcion']},digest:hash({source,baseline,operations})};
}

export const emptyCounters=()=>({productsCreated:0,productsUpdated:0,productsClassified:0,classificationPending:0,seriesCreated:0,seriesUpdated:0,seriesZeroed:0,productsUnchanged:0,productsOmitted:0,syncErrors:0});
export function countersFor(plan){const c=emptyCounters();if(plan.excluded){c.productsOmitted=1;return c;}
 for(const op of plan.operations){if(op.type==='CREATE_PRODUCT')c.productsCreated++;if(op.type==='UPDATE_PRODUCT')c.productsUpdated++;if(op.type==='CREATE_EXISTENCE')c.seriesCreated++;if(op.type==='UPDATE_EXISTENCE')c.seriesUpdated++;if(op.type==='ZERO_EXISTENCE')c.seriesZeroed++;}
 if(!plan.operations.length)c.productsUnchanged=1;
 if(plan.classification.pending.length)c.classificationPending=1;
 if(!plan.classification.pending.length&&plan.operations.some(o=>['CREATE_PRODUCT','UPDATE_PRODUCT'].includes(o.type)&&['categoriaId','marcaId','descripcion'].some(k=>Object.hasOwn(o.fields,k))))c.productsClassified=1;
 return c;
}
