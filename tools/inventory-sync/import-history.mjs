import { FieldValue } from 'firebase-admin/firestore';

export const historyCollection='historial_importaciones';
export const validEmail=value=>typeof value==='string'&&value.length<=320&&/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(value);

/** A transport interruption is not a terminal inventory outcome. Only receipts decide. */
export function historyState(summary,issueCount=0){
 const applied=summary.productsCreated+summary.productsUpdated+summary.productsUnchanged;
 if(summary.syncErrors>0&&applied===0)return 'FALLIDO';
 return summary.syncErrors>0||summary.productsOmitted>0||summary.classificationPending>0||issueCount>0
  ?'COMPLETADO_CON_OBSERVACIONES':'COMPLETADO';
}
export function historyRecord(id,job){
 if(!validEmail(job.importUserEmail))throw Error('AUTH_EMAIL_REQUIRED');
 return {idImportacion:id,archivo:job.filename,fecha:FieldValue.serverTimestamp(),usuarioUid:job.uid,
  usuario:job.importUserEmail,destino:'Firebase Firestore',estado:historyState(job.summary,job.review?.issueCount??job.issueCount??0)};
}
export function historyView(data){
 if(!data)return null;
 return {idImportacion:data.idImportacion,archivo:data.archivo,fecha:data.fecha.toDate().toISOString(),
  usuarioUid:data.usuarioUid,usuario:data.usuario,destino:data.destino,estado:data.estado};
}
