import { parentPort,workerData } from 'node:worker_threads';
import { prepareAdministrativeContract } from './preparation.mjs';
import { sha } from './canonical.mjs';

// CPU/memory isolated. No Auth token, credentials, filesystem persistence or inventory executor.
try{
 const {contractBytes,excelBytes,uid,maxResultBytes}=workerData;
 const c=JSON.parse(new TextDecoder('utf-8',{fatal:true}).decode(contractBytes));
 if(c?.requester?.uid!==uid){parentPort.postMessage({error:'REQUESTER_MISMATCH'});}
 else if(c?.plan?.fileSha256!==sha(Buffer.from(excelBytes))){parentPort.postMessage({error:'SOURCE_HASH_MISMATCH'});}
 else{
  const proposal=prepareAdministrativeContract(c);
  const result=JSON.stringify({mode:'SAFE_PREVIEW',inventoryWritesEnabled:false,authorization:false,proposal});
  parentPort.postMessage(Buffer.byteLength(result)<=maxResultBytes?{result}:{error:'PROPOSAL_TOO_LARGE'});
 }
}catch{parentPort.postMessage({error:'INVALID_PROPOSAL_SOURCE'});}
