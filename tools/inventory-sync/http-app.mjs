import express from 'express';
import helmet from 'helmet';
import { rateLimit } from 'express-rate-limit';
import { randomUUID } from 'node:crypto';
import { HttpError,prepareInWorker } from './http-proposal.mjs';
import { readProposalUpload } from './http-upload.mjs';
import { createAutomaticImport,readSourceWorker } from './automatic-import.mjs';

const authenticatedFailures=new Set(['auth/argument-error','auth/invalid-id-token','auth/id-token-expired','auth/id-token-revoked','auth/user-not-found']);
const adminClaims=c=>['ADMIN','SUPER_ADMIN'].includes(c?.role)&&c.admin===true&&(c.role!=='SUPER_ADMIN'||c.superAdmin===true);
const reject=(res,status,code)=>res.status(status).json({error:{code},requestId:res.locals.requestId});
const finished=res=>res.writableEnded||res.destroyed;

export function createHttpApp({config,firebase=null,logger=()=>{},automaticImport=null}){
 const imports=automaticImport??(config.automaticImportEnabled&&firebase?.inventoryDb?createAutomaticImport({db:firebase.inventoryDb,apiKey:process.env.GEMINI_API_KEY,model:process.env.GEMINI_MODEL}):null);
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
 const automatic=req=>Boolean(imports)&&req.path.startsWith('/excel-imports');
 // Global bound runs first, so an attacker rotating IPs cannot grow the per-IP store unboundedly.
 app.use('/v1/admin',limiter({limit:config.globalLimit,keyGenerator:()=> 'service',skip:automatic}));
 app.use('/v1/admin',limiter({limit:config.ipLimit,skip:automatic}));
 app.use('/v1/admin',limiter({limit:180,keyGenerator:()=> 'automatic-service',skip:req=>!automatic(req)}));
 app.use('/v1/admin',limiter({limit:100,skip:req=>!automatic(req)}));
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
 app.use('/v1/admin',limiter({limit:config.uidLimit,keyGenerator:(req,res)=>res.locals.principal.uid,skip:automatic}));
 app.use('/v1/admin',limiter({limit:90,keyGenerator:(req,res)=>res.locals.principal.uid,skip:req=>!automatic(req)}));
 // Per-user throttling precedes Firestore so rejected repetitions do not consume profile reads.
 app.use('/v1/admin',async(req,res,next)=>{
  try{
   await bounded(async()=>{
    const p=res.locals.principal,profile=await firebase.readProfile(p.uid);
    if(profile?.estado!=='ACTIVO'||profile.role!==p.role)throw new HttpError(403,'FORBIDDEN');
   });if(!finished(res))next();
  }catch(error){forwardAuthError(error,res,next);}
 });
 app.get('/v1/admin/status',(req,res)=>res.json({mode:imports?'AUTOMATIC_IMPORT':'SAFE_PREVIEW',inventoryWritesEnabled:Boolean(imports),capabilities:['status','prepare-proposal',...(imports?['automatic-import']:[])]}));
 let preparing=false;
 app.post('/v1/admin/imports/prepare',async(req,res,next)=>{
  if(preparing)return reject(res,503,'PREPARATION_BUSY');preparing=true;
  try{
   const files=await readProposalUpload(req,config,res.locals.signal);
   const json=await prepareInWorker({...files,uid:res.locals.principal.uid,config,signal:res.locals.signal});
   if(!finished(res))res.type('application/json').send(json);
  }catch(error){next(error);}finally{preparing=false;}
 });
 if(imports){
  let uploading=false;
  app.post('/v1/admin/excel-imports',async(req,res,next)=>{
   if(uploading)return reject(res,503,'PREPARATION_BUSY');uploading=true;
   try{
    const files=await readProposalUpload(req,config,res.locals.signal,{automatic:true});let options;
    try{options=JSON.parse(files.contract.toString('utf8'));}catch{throw new HttpError(422,'INVALID_MAPPING');}
    if(!options||typeof options!=='object'||Array.isArray(options)||options.mappings&&typeof options.mappings!=='object')throw new HttpError(422,'INVALID_MAPPING');
    const source=await readSourceWorker(files.excel,files.filename,options.mappings??{},config.prepareTimeoutMs);
    if(source.mappingRequired.length){if(!finished(res))res.json({status:'MAPPING_REQUIRED',mappingRequired:source.mappingRequired});return;}
    if(!source.products.length)throw new HttpError(422,'NO_PRODUCTS');
    const result=await imports.create(source,res.locals.principal);if(!finished(res))res.json(result);
   }catch(error){next(error);}finally{uploading=false;}
  });
  app.get('/v1/admin/excel-imports/:id',async(req,res,next)=>{try{const result=await imports.view(req.params.id,res.locals.principal,Number(req.query.offset??0));if(!finished(res))res.json(result);}catch(error){next(error);}});
  for(const action of ['advance','confirm'])app.post(`/v1/admin/excel-imports/:id/${action}`,async(req,res,next)=>{
   try{const result=await imports[action](req.params.id,res.locals.principal);if(!finished(res))res.json(result);}catch(error){next(error);}
  });
 }
 app.use((req,res)=>reject(res,404,'NOT_FOUND'));
 app.use((error,req,res,next)=>{
  if(res.headersSent||finished(res))return;
  if(error instanceof HttpError){if(error.status===408||error.status===413)res.set('Connection','close');return reject(res,error.status,error.code);}
  reject(res,503,'SERVICE_UNAVAILABLE');
 });
 return app;
}
