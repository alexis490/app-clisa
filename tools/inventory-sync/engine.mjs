import { randomUUID } from 'node:crypto';
import { FieldValue } from 'firebase-admin/firestore';
import { assertEmulator } from './runtime.mjs';
import { validateContract, estimate, MUTATIONS, POLICY } from './contract.mjs';
import { check, hash, quantity } from './canonical.mjs';
import { scan, baseline, fingerprint, validateIdentities, buildUnit, stamp, serializable } from './inventory.mjs';

const CONTROL='importaciones/_control_clisa';
const txOptions={maxAttempts:3};
const authorizationScope=selection=>hash(selection.allowed.filter(o=>MUTATIONS.has(o.type)).map(o=>o.operationId).sort());
export class InventorySyncEngine {
  constructor(runtime,{trust,clock=Date.now,fault=()=>{}}){assertEmulator(runtime);this.runtime=runtime;this.db=runtime.db;this.trust=trust;this.clock=clock;this.fault=fault;}
  async authority(uid){
    assertEmulator(this.runtime);
    const user=await this.runtime.auth.getUser(uid),c=user.customClaims??{};
    check(!user.disabled&&['ADMIN','SUPER_ADMIN'].includes(c.role)&&c.admin===true&&(c.role!=='SUPER_ADMIN'||c.superAdmin===true),'UNAUTHORIZED_ADMIN');
    return c.role;
  }
  profile(snapshot,role){check(snapshot.exists&&snapshot.get('estado')==='ACTIVO'&&snapshot.get('role')===role,'UNAUTHORIZED_PROFILE');}
  fence(control,runId,token){check(control.exists&&control.get('locked')===true&&control.get('runId')===runId&&control.get('fence')===token,'FENCED_OUT');}
  async start({contract,bytes,approval,runId='run_'+randomUUID()}){
    assertEmulator(this.runtime);check(/^run_[A-Za-z0-9_-]{1,80}$/.test(runId),'INVALID_RUN_ID');
    const selection=validateContract(contract,bytes,approval,this.trust,this.clock()),cost=estimate(contract,selection);
    const b=selection.authorization.budget;
    check(cost.reads+b.reserveReads<=b.reads&&cost.writes+b.reserveWrites<=b.writes,'QUOTA_INSUFFICIENT');
    check(selection.groups.length>0,'NO_AUTHORIZED_CHANGES');
    const uid=contract.requester.uid,role=await this.authority(uid),jobRef=this.db.doc(`importaciones/${runId}`),replayRef=this.db.doc(`importaciones/source_${selection.sourceReplayKey}`);
    let token,completedResult;
    await this.db.runTransaction(async tx=>{
      const [control,job,replay,profile]=await tx.getAll(this.db.doc(CONTROL),jobRef,replayRef,this.db.doc(`usuarios/${uid}`));this.profile(profile,role);
      if(job.exists){
        check(job.get('contractHash')===selection.contractHash,'RUN_ID_REUSED');
        if(['COMPLETED','REQUIRES_REVIEW'].includes(job.get('status'))){completedResult=job.data();return;}
        throw Error('RUN_EXISTS_USE_SUPERVISED_RECOVERY');
      }
      check(!control.exists||control.get('locked')===false,'IMPORT_LOCKED');check(!replay.exists,'SOURCE_ALREADY_RESERVED');
      token=(control.get('fence')??0)+1;check(quantity(token),'INVALID_FENCE');
      tx.set(this.db.doc(CONTROL),{locked:true,runId,fence:token,startedAt:FieldValue.serverTimestamp(),heartbeat:FieldValue.serverTimestamp()});
      tx.create(replayRef,{runId,contractHash:selection.contractHash,createdAt:FieldValue.serverTimestamp()});
      tx.create(jobRef,{status:'PREPARED',runId,fence:token,contractHash:selection.contractHash,planId:contract.plan.planId,fileSha256:contract.plan.fileSha256,
        requester:uid,createdAt:FieldValue.serverTimestamp(),startedAt:null,finishedAt:null,estimate:cost,authorizedCount:selection.allowed.length,
        omittedCount:contract.plan.operations.filter(o=>!selection.allowed.includes(o)&&!['SKIP_UNCHANGED','REVIEW_MISSING_PRODUCT','REVIEW_MISSING_EXISTENCE'].includes(o.type)).length,
        blockedCount:contract.plan.operations.filter(o=>!['REVIEW_MISSING_PRODUCT','REVIEW_MISSING_EXISTENCE'].includes(o.type)&&(o.blockers.length||['BLOCK_CONFLICT','DELETE_EXISTENCE'].includes(o.type))).length,
        retainedAbsenceCount:contract.plan.operations.filter(o=>['REVIEW_MISSING_PRODUCT','REVIEW_MISSING_EXISTENCE'].includes(o.type)).length,inventoryPolicy:POLICY,
        plannedGroups:selection.groups,authorizationScopeHash:authorizationScope(selection),approvalKeyId:approval.keyId,
        appliedGroups:0,errors:[],schemaVersion:3});
    },txOptions);
    if(completedResult)return completedResult;
    try{
      const products=await scan(this.db,'productos',contract.comparison.products.length),entries=await scan(this.db,'existencias',contract.comparison.existences.length);
      const live=baseline(products,entries);validateIdentities(live);
      check(fingerprint(live)===contract.plan.baselineFingerprint&&fingerprint(contract.comparison)===contract.plan.baselineFingerprint,'BASELINE_CHANGED');
      const units=selection.groups.map(g=>buildUnit(contract,selection,g.slice(8),products,entries));
      for(const unit of units)await this.db.runTransaction(async tx=>{
        const control=await tx.get(this.db.doc(CONTROL));this.fence(control,runId,token);
        tx.create(jobRef.collection('units').doc(unit.productId),{...unit,status:'PREPARED',digest:hash(unit)});
      },txOptions);
      await this.db.runTransaction(async tx=>{const c=await tx.get(this.db.doc(CONTROL));this.fence(c,runId,token);tx.update(jobRef,{status:'RUNNING',startedAt:FieldValue.serverTimestamp()});},txOptions);
      return await this.run({contract,selection,runId,token});
    }catch(error){await this.fail(runId,token,error);error.runId=runId;throw error;}
  }
  async run({contract,selection,runId,token}){
    const jobRef=this.db.doc(`importaciones/${runId}`),uid=contract.requester.uid;
    for(const group of selection.groups){
      const pid=group.slice(8),role=await this.authority(uid);
      await this.fault('beforeUnit',pid);
      await this.db.runTransaction(async tx=>{
        const [control,job,checkpoint,profile]=await tx.getAll(this.db.doc(CONTROL),jobRef,jobRef.collection('units').doc(pid),this.db.doc(`usuarios/${uid}`));
        this.fence(control,runId,token);this.profile(profile,role);check(job.get('contractHash')===selection.contractHash&&['RUNNING','PARTIAL','FAILED'].includes(job.get('status')),'INVALID_JOB');
        check(checkpoint.exists,'PREPARATION_INCOMPLETE');
        check(checkpoint.get('inventoryPolicy')===POLICY&&job.get('inventoryPolicy')===POLICY,'LEGACY_CHECKPOINT_REQUIRES_REVIEW');
        if(checkpoint.get('status')==='APPLIED'){
          const after=checkpoint.get('after'),docs=await tx.getAll(...Object.keys(after).map(p=>this.db.doc(p)));
          for(const doc of docs){const data=serializable(doc.data()??{});delete data.actualizadoEn;
            check(doc.exists&&hash(data)===after[doc.ref.path]&&stamp(doc.get('actualizadoEn'))===stamp(checkpoint.get('appliedAt')),'APPLIED_DOCUMENT_CHANGED');}
          return;
        }
        const unit=checkpoint.data(),{digest,status,...payload}=unit;
        check(hash(payload)===digest,'CHECKPOINT_TAMPERED');
        const paths=Object.keys(unit.beforeVersions),snapshots=paths.length?await tx.getAll(...paths.map(p=>this.db.doc(p))):[];
        const entries=await tx.get(this.db.collection('existencias').where('productoId','==',pid));
        const productMatches=await tx.get(this.db.collection('productos').where('codigo','==',unit.operations[0].sku));
        for(const op of unit.operations.filter(o=>o.type==='CREATE_EXISTENCE')){
          const serialMatches=await tx.get(this.db.collection('existencias').where('codigo','==',op.serial).limit(2));
          check(serialMatches.empty,'SERIAL_CREATED_CONCURRENTLY');
        }
        const knownEntries=paths.filter(p=>p.startsWith('existencias/')&&unit.beforeVersions[p]!==null).sort();
        check(hash(entries.docs.map(d=>d.ref.path).sort())===hash(knownEntries),'SERIAL_SET_CHANGED');
        check(productMatches.size===(unit.beforeVersions[`productos/${pid}`]===null?0:1)&&productMatches.docs.every(d=>d.id===pid),'SKU_CHANGED');
        for(const doc of snapshots)check((doc.exists?stamp(doc.updateTime):null)===unit.beforeVersions[doc.ref.path],'DOCUMENT_VERSION_CONFLICT');
        // Before any writes, recheck the whole unit and inject only deterministic test faults.
        await this.fault('insideUnit',pid);
        const after={};
        for(const op of unit.operations){
          const ref=this.db.doc(op.path),current=snapshots.find(d=>d.ref.path===op.path);
          const fields={...op.expected,actualizadoEn:FieldValue.serverTimestamp()};
          if(op.type.startsWith('CREATE_'))tx.create(ref,fields);else tx.update(ref,fields);
          const afterFields={...serializable(current?.data()??{}),...op.expected};delete afterFields.actualizadoEn;
          after[op.path]=hash(afterFields);
          tx.create(jobRef.collection('journal').doc(op.operationId),{operationId:op.operationId,type:op.type,path:op.path,before:op.before,expected:op.expected,
            runId,fence:token,requester:uid,fileSha256:contract.plan.fileSha256,status:'APPLIED',at:FieldValue.serverTimestamp()});
        }
        tx.update(checkpoint.ref,{status:'APPLIED',after,appliedAt:FieldValue.serverTimestamp(),fence:token});
        tx.update(jobRef,{appliedGroups:FieldValue.increment(1)});
        tx.update(this.db.doc(CONTROL),{heartbeat:FieldValue.serverTimestamp()});
      },txOptions);
      // A lost response after commit leaves the journal/checkpoint applied; recovery must not replay it.
      await this.fault('afterUnit',pid);
    }
    const role=await this.authority(uid);
    await this.db.runTransaction(async tx=>{
      const [c,j,p]=await tx.getAll(this.db.doc(CONTROL),jobRef,this.db.doc(`usuarios/${uid}`));this.fence(c,runId,token);this.profile(p,role);
      check(j.get('appliedGroups')===selection.groups.length,'CHECKPOINTS_INCOMPLETE');
      check(hash(j.get('plannedGroups'))===hash(selection.groups)&&j.get('authorizationScopeHash')===authorizationScope(selection),'EXECUTION_SCOPE_CHANGED');
      const blocking=j.get('blockedCount')>0||j.get('omittedCount')>0;
      const review=blocking||j.get('retainedAbsenceCount')>0;
      tx.update(jobRef,{status:review?'REQUIRES_REVIEW':'COMPLETED',finishedAt:FieldValue.serverTimestamp()});
      // Never release on partial/error/unresolved exclusions; expiry alone grants no ownership.
      if(!blocking)tx.update(c.ref,{locked:false,finishedAt:FieldValue.serverTimestamp()});
    },txOptions);
    return (await jobRef.get()).data();
  }
  async fail(runId,token,error){
    try{await this.db.runTransaction(async tx=>{
      const [c,j]=await tx.getAll(this.db.doc(CONTROL),this.db.doc(`importaciones/${runId}`));this.fence(c,runId,token);
      tx.update(j.ref,{status:j.get('appliedGroups')>0?'PARTIAL':'FAILED',errors:FieldValue.arrayUnion(String(error.message).slice(0,300)),failedAt:FieldValue.serverTimestamp()});
    },txOptions);}catch{/* Lost fencing ownership must not alter the new owner's journal. */}
  }
  async recover({contract,bytes,approval,runId,supervised}){
    assertEmulator(this.runtime);check(supervised===true,'SUPERVISION_REQUIRED');
    const selection=validateContract(contract,bytes,approval,this.trust,this.clock()),uid=contract.requester.uid,role=await this.authority(uid);
    let token;
    await this.db.runTransaction(async tx=>{
      const jobRef=this.db.doc(`importaciones/${runId}`),[c,j,p]=await tx.getAll(this.db.doc(CONTROL),jobRef,this.db.doc(`usuarios/${uid}`));this.profile(p,role);
      check(j.exists&&j.get('contractHash')===selection.contractHash&&j.get('requester')===uid,'RECOVERY_IDENTITY_MISMATCH');
      check(j.get('schemaVersion')===3&&j.get('inventoryPolicy')===POLICY&&j.get('authorizationScopeHash')===authorizationScope(selection)&&hash(j.get('plannedGroups'))===hash(selection.groups),'RECOVERY_SCOPE_CHANGED');
      check(c.get('locked')===true&&c.get('runId')===runId&&['PREPARED','RUNNING','PARTIAL','FAILED'].includes(j.get('status')),'RECOVERY_NOT_ALLOWED');
      check((j.get('recoveryCount')??0)<2,'RECOVERY_ATTEMPTS_EXHAUSTED');
      const checkpoints=await tx.get(jobRef.collection('units'));
      check(checkpoints.size===selection.groups.length,'PREPARATION_INCOMPLETE_REVIEW_REQUIRED');
      token=c.get('fence')+1;
      tx.update(c.ref,{fence:token,heartbeat:FieldValue.serverTimestamp()});
      tx.create(jobRef.collection('recoveries').doc(String(token)),{at:FieldValue.serverTimestamp(),requester:uid,previousFence:c.get('fence'),fence:token});
      tx.update(j.ref,{status:'RUNNING',fence:token,recoveryCount:FieldValue.increment(1)});
    },txOptions);
    try{return await this.run({contract,selection,runId,token});}catch(e){await this.fail(runId,token,e);throw e;}
  }
}
