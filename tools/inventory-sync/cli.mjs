// No project/host override, deployment command, HTTP server or production mode exists in this phase.
import { readFile,mkdir,writeFile } from 'node:fs/promises';
import { createPublicKey,randomUUID } from 'node:crypto';
import { join } from 'node:path';
import { createEmulatorRuntime,assertEmulator } from './runtime.mjs';
import { InventorySyncEngine } from './engine.mjs';
import { serializable } from './inventory.mjs';
import { check,sha } from './canonical.mjs';
import { prepareAdministrativeContract } from './preparation.mjs';
import { previewClassification,applyClassification } from './classification.mjs';

let runtime;
try {
  assertEmulator();
  const args=process.argv.slice(2),options={};
  for(let i=0;i<args.length;i+=2){check(/^--[a-z-]+$/.test(args[i])&&args[i+1]&&!Object.hasOwn(options,args[i]),'INVALID_ARGUMENTS');options[args[i]]=args[i+1];}
  check(Object.keys(options).every(k=>['--contract','--excel','--approval','--public-key','--output','--run-id','--recover','--supervised','--targets','--prepare','--classification','--id-token-file','--apply-classification'].includes(k)),'UNKNOWN_ARGUMENT');
  if(options['--classification']){
    check(Object.keys(options).every(k=>['--classification','--id-token-file','--apply-classification','--output'].includes(k))&&options['--output'],'CLASSIFICATION_ARGUMENTS');
    const bytes=await readFile(options['--classification']);check(bytes.length<=8*1024*1024,'CLASSIFICATION_TOO_LARGE');
    const input=JSON.parse(bytes),manifest=Object.hasOwn(input,'classification')?(typeof input.classification==='string'?JSON.parse(input.classification):input.classification):input;
    runtime=createEmulatorRuntime();let result;
    if(options['--apply-classification']){
      check(options['--apply-classification']==='true'&&options['--id-token-file'],'EXPLICIT_METADATA_APPROVAL_REQUIRED');
      const token=await readFile(options['--id-token-file'],'utf8');check(token.length<=16384,'TOKEN_TOO_LARGE');
      result=await applyClassification(runtime,manifest,{idToken:token.trim()});
    }else result=await previewClassification(runtime,manifest);
    await mkdir(options['--output'],{recursive:true});await writeFile(join(options['--output'],'classification-result.json'),JSON.stringify(result,null,2));
    console.log(JSON.stringify({environment:'EMULATOR',production:false,stockOperations:0}));
  }else{
  const preparing=options['--targets']||options['--prepare'];if(options['--prepare'])check(options['--prepare']==='true','INVALID_PREPARE');
  for(const name of preparing?['--contract','--excel','--output']:['--contract','--excel','--approval','--public-key','--output'])check(options[name],'MISSING_'+name);
  const contractBytes=await readFile(options['--contract']);check(contractBytes.length<=8*1024*1024,'CONTRACT_TOO_LARGE');
  const contract=JSON.parse(contractBytes);
  if(preparing){
    const sourceBytes=await readFile(options['--excel']);check(sourceBytes.length<=25*1024*1024&&sha(sourceBytes)===contract.plan.fileSha256,'SOURCE_HASH_MISMATCH');
    const targets=options['--targets']?JSON.parse(await readFile(options['--targets'],'utf8')):null;const proposal=prepareAdministrativeContract(contract,targets);
    await mkdir(options['--output'],{recursive:true});await writeFile(join(options['--output'],'proposal.json'),JSON.stringify(proposal,null,2));
    console.log('PROPOSAL_ONLY: Excel quantities, signed administrative approval required. No writes.');
  }else{
  const approval=JSON.parse(await readFile(options['--approval'],'utf8'));
  const key=createPublicKey(await readFile(options['--public-key'])),bytes=await readFile(options['--excel']);
  runtime=createEmulatorRuntime();const engine=new InventorySyncEngine(runtime,{trust:{operator:key}});
  const runId=options['--recover']??options['--run-id']??'run_'+randomUUID();
  await mkdir(options['--output'],{recursive:true});
  await writeFile(join(options['--output'],'request.json'),JSON.stringify({runId,contract,approval},null,2));
  try {
    const request={contract,bytes,approval,runId};
    const result=options['--recover']?await engine.recover({...request,runId:options['--recover'],supervised:options['--supervised']==='true'}):await engine.start(request);
    await writeFile(join(options['--output'],'result.json'),JSON.stringify(serializable(result),null,2));
    await writeFile(join(options['--output'],'simulation-summary.json'),JSON.stringify({schemaVersion:1,environment:'EMULATOR',production:false,
      fileSha256:contract.plan.fileSha256,planId:contract.plan.planId,sourcePlanId:contract.preparation?.originalPlanId??contract.plan.planId,runId:result.runId,status:result.status,
      appliedGroups:result.appliedGroups,authorizedCount:result.authorizedCount,blockedCount:result.blockedCount,auditPath:`importaciones/${result.runId}`},null,2));
    console.log(JSON.stringify({runId:result.runId,status:result.status,production:false}));
  }catch(error){
    let failed=null;
    if(/^run_[A-Za-z0-9_-]{1,80}$/.test(runId))try{const doc=await runtime.db.doc(`importaciones/${runId}`).get();if(doc.exists)failed=doc.data();}catch{}
    await writeFile(join(options['--output'],'simulation-summary.json'),JSON.stringify({schemaVersion:1,environment:'EMULATOR',production:false,
      fileSha256:contract.plan.fileSha256,planId:contract.plan.planId,sourcePlanId:contract.preparation?.originalPlanId??contract.plan.planId,
      runId,status:failed?.status==='PARTIAL'?'PARTIAL':'FAILED',appliedGroups:failed?.appliedGroups??0,error:error.message},null,2));
    await writeFile(join(options['--output'],'error.json'),JSON.stringify({error:error.message,runId:error.runId??runId,production:false},null,2));throw error;
  }
  }
  }
}catch(error){console.error(error.message);process.exitCode=1;}
finally{await runtime?.close();}
