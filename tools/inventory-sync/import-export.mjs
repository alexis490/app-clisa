// Read-only projection of archived evidence. Never reads current inventory or credentials.
const number=v=>Number.isSafeInteger(v)&&v>=0?v:null;
const text=v=>typeof v==='string'?v:null;
const errors=new Set(['WRITE_FAILED','PERMISSION_CHANGED','INVENTORY_CHANGED','SERIAL_CHANGED','IDENTITY_COLLISION','CONFIRMATION_REQUIRED']);
export const exportIssue=i=>({sheet:text(i.sheet),row:Number.isInteger(i.row)&&i.row>0?i.row:null,code:text(i.productCode),name:text(i.productName),serial:text(i.serial),type:text(i.type),description:text(i.description),reason:text(i.reason)});
export function exportUnit(u,unitId){
 const p=u.source??{},plan=u.plan,receipt=u.receipt;
 const state={APPLIED:'COMPLETADO',OMITTED:'OMITIDO',FAILED:'FALLIDO'}[receipt?.status]??'NO_DISPONIBLE';
 const previous=u.input?.products,exists=previous?.length===1?true:previous?.length===0&&!plan?.excluded?false:null;
 const before=exists===true?number(previous[0].data?.stock):null;
 const after=state==='COMPLETADO'&&plan?.excluded===false?number(p.stock):null;
 const knownOps=Array.isArray(plan?.operations),ops=knownOps?plan.operations:[];
 const productType=ops.find(o=>['CREATE_PRODUCT','UPDATE_PRODUCT'].includes(o.type))?.type;
 const product={code:text(p.code),name:text(p.name),store:null,previousExists:exists,beforeStock:before,newStock:after,
  difference:before!==null&&after!==null?after-before:null,type:state==='OMITIDO'?'OMITIR_PRODUCTO':productType??(ops.length?'ACTUALIZAR_EXISTENCIAS':knownOps?'SIN_CAMBIOS':null),state};
 const reason=state==='FALLIDO'?(errors.has(receipt.reason)?receipt.reason:'DETALLE_NO_DISPONIBLE'):state==='OMITIDO'?text(plan?.reason):null;
 const operations=ops.map(o=>{const match=/^(productos|existencias)\/([^/]+)$/.exec(o.path??'');return {collection:match?.[1]??null,id:match?.[2]??null,type:text(o.type),state,error:reason};});
 if(!operations.length)operations.push({collection:state==='OMITIDO'||state==='FALLIDO'?'importaciones_excel':exists===true?'productos':null,
  id:state==='OMITIDO'||state==='FALLIDO'?unitId:text(plan?.productId),type:state==='OMITIDO'?'OMITIR_PRODUCTO':knownOps?'SIN_CAMBIOS':null,state,error:reason});
 const issues=(u.issues??[]).map(exportIssue);
 if(plan?.excluded&&(!issues.length||p.serialCount!=null))issues.push(exportIssue({sheet:p.sheet,row:p.row,productCode:p.code,productName:p.name,
  type:p.serialCount!=null?'PRODUCT_LIMIT':'DATABASE_CONFLICT',description:plan.reason??'Producto apartado.',reason:plan.reason??'No se aplicaron operaciones a este producto.'}));
 return {product,operations,issues};
}
