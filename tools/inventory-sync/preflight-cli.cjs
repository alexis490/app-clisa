// Management OAuth is used only by the dedicated read adapter. No execution, deployment or key creation.
const path=require('node:path'),fs=require('node:fs/promises');
(async()=>{
 const args=process.argv.slice(2),options={};
 for(let i=0;i<args.length;i+=2){if(!['--project','--output','--firebase-tools-lib'].includes(args[i])||!args[i+1]||options[args[i]])throw Error('INVALID_ARGUMENTS');options[args[i]]=args[i+1];}
 if(options['--project']!=='empresa-clisa')throw Error('WRONG_FIREBASE_PROJECT');
 if(process.env.FIRESTORE_EMULATOR_HOST||process.env.FIREBASE_AUTH_EMULATOR_HOST)throw Error('PREFLIGHT_EMULATOR_ENV_UNEXPECTED');
 if(!options['--output']||!options['--firebase-tools-lib'])throw Error('MISSING_ARGUMENT');
 const {ProductionReadPreflight,runReadPreflight}=await import('./read-preflight.mjs');
 const lib=options['--firebase-tools-lib'],auth=require(path.join(lib,'auth.js')),api=require(path.join(lib,'apiv2.js'));
 const account=auth.getProjectDefaultAccount(process.cwd());if(!account?.user?.email)throw Error('CLI_ACCOUNT_UNAVAILABLE');auth.setActiveAccount({},account);
 const token=await api.getAccessToken();
 const reader=new ProductionReadPreflight({project:options['--project'],token});
 const report=await runReadPreflight(reader,{expectedAccountEmail:account.user.email});
 await fs.mkdir(options['--output'],{recursive:true});
 // The report excludes OAuth tokens and Auth refresh tokens/password data.
 await fs.writeFile(path.join(options['--output'],'preflight.json'),JSON.stringify(report,null,2));
 for(const [index,file]of (report.evidence.rules?.files??[]).entries())await fs.writeFile(path.join(options['--output'],`deployed-${index}.rules`),file.content);
 console.log(JSON.stringify({status:report.status,halted:report.halted??false,blockers:report.blockers,cost:report.cost,productionExecutable:false}));
})().catch(e=>{console.error(e.message);process.exitCode=1;});
