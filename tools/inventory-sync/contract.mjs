import { verify } from 'node:crypto';
import { canonical, hash, sha, check, validCode, validId, quantity } from './canonical.mjs';
import { independentSourceScope } from './preparation.mjs';

export const MUTATIONS=new Set(['CREATE_PRODUCT','CREATE_EXISTENCE','UPDATE_EXISTENCE','UPDATE_PRODUCT_STOCK','DELETE_EXISTENCE']);
export const POLICY='EXCEL_REFERENCE_V1';
export const TYPES=new Set([...MUTATIONS,'SKIP_UNCHANGED','BLOCK_CONFLICT','REVIEW_MISSING_PRODUCT','REVIEW_MISSING_EXISTENCE']);
export const operationIdentity=o=>hash([o.type,o.collection,o.documentId,o.path,o.sku,o.serial,o.productId,o.before,o.expected,
  o.reason,o.validation,o.risk,[...o.blockers].sort(),[...o.references].sort(),o.atomicGroup,o.observedVersion]);
export const planIdentity=c=>'dryrun-'+hash(['CLISA_EXCEL_REFERENCE_V1',c.plan.fileSha256,c.plan.baselineFingerprint,
  c.source.completeness.manifestDigest,c.plan.temporalIdentity,c.plan.operations.map(o=>o.operationId),c.plan.checks.map(x=>[x.code,x.satisfied])]);
export function validateContract(contract,bytes,approval,trust,now){
  check(contract.schemaVersion===1&&contract.plan.schemaVersion===4&&contract.plan.inventoryPolicy===POLICY,'LEGACY_INVENTORY_POLICY_REQUIRES_NEW_PLAN');
  const p=contract.plan;
  check(/^dryrun-[a-f0-9]{64}$/.test(p.planId)&&p.fileSha256===sha(bytes),'SOURCE_HASH_MISMATCH');
  check(Number.isSafeInteger(p.observedAtMillis)&&p.observedAtMillis<=now&&now-p.observedAtMillis<=15*60_000,'PLAN_EXPIRED');
  check(Buffer.byteLength(canonical(contract))<=8*1024*1024,'CONTRACT_TOO_LARGE');
  const a=approval?.payload,key=trust[approval?.keyId];
  check(a&&key&&verify(null,Buffer.from(canonical(a)),key,Buffer.from(approval.signature,'base64')),'TRUSTED_APPROVAL_REQUIRED');
  check(a.contractHash===hash(contract)&&a.fileSha256===p.fileSha256&&a.sourceHash===hash(contract.source),'PLAN_TAMPERED');
  check(p.operations.every(o=>o.operationId===operationIdentity(o))&&p.planId===planIdentity(contract),'PLAN_IDENTITY_MISMATCH');
  check(a.requesterUid===contract.requester.uid&&validId(a.requesterUid),'REQUESTER_MISMATCH');
  check(a.issuedAt<=now&&a.expiresAt>=now&&a.expiresAt-a.issuedAt<=15*60_000,'APPROVAL_EXPIRED');
  // Signed administrative authorization is retained; transfer/export-inclusion evidence is not required.
  check(a.evidenceKind==='EMULATOR_FIXTURE_ONLY','REAL_EXPORT_AUTHORITY_NOT_IMPLEMENTED');
  check(p.operations.length<=30_000&&Array.isArray(a.allowedOperationIds),'INVALID_OPERATIONS');
  const ids=new Set(),paths=new Set();
  for(const op of p.operations){
    check(/^[a-f0-9]{64}$/.test(op.operationId)&&!ids.has(op.operationId),'DUPLICATE_OR_INVALID_OPERATION_ID');ids.add(op.operationId);
    check(TYPES.has(op.type)&&Array.isArray(op.blockers),'INVALID_OPERATION');
    if(MUTATIONS.has(op.type)){
      check(validId(op.productId)&&op.atomicGroup===`product:${op.productId}`&&validCode(op.sku),'INVALID_ATOMIC_GROUP');
      check(validId(op.documentId)&&op.path===`${op.collection}/${op.documentId}`&&['productos','existencias'].includes(op.collection),'INVALID_PATH');
      check(!paths.has(op.path),'MULTIPLE_OPERATIONS_SAME_PATH');paths.add(op.path);
    }
  }
  check(new Set(a.allowedOperationIds).size===a.allowedOperationIds.length,'DUPLICATE_AUTHORIZATION');
  const allowed=p.operations.filter(o=>a.allowedOperationIds.includes(o.operationId));
  check(allowed.length===a.allowedOperationIds.length,'UNKNOWN_AUTHORIZED_OPERATION');
  for(const op of allowed){
    check(op.type!=='DELETE_EXISTENCE','DELETE_DISABLED');
    check(op.type==='SKIP_UNCHANGED'||(MUTATIONS.has(op.type)&&op.validation==='CANDIDATE'&&op.blockers.length===0),'BLOCKED_OPERATION');
  }
  const groups=new Set(allowed.filter(o=>MUTATIONS.has(o.type)).map(o=>o.atomicGroup));
  for(const group of groups){
    const all=p.operations.filter(o=>o.atomicGroup===group);
    check(all.every(o=>!['BLOCK_CONFLICT','REVIEW_MISSING_PRODUCT','DELETE_EXISTENCE'].includes(o.type)&&(!MUTATIONS.has(o.type)||a.allowedOperationIds.includes(o.operationId))),'ATOMIC_GROUP_BLOCKED');
  }
  check(contract.comparison.complete===true,'INCOMPLETE_BASELINE');
  const scoped=contract.source.issues.length>0;
  if(scoped){
    const codes=[...new Set(allowed.filter(o=>MUTATIONS.has(o.type)).map(o=>o.sku))];
    const expected=independentSourceScope(contract,codes),scope=a.independentScope;
    check(scope&&hash(scope.scope)===hash(expected)&&scope.reference?.startsWith('fixture:')&&a.allowPartial===true,'SOURCE_HAS_ISSUES');
  }else check(contract.source.errorCount===0,'SOURCE_HAS_ISSUES');
  check(!p.operations.some(o=>o.type==='BLOCK_CONFLICT'&&o.atomicGroup===null),'GLOBAL_PLAN_BLOCKED');
  check(scoped||(contract.source.completeness.technicalValid===true&&contract.source.completeness.state!=='INCOMPLETE'),'SOURCE_INCOMPLETE');
  const skus=new Set(),serials=new Set();
  for(const product of contract.source.products.filter(p=>!scoped||allowed.some(o=>o.sku===p.code))){
    check(validCode(product.code)&&!skus.has(product.code),'DUPLICATE_OR_INVALID_SKU');skus.add(product.code);
    check(validCode(product.name)&&quantity(product.reportedQuantity),'INVALID_SOURCE_PRODUCT');
    let total=0;
    for(const row of product.serials){
      check(validCode(row.code)&&!serials.has(row.code),'DUPLICATE_OR_INVALID_SERIAL');serials.add(row.code);
      check(row.valid===true&&quantity(row.quantity),'INVALID_SOURCE_QUANTITY');total+=row.quantity;
    }
    check(quantity(total)&&total===product.reportedQuantity,'SUMMARY_MISMATCH');
  }
  const b=a.budget;
  check(b&&b.measuredAt<=now&&now-b.measuredAt<=5*60_000&&['reads','writes','deletes','reserveReads','reserveWrites'].every(k=>quantity(b[k])),'QUOTA_BALANCE_UNKNOWN');
  check(b.reads<=50_000&&b.writes<=20_000&&b.deletes<=20_000,'INVALID_QUOTA_BALANCE');
  const actionable=p.operations.filter(o=>!['SKIP_UNCHANGED','REVIEW_MISSING_PRODUCT','REVIEW_MISSING_EXISTENCE'].includes(o.type));
  check(actionable.every(o=>allowed.includes(o))||a.allowPartial===true,'PARTIAL_SCOPE_NOT_ACKNOWLEDGED');
  return {allowed,groups:[...groups].sort(),authorization:a,scopedSourceValidated:scoped,contractHash:hash(contract),sourceReplayKey:hash(['CLISA_SYNC_4B1',p.fileSha256,hash(contract.source)])};
}

export function estimate(contract,selection){
  const mutations=selection.allowed.filter(o=>MUTATIONS.has(o.type));
  const n=selection.groups.length, baseline=contract.comparison.products.length+contract.comparison.existences.length;
  // Conservative reservation: baseline, bounded transaction retries, identities and authority. No history scan.
  const targetReads=selection.groups.reduce((s,g)=>s+contract.comparison.existences.filter(e=>`product:${e.productId}`===g).length+10,0);
  return {inventoryWrites:mutations.length,auditWrites:4*n+8,reads:baseline+18*targetReads+100,
    writes:2*mutations.length+4*n+30,deletes:0,additionalQueries:Math.ceil(baseline/500)+10*n+10,
    maxTransactionAttempts:3,historyDocumentLimit:0};
}
