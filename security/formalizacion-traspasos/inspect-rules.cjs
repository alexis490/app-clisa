const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto');
const cli='C:/Users/TUF/AppData/Local/npm-cache/_npx/7750544ccf494d8b/node_modules/firebase-tools/lib';
const auth=require(path.join(cli,'auth.js')),api=require(path.join(cli,'apiv2.js'));
(async()=>{
 const account=auth.getProjectDefaultAccount(process.cwd());if(!account)throw Error('CLI session unavailable');auth.setActiveAccount({},account);
 const token=await api.getAccessToken();const base='https://firebaserules.googleapis.com/v1/projects/empresa-clisa';
 async function get(url){if(!url.startsWith(base+'/'))throw Error('Unauthorized endpoint');const r=await fetch(url,{method:'GET',headers:{Authorization:`Bearer ${token}`},signal:AbortSignal.timeout(30000)});if(!r.ok)throw Error(`Rules metadata HTTP ${r.status}`);return r.json();}
 const release=await get(base+'/releases/cloud.firestore');const rules=await get('https://firebaserules.googleapis.com/v1/'+release.rulesetName);
 const out=process.argv[2]||'security/formalizacion-traspasos';fs.mkdirSync(out,{recursive:true});const files=[];
 for(const [i,f] of (rules.source?.files||[]).entries()){const name=`deployed-${i}-${path.basename(f.name)}`;fs.writeFileSync(path.join(out,name),f.content);files.push({file:name,sha256:crypto.createHash('sha256').update(f.content).digest('hex')});}
 const metadata={checkedAt:new Date().toISOString(),project:'empresa-clisa',ruleset:release.rulesetName,files,productionDocumentReads:0,productionWrites:0};fs.writeFileSync(path.join(out,'inspection.json'),JSON.stringify(metadata,null,2));console.log(JSON.stringify(metadata));
})().catch(e=>{console.error(e.message);process.exit(1)});
