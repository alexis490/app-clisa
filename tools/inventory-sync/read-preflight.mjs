import { sha,check,validCode } from './canonical.mjs';

export const TARGET='empresa-clisa';
const DB=`projects/${TARGET}/databases/(default)`;
export const READ_PERMISSIONS=['datastore.databases.getMetadata','datastore.entities.get','datastore.entities.list','datastore.indexes.list','firebaserules.releases.get','firebaserules.rulesets.get','firebaseauth.users.get'];
export const FUTURE_WRITE_PERMISSIONS=['datastore.databases.get','datastore.entities.create','datastore.entities.update'];
export const EXCESS_PERMISSIONS=['datastore.entities.delete','resourcemanager.projects.setIamPolicy','firebaserules.releases.update','firebaseauth.users.update'];
const projections={
  productos:['codigo','nombre','stock','cantidadRestante','estado','categoriaId','marcaId','descripcion','actualizadoEn'],
  existencias:['productoId','codigoProducto','codigo','cantidad','estado','actualizadoEn'],
  registros:['registroId','productoId','existenciaId','codigoProducto','codigoExistencia','cantidad','tipo','estado','fechaHora','tiendaOrigenId','tiendaDestinoId','estadoPago','estadoFormalizacion','formalizadoEn','formalizadoPor'],
  usuarios:['uid','role','estado'],tiendas:['nombre','estado'],categorias:['nombre','activo'],marcas:['nombre','activo']
};

/** Dedicated REST reader. No generic URL/method API and no import of Admin SDK/engine. */
export class ProductionReadPreflight {
  #token;#fetch;#budget;#calls=[];#reserved=0;#returned=0;
  constructor({project,token,fetchImpl=fetch,documentBudget=24}){
    check(project===TARGET,'WRONG_FIREBASE_PROJECT');
    check(typeof token==='string'&&token.length>0,'READ_IDENTITY_REQUIRED');
    check(Number.isInteger(documentBudget)&&documentBudget>=1&&documentBudget<=24,'INVALID_READ_BUDGET');
    this.#token=token;this.#fetch=fetchImpl;this.#budget=documentBudget;
  }
  stats(){return {httpCalls:this.#calls.length,requests:[...this.#calls],reservedDocumentReadUnits:this.#reserved,documentsReturned:this.#returned,documentBudget:this.#budget,documentWrites:0,fullInventoryScan:false};}
  async #request(method,url,body,{reserve=0,missing=false}={}){
    check(this.#reserved+reserve<=this.#budget,'PREFLIGHT_READ_BUDGET_EXCEEDED');this.#reserved+=reserve;
    this.#calls.push({method,url});
    const r=await this.#fetch(url,{method,redirect:'error',headers:{Authorization:`Bearer ${this.#token}`,'Content-Type':'application/json'},
      ...(body?{body:JSON.stringify(body)}:{}),signal:AbortSignal.timeout(15000)});
    if(missing&&r.status===404)return null;
    if(!r.ok){
      // Only expose validation diagnostics for public index metadata; never tokens or user lookup responses.
      let detail='';if(r.status===400&&/\/collectionGroups\/.*\/(indexes|fields)\?/.test(url)){
        const error=await r.json();detail=':'+String(error.error?.message??'INVALID_ARGUMENT').slice(0,240);
      }
      throw Error(`READ_PREFLIGHT_HTTP_${r.status}${detail}`);
    }
    const data=await r.json();return data;
  }
  async identity(){return this.#request('GET','https://www.googleapis.com/oauth2/v2/userinfo');}
  async permissions(){return this.#request('POST',`https://cloudresourcemanager.googleapis.com/v1/projects/${TARGET}:testIamPermissions`,{permissions:[...READ_PERMISSIONS,...FUTURE_WRITE_PERMISSIONS,...EXCESS_PERMISSIONS]});}
  async database(){return this.#request('GET',`https://firestore.googleapis.com/v1/${DB}`);}
  async release(){return this.#request('GET',`https://firebaserules.googleapis.com/v1/projects/${TARGET}/releases/cloud.firestore`);}
  async ruleset(name){check(new RegExp(`^projects/${TARGET}/rulesets/[a-zA-Z0-9-]+$`).test(name),'WRONG_RULESET_PROJECT');return this.#request('GET',`https://firebaserules.googleapis.com/v1/${name}`);}
  // This endpoint currently accepts only the default page size (same request as Firebase CLI).
  async indexes(){return this.#request('GET',`https://firestore.googleapis.com/v1/${DB}/collectionGroups/-/indexes`);}
  async fields(){return this.#request('GET',`https://firestore.googleapis.com/v1/${DB}/collectionGroups/-/fields?filter=${encodeURIComponent('indexConfig.usesAncestorConfig=false OR ttlConfig:*')}`);}
  async collections(){return this.#request('POST',`https://firestore.googleapis.com/v1/${DB}/documents:listCollectionIds`,{pageSize:100});}
  async control(){const d=await this.#request('GET',`https://firestore.googleapis.com/v1/${DB}/documents/importaciones/_control_clisa`,null,{reserve:1,missing:true});if(d)this.#returned++;return d;}
  async sample(collection,limit,filter=null){
    check(Object.hasOwn(projections,collection)&&Number.isInteger(limit)&&limit>=1&&limit<=3,'UNBOUNDED_OR_UNKNOWN_QUERY');
    const allowed={productos:'codigo',existencias:'codigo',usuarios:'email'};
    if(filter)check(filter.field===allowed[collection]&&validCode(filter.value),'QUERY_FILTER_NOT_ALLOWED');
    check(collection!=='usuarios'||filter!==null,'USER_COLLECTION_SCAN_FORBIDDEN');
    const structuredQuery={from:[{collectionId:collection}],select:{fields:projections[collection].map(fieldPath=>({fieldPath}))},limit};
    if(filter)structuredQuery.where={fieldFilter:{field:{fieldPath:filter.field},op:'EQUAL',value:{stringValue:filter.value}}};
    const result=await this.#request('POST',`https://firestore.googleapis.com/v1/${DB}/documents:runQuery`,{structuredQuery},{reserve:limit});
    check(Array.isArray(result),'INVALID_QUERY_RESPONSE');const docs=result.filter(r=>r.document).map(r=>r.document);
    check(docs.length<=limit,'UNEXPECTED_QUERY_SIZE');this.#returned+=docs.length;return docs;
  }
  async authUser(uid){check(typeof uid==='string'&&uid.length>0&&!uid.includes('/'),'INVALID_AUTH_UID');
    const response=await this.#request('POST',`https://identitytoolkit.googleapis.com/v1/projects/${TARGET}/accounts:lookup`,{localId:[uid]});
    check(response.users?.length===1&&response.users[0].localId===uid,'AUTH_PROFILE_NOT_FOUND');
    const u=response.users[0];let claims;try{claims=JSON.parse(u.customAttributes||'{}');}catch{throw Error('INVALID_AUTH_CLAIMS');}
    return {uid:u.localId,disabled:u.disabled===true,claims:{role:claims.role??null,admin:claims.admin===true,superAdmin:claims.superAdmin===true}};
  }
}

export function decodeFields(document){
  function value(v){if('stringValue'in v)return v.stringValue;if('integerValue'in v){const n=Number(v.integerValue);return Number.isSafeInteger(n)?n:null;}if('booleanValue'in v)return v.booleanValue;if('timestampValue'in v)return {timestamp:v.timestampValue};if('nullValue'in v)return null;return {unsupportedType:true};}
  return Object.fromEntries(Object.entries(document?.fields??{}).map(([k,v])=>[k,value(v)]));
}
export function checkSample(collection,document){
  const f=decodeFields(document),errors=[];const nonempty=k=>typeof f[k]==='string'&&f[k].length>0;
  const integer=k=>Number.isSafeInteger(f[k])&&f[k]>=0;
  if(collection==='productos'){
    if(!nonempty('codigo')||!nonempty('nombre')||!integer('stock')||!['ACTIVO','INACTIVO'].includes(f.estado))errors.push('PRODUCT_SCHEMA_INCOMPATIBLE');
    if(f.cantidadRestante!==undefined&&f.cantidadRestante!==f.stock)errors.push('LEGACY_COUNTER_DIFFERS_PRESERVE');
  }
  if(collection==='existencias'){
    if(!nonempty('productoId')||!nonempty('codigoProducto')||!nonempty('codigo')||!integer('cantidad')||f.estado!==(f.cantidad===0?'SIN_STOCK':'DISPONIBLE'))errors.push('EXISTENCE_SCHEMA_INCOMPATIBLE');
  }
  if(collection==='registros'){
    if(!nonempty('productoId')||!nonempty('existenciaId')||!Number.isSafeInteger(f.cantidad)||f.cantidad<=0||!f.fechaHora?.timestamp||f.estado!=='COMPLETADO')errors.push('HISTORICAL_SCHEMA_REQUIRES_REVIEW');
    if(f.tipo==='SALIDA'&&(f.tiendaOrigenId!=='clisa'||f.tiendaDestinoId!=='toqen'||!['Pagado','Pendiente'].includes(f.estadoPago)))errors.push('SALIDA_SCHEMA_REQUIRES_REVIEW');
  }
  return {path:document.name?.split('/documents/')[1]??null,fields:f,findings:errors,
    ...(collection==='registros'?{formalization:Object.hasOwn(f,'estadoFormalizacion')?f.estadoFormalizacion:'SIN_VERIFICAR',inclusionInExcel:'UNPROVEN'}:{})};
}

/** Connectivity/permission failures halt further remote reads. Sample coverage never authorizes writes. */
export async function runReadPreflight(reader,{expectedAccountEmail,now=Date.now()}={}){
  const report={schemaVersion:1,project:TARGET,checkedAt:new Date(now).toISOString(),mode:'READ_ONLY',status:'BLOQUEADO',productionExecutable:false,checks:[],blockers:[],evidence:{}};
  const add=(code,status,detail)=>report.checks.push({code,status,detail});
  try{
    const identity=await reader.identity();
    check(identity.verified_email===true&&identity.email&&identity.email===expectedAccountEmail,'OPERATOR_IDENTITY_MISMATCH');
    report.evidence.operator={email:identity.email,identitySource:'OAuth userinfo; no proof of CLISA requester consent'};add('OPERATOR_IDENTITY','PROBADO','Identidad OAuth verificada; separada de permisos IAM y rol CLISA.');
    const iam=await reader.permissions();report.evidence.permissions=iam.permissions??[];
    const missing=READ_PERMISSIONS.filter(p=>!report.evidence.permissions.includes(p));check(missing.length===0,'INSUFFICIENT_READ_PERMISSIONS:'+missing.join(','));
    add('READ_IAM','PROBADO','Permisos de inspección presentes. No se probaron escrituras.');
    const futureMissing=FUTURE_WRITE_PERMISSIONS.filter(p=>!report.evidence.permissions.includes(p));
    add('FUTURE_WRITE_IAM',futureMissing.length?'BLOQUEADO':'PENDIENTE',futureMissing.length?futureMissing.join(','):'testIamPermissions positivo no acredita ausencia de condiciones, denegaciones o restricciones sobre cada recurso.');
    const db=await reader.database();check(db.name===`projects/${TARGET}/databases/(default)`,'WRONG_DATABASE');report.evidence.database={name:db.name,locationId:db.locationId,type:db.type,concurrencyMode:db.concurrencyMode};
    check(db.type==='FIRESTORE_NATIVE','UNSUPPORTED_DATABASE_TYPE');
    const release=await reader.release(),rules=await reader.ruleset(release.rulesetName);
    check(rules.source?.files?.length>0,'RULES_SOURCE_UNAVAILABLE');
    report.evidence.rules={ruleset:release.rulesetName,files:rules.source.files.map(f=>({name:f.name,sha256:sha(f.content),content:f.content}))};
    const exactProposal=(await import('node:fs/promises')).readFile;
    // Detection is conservative: equality to the tested candidate is required for its known lock protocol.
    const candidate=await exactProposal(new URL('../../security/paso4b1/firestore.emulator.rules',import.meta.url),'utf8');
    report.evidence.rules.matchesTestedLockProposal=rules.source.files.length===1&&sha(rules.source.files[0].content)===sha(candidate);
    if(!report.evidence.rules.matchesTestedLockProposal)report.blockers.push('DEPLOYED_LOCK_PROTOCOL_NOT_VERIFIED');
    report.evidence.indexes=await reader.indexes();report.evidence.fieldConfigurations=await reader.fields();report.evidence.collections=await reader.collections();
    if(report.evidence.indexes.nextPageToken)report.blockers.push('INDEX_METADATA_PARTIAL');
    if(report.evidence.collections.nextPageToken)report.blockers.push('COLLECTION_METADATA_PARTIAL');
    if(report.evidence.fieldConfigurations.nextPageToken)report.blockers.push('FIELD_INDEX_METADATA_PARTIAL');
    if((report.evidence.indexes.indexes?.length??0)>200||(report.evidence.fieldConfigurations.fields?.length??0)>200)report.blockers.push('INDEX_METADATA_REVIEW_TOO_LARGE');
    const collectionIds=report.evidence.collections.collectionIds??[];
    report.evidence.requiredCollections=['productos','existencias','registros','usuarios','tiendas','categorias','marcas'].map(name=>({name,listed:collectionIds.includes(name)}));
    if(report.evidence.requiredCollections.some(c=>!c.listed))report.blockers.push('REQUIRED_COLLECTION_NOT_OBSERVED');
    const control=await reader.control();report.evidence.control=control?decodeFields(control):{exists:false};
    check(!control||(report.evidence.control.locked===false&&Number.isSafeInteger(report.evidence.control.fence)&&report.evidence.control.fence>=0),'IMPORT_CONTROL_REQUIRES_REVIEW');
    const profiles=await reader.sample('usuarios',2,{field:'email',value:identity.email});
    check(profiles.length===1,'APPLICATION_OPERATOR_PROFILE_UNVERIFIED');
    const profile=decodeFields(profiles[0]),uid=profiles[0].name.split('/').at(-1);report.evidence.applicationProfile={uid,role:profile.role,estado:profile.estado};
    check(profile.estado==='ACTIVO'&&['ADMIN','SUPER_ADMIN'].includes(profile.role),'APPLICATION_OPERATOR_NOT_ADMIN');
    const auth=await reader.authUser(uid);report.evidence.auth=auth;
    check(!auth.disabled&&auth.claims.role===profile.role&&auth.claims.admin&&(profile.role!=='SUPER_ADMIN'||auth.claims.superAdmin),'AUTH_PROFILE_ROLE_MISMATCH');
    add('APPLICATION_OPERATOR','PROBADO','Perfil propio y Auth coherentes. No autentica un solicitante móvil ni autoriza un plan concreto.');
    report.evidence.samples={};
    for(const [name,limit] of [['productos',2],['existencias',2],['registros',3],['tiendas',2],['categorias',1],['marcas',1]]){
      const docs=await reader.sample(name,limit);report.evidence.samples[name]=docs.map(d=>checkSample(name,d));
    }
    report.evidence.sampleIdentityChecks=[];
    for(const collection of ['productos','existencias'])for(const sample of report.evidence.samples[collection]){
      if(!validCode(sample.fields.codigo)){report.blockers.push('SAMPLED_IDENTITY_INVALID');continue;}
      const matches=await reader.sample(collection,2,{field:'codigo',value:sample.fields.codigo});
      report.evidence.sampleIdentityChecks.push({collection,code:sample.fields.codigo,returned:matches.length,ambiguous:matches.length!==1});
      if(matches.length!==1)report.blockers.push('SAMPLED_IDENTITY_AMBIGUOUS');
    }
    add('BOUNDED_INSPECTION','PROBADO','Muestras limitadas; no certifican todo el inventario, historial, referencias, IDs nuevos ni cuota.');
    report.blockers.push('FULL_IDENTITIES_AND_NATIVE_VERSIONS_UNVERIFIED','REMAINING_QUOTA_UNVERIFIED','REAL_EXPORT_AUTHORITY_MISSING','REAL_RECONCILIATION_MISSING','PRODUCTION_EXECUTOR_DISABLED');
  }catch(error){report.blockers.push(error.message);report.halted=true;}
  report.blockers=[...new Set(report.blockers)];report.cost=reader.stats();return report;
}
