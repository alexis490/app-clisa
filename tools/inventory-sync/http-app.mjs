import express from 'express';
import helmet from 'helmet';
import { rateLimit } from 'express-rate-limit';
import { randomUUID } from 'node:crypto';
import { HttpError,prepareInWorker } from './http-proposal.mjs';
import { readProposalUpload } from './http-upload.mjs';

const authenticatedFailures=new Set(['auth/argument-error','auth/invalid-id-token','auth/id-token-expired','auth/id-token-revoked','auth/user-not-found']);
const adminClaims=c=>['ADMIN','SUPER_ADMIN'].includes(c?.role)&&c.admin===true&&(c.role!=='SUPER_ADMIN'||c.superAdmin===true);
const reject=(res,status,code)=>res.status(status).json({error:{code},requestId:res.locals.requestId});
const finished=res=>res.writableEnded||res.destroyed;

export function createHttpApp({config,firebase=null,logger=()=>{}}){
 const app=express();app.disable('x-powered-by');app.set('trust proxy',config.trustProxyHops);
 app.use(helmet({contentSecurityPolicy:{directives:{defaultSrc:["'none'"],frameAncestors:["'none'"]}},frameguard:{action:'deny'}}));
 app.use((req,res,next)=>{
  res.locals.requestId=randomUUID();res.set('Cache-Control','no-store');res.set('X-Request-Id',res.locals.requestId);
  const start=Date.now(),controller=new AbortController();res.locals.signal=controller.signal;
  const timer=setTimeout(()=>{if(!finished(res)){res.set('Connection','close');reject(res,504,'REQUEST_TIMEOUT');controller.abort();}},config.requestTimeoutMs);timer.unref();
  res.once('finish',()=>{clearTimeout(timer);if(req.path!=='/health')logger({event:'http_request',requestId:res.locals.requestId,method:req.method,status:res.statusCode,durationMs:Date.now()-start});});
  res.once('close',()=>{clearTimeout(timer);controller.abort();});next();
 });
 app.get('/health',(req,res)=>res.json({status:'ok'}));
 const limiter=options=>rateLimit({windowMs:60000,standardHeaders:'draft-7',legacyHeaders:false,
  handler:(req,res)=>reject(res,429,'RATE_LIMITED'),...options});
 // Global bound runs first, so an attacker rotating IPs cannot grow the per-IP store unboundedly.
 app.use('/v1/admin',limiter({limit:config.globalLimit,keyGenerator:()=> 'service'}));
 app.use('/v1/admin',limiter({limit:config.ipLimit}));
 let authPending=0;
 async function bounded(operation){
  if(authPending>=config.maxAuthPending)throw new HttpError(503,'SERVICE_BUSY');
  authPending++;
  const pending=Promise.resolve().then(operation).finally(()=>{authPending--;});let timer;
  try{return await Promise.race([pending,new Promise((_,fail)=>{timer=setTimeout(()=>fail(new HttpError(504,'AUTH_TIMEOUT')),config.authTimeoutMs);timer.unref();})]);}
  finally{clearTimeout(timer);}
 }
 function forwardAuthError(error,res,next){
  if(finished(res))return;
  if(error instanceof HttpError)next(error);
  else if(error?.code==='auth/user-disabled')next(new HttpError(403,'FORBIDDEN'));
  else next(new HttpError(authenticatedFailures.has(error?.code)?401:503,authenticatedFailures.has(error?.code)?'UNAUTHENTICATED':'AUTH_SERVICE_UNAVAILABLE'));
 }
 app.use('/v1/admin',async(req,res,next)=>{
  if(config.requireHttps&&!req.secure)return reject(res,400,'HTTPS_REQUIRED');
  const encoding=req.headers['content-encoding'];if(encoding&&encoding!=='identity')return reject(res,415,'CONTENT_ENCODING_UNSUPPORTED');
  const declared=Number(req.headers['content-length']??0);
  if(!Number.isSafeInteger(declared)||declared<0||declared>config.excelBytes+config.contractBytes+65536)return reject(res,413,'UPLOAD_TOO_LARGE');
  const count=req.rawHeaders.filter((_,i)=>i%2===0&&req.rawHeaders[i].toLowerCase()==='authorization').length;
  const header=req.headers.authorization;
  if(count!==1||typeof header!=='string'||!/^Bearer [A-Za-z0-9._-]{20,8192}$/.test(header))return reject(res,401,'UNAUTHENTICATED');
  if(!firebase)return reject(res,503,'AUTH_SERVICE_UNAVAILABLE');
  try{
   const principal=await bounded(async()=>{
   const claims=await firebase.verifyIdToken(header.slice(7));
   if(typeof claims?.uid!=='string'||!claims.uid||claims.uid.includes('/'))throw new HttpError(401,'UNAUTHENTICATED');
   if(!adminClaims(claims))throw new HttpError(403,'FORBIDDEN');
   const current=await firebase.getUser(claims.uid);
   if(current.disabled||!adminClaims(current.customClaims)||current.customClaims.role!==claims.role)throw new HttpError(403,'FORBIDDEN');
   return {uid:claims.uid,role:claims.role};
   });if(!finished(res)){res.locals.principal=principal;next();}
  }catch(error){forwardAuthError(error,res,next);}
 });
 // Firebase operations that time out keep their concurrency slot until they actually settle.
 app.use('/v1/admin',limiter({limit:config.uidLimit,keyGenerator:(req,res)=>res.locals.principal.uid}));
 // Per-user throttling precedes Firestore so rejected repetitions do not consume profile reads.
 app.use('/v1/admin',async(req,res,next)=>{
  try{
   await bounded(async()=>{
    const p=res.locals.principal,profile=await firebase.readProfile(p.uid);
    if(profile?.estado!=='ACTIVO'||profile.role!==p.role)throw new HttpError(403,'FORBIDDEN');
   });if(!finished(res))next();
  }catch(error){forwardAuthError(error,res,next);}
 });
 app.get('/v1/admin/status',(req,res)=>res.json({mode:'SAFE_PREVIEW',inventoryWritesEnabled:false,capabilities:['status','prepare-proposal']}));
 let preparing=false;
 app.post('/v1/admin/imports/prepare',async(req,res,next)=>{
  if(preparing)return reject(res,503,'PREPARATION_BUSY');preparing=true;
  try{
   const files=await readProposalUpload(req,config,res.locals.signal);
   const json=await prepareInWorker({...files,uid:res.locals.principal.uid,config,signal:res.locals.signal});
   if(!finished(res))res.type('application/json').send(json);
  }catch(error){next(error);}finally{preparing=false;}
 });
 // No start/recover/execute/stock/classification-apply route exists. No engine or privileged write API is injected.
 app.use((req,res)=>reject(res,404,'NOT_FOUND'));
 app.use((error,req,res,next)=>{
  if(res.headersSent||finished(res))return;
  if(error instanceof HttpError){if(error.status===408||error.status===413)res.set('Connection','close');return reject(res,error.status,error.code);}
  reject(res,503,'SERVICE_UNAVAILABLE');
 });
 return app;
}
