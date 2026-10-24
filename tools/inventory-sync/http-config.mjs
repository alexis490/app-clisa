export const EXCEL_LIMIT=25*1024*1024,CONTRACT_LIMIT=8*1024*1024;
export class ConfigError extends Error { constructor(){super('INVALID_CONFIGURATION');} }
export function readHttpConfig(env=process.env){
 const fail=()=>{throw new ConfigError();};
 // A public server must NEVER accept unsigned emulator tokens or enable inventory via an env toggle.
 if(['FIRESTORE_EMULATOR_HOST','FIREBASE_AUTH_EMULATOR_HOST'].some(k=>Object.hasOwn(env,k)))fail();
 if(env.INVENTORY_WRITES_ENABLED&&env.INVENTORY_WRITES_ENABLED!=='false')fail();
 const integer=(key,fallback,min,max)=>{if(!env[key])return fallback;const v=Number(env[key]);if(!/^\d+$/.test(env[key])||!Number.isSafeInteger(v)||v<min||v>max)fail();return v;};
 const render=env.RENDER==='true';
 if(env.REQUIRE_HTTPS&&!['true','false'].includes(env.REQUIRE_HTTPS)||render&&env.REQUIRE_HTTPS==='false')fail();
 const projectId=env.FIREBASE_PROJECT_ID||null,json=env.FIREBASE_SERVICE_ACCOUNT_JSON||null;
 if(Boolean(projectId)!==Boolean(json))fail();
 let account=null;
 if(json){
  if(Buffer.byteLength(json)>65536||!/^[a-z][a-z0-9-]{4,28}[a-z0-9]$/.test(projectId)||projectId.startsWith('demo-'))fail();
  try{account=JSON.parse(json);}catch{fail();}
  if(account?.type!=='service_account'||account.project_id!==projectId||typeof account.client_email!=='string'||!account.client_email.endsWith('.iam.gserviceaccount.com')||typeof account.private_key!=='string'||!account.private_key.includes('-----BEGIN PRIVATE KEY-----'))fail();
 }
 const config={port:integer('PORT',10000,1,65535),trustProxyHops:integer('TRUST_PROXY_HOPS',render?1:0,0,1),
  requireHttps:render||env.REQUIRE_HTTPS==='true',requestTimeoutMs:integer('REQUEST_TIMEOUT_MS',30000,1000,60000),
  authTimeoutMs:integer('AUTH_TIMEOUT_MS',5000,500,10000),prepareTimeoutMs:integer('PREPARATION_TIMEOUT_MS',15000,100,20000),
  excelBytes:integer('MAX_EXCEL_BYTES',EXCEL_LIMIT,1024,EXCEL_LIMIT),contractBytes:integer('MAX_CONTRACT_BYTES',CONTRACT_LIMIT,1024,CONTRACT_LIMIT),
  globalLimit:integer('GLOBAL_REQUESTS_PER_MINUTE',120,1,600),ipLimit:integer('IP_REQUESTS_PER_MINUTE',20,1,60),
  uidLimit:integer('USER_REQUESTS_PER_MINUTE',20,1,60),maxAuthPending:4,projectId};
 if(2*config.authTimeoutMs+config.prepareTimeoutMs>=config.requestTimeoutMs)fail();
 return {config:Object.freeze(config),account};
}
