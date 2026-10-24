import { hash } from './canonical.mjs';

/** Review only: no amount is added/subtracted and no boolean supplied by Android can authorize execution. */
export function reviewReconciliation({fileSha256,productId,declaredExportMillis,movements=[],exportEvidence=null}){
  const blockers=['TRUSTED_EXPORT_AND_RECONCILIATION_VERIFIER_NOT_CONFIGURED'];
  if(!/^[a-f0-9]{64}$/.test(fileSha256??''))blockers.push('SOURCE_IDENTITY_UNKNOWN');
  if(!productId)blockers.push('PRODUCT_IDENTITY_UNKNOWN');
  if(!Number.isSafeInteger(declaredExportMillis))blockers.push('EXPORT_TIME_UNKNOWN');
  const rows=movements.map(m=>{
    const formalization=Object.hasOwn(m,'estadoFormalizacion')?m.estadoFormalizacion:'SIN_VERIFICAR';
    const confirmed=m.formalizadoEnMillis;
    const relative=Number.isSafeInteger(confirmed)&&Number.isSafeInteger(declaredExportMillis)?declaredExportMillis<confirmed?'EXCEL_BEFORE_CONFIRMATION':declaredExportMillis>confirmed?'EXCEL_AFTER_CONFIRMATION':'SAME_DECLARED_INSTANT':'UNKNOWN';
    return {registroId:m.id??null,productId:m.productoId??null,formalization,relative,includedInExport:'UNPROVEN',decision:'BLOCKED',
      reason:'La fecha y el estado de formalización no acreditan inclusión o exclusión en el archivo.'};
  });
  if(rows.length)blockers.push('MOVEMENTS_NOT_RECONCILED');
  if(exportEvidence?.kind==='EMULATOR_FIXTURE_ONLY')blockers.push('FIXTURE_EVIDENCE_NOT_VALID_FOR_PRODUCTION');
  return {schemaVersion:1,status:'BLOQUEADO',productionExecutable:false,fileSha256,productId,
    movementSetHash:hash(movements),rows,blockers,
    evidenceRequired:['Exportación identificada, firmada y vinculada al SHA-256 y alcance sin filtros',
      'Identificador externo de cada traspaso y comprobante de inclusión/exclusión en esa exportación',
      'Identidades exactas de producto/series y versiones nativas actuales',
      'Protocolo de exclusión de escritores y reconciliación de todos los movimientos relevantes']};
}
