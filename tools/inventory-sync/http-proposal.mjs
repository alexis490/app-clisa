import { Worker } from 'node:worker_threads';
export class HttpError extends Error { constructor(status,code){super(code);this.status=status;this.code=code;} }
export function prepareInWorker({contract,excel,uid,config,signal}){
 return new Promise((resolve,reject)=>{
  const worker=new Worker(new URL('./http-proposal-worker.mjs',import.meta.url),{
   workerData:{contractBytes:contract,excelBytes:excel,uid,maxResultBytes:config.contractBytes},env:{},
   resourceLimits:{maxOldGenerationSizeMb:192,maxYoungGenerationSizeMb:32,stackSizeMb:2}});
  let settled=false;
  const finish=async(error,result)=>{
   if(settled)return;settled=true;clearTimeout(timer);signal.removeEventListener('abort',abort);
   await worker.terminate();if(error)reject(error);else resolve(result);
  };
  const abort=()=>void finish(new HttpError(504,'REQUEST_TIMEOUT'));
  const timer=setTimeout(abort,config.prepareTimeoutMs);timer.unref();
  signal.addEventListener('abort',abort,{once:true});
  worker.once('error',()=>void finish(new HttpError(422,'PREPARATION_LIMIT_EXCEEDED')));
  worker.once('exit',code=>{if(!settled)void finish(new HttpError(422,'PREPARATION_FAILED'));});
  worker.once('message',message=>{
   if(message.error)void finish(new HttpError(message.error==='REQUESTER_MISMATCH'?403:message.error==='PROPOSAL_TOO_LARGE'?413:422,message.error));
   else void finish(null,message.result);
  });
  if(signal.aborted)abort();
 });
}
