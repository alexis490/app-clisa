import { check,hash } from './canonical.mjs';

const policies={
 MISSING_SKU:{scope:'FILE_SCOPE_UNKNOWN',impact:'No se puede identificar con seguridad el producto ni su inventario ausente.',resolution:'Corregir el SKU en el sistema principal y volver a exportar.',evidence:'Referencia comercial inequívoca del producto y nueva exportación trazable.'},
 SERIAL_WHITESPACE:{scope:'PRODUCT_IDENTITY',impact:'La serie literal difiere; normalizar podría colisionar con otra identidad.',resolution:'Revisar el literal en ambos sistemas; no recortar automáticamente.',evidence:'Serie exacta autorizada y búsqueda global de posibles colisiones.'},
 DUPLICATE_SERIAL:{scope:'SERIAL_IDENTITY_GROUP',impact:'Todas las apariciones y sus productos quedan ambiguos.',resolution:'Revisar todas las ocurrencias; no elegir ni sumar la primera.',evidence:'Identidad física, producto padre y trazabilidad de cada ocurrencia.'},
 DUPLICATE_SKU:{scope:'PRODUCT_IDENTITY_GROUP',impact:'Todos los grupos con ese SKU quedan bloqueados, sin fusionar nombres.',resolution:'Resolver duplicados en origen y exportar nuevamente.',evidence:'SKU único autorizado y revisión de todos sus grupos y referencias.'},
 NEGATIVE_QUANTITY:{scope:'PRODUCT_QUANTITY',impact:'Cantidad inválida que invalida el stock esperado de ese grupo.',resolution:'Aclarar/corregir el saldo en origen; no convertir a cero.',evidence:'Saldo respaldado por el sistema principal y exportación corregida.'}
};
export function buildIssueReview({fileSha256,issues}){
 check(/^[a-f0-9]{64}$/.test(fileSha256)&&Array.isArray(issues),'INVALID_ISSUE_SOURCE');
 const rows=issues.map((issue,index)=>{
  const policy=policies[issue.kind]??{scope:'FILE_STRUCTURE',impact:'Interpretación o identidad no demostrada.',resolution:'Revisión técnica del lector/origen sin omitir la fila.',evidence:'Archivo original, fila y criterio de interpretación verificables.'};
  return {issueId:hash(['CLISA_ISSUE_REVIEW_V1',fileSha256,index,issue]),fileSha256,
    sheet:issue.sheet,row:issue.row,productCode:issue.productCode??null,productName:issue.productName??null,serialCode:issue.serialCode??null,
    kind:issue.kind,originalMessage:issue.message,...policy,blocksProduct:true,blocksFileCompleteness:true,
    reviewState:'OPEN',proposedResolution:null,evidenceReferences:[],reviewEvents:[]};
 });
 return {schemaVersion:1,fileSha256,total:rows.length,byKind:Object.fromEntries([...new Set(rows.map(r=>r.kind))].sort().map(k=>[k,rows.filter(r=>r.kind===k).length])),
   status:'BLOQUEADO',sourceCompleteness:'INCOMPLETE',productionExecutable:false,
   localScopeKnown:rows.filter(r=>r.scope!=='FILE_SCOPE_UNKNOWN'&&r.scope!=='FILE_STRUCTURE').length,
   unknownScope:rows.filter(r=>r.scope==='FILE_SCOPE_UNKNOWN'||r.scope==='FILE_STRUCTURE').length,
   rows,warning:'Revisar o proponer una resolución no altera el archivo, no elimina la incidencia y no autoriza sincronizar o borrar.'};
}
