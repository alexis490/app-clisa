import { readFile,writeFile } from 'node:fs/promises';
import { performance } from 'node:perf_hooks';
import { readInventory } from './source-reader.mjs';
import { planProduct,countersFor,emptyCounters } from './automatic-plan.mjs';
const filename=process.env.CLISA_EXCEL_SAMPLE??'C:/Users/TUF/Documents/stock.production.lot123 - 2000.xlsx';
const start=performance.now(),bytes=await readFile(filename),source=readInventory(bytes,filename),readMs=performance.now()-start;
const summary=emptyCounters();let queries=0;
for(const p of source.products){const plan=planProduct(p,[],[],[],null);const count=countersFor(plan);for(const key of Object.keys(summary))summary[key]+=count[key];if(!p.critical)queries+=1+Math.ceil(p.serials.length/30);}
const report={environment:'LOCAL_EMPTY_DATABASE_SIMULATION',productionAccess:false,sourceModified:false,fileSha256:source.fileSha256,bytes:source.bytes,groups:source.products.length,serialRows:source.products.reduce((n,p)=>n+p.serials.length,0),issues:source.issues.length,criticalProducts:source.products.filter(p=>p.critical).length,validSourceProducts:source.products.filter(p=>!p.critical).length,mappingRequired:source.mappingRequired.length,readMilliseconds:Math.round(readMs),maxHeapMiB:Math.round(process.memoryUsage().heapUsed/1024/1024),hypotheticalEmptyDatabase:summary,estimatedQueriesForEmptyDatabase:queries,notes:['No se consultó Firestore. Las propuestas simuladas no son resultados de escritura.','Comparación: consulta de SKU y búsquedas de series en grupos de hasta 30; para productos existentes se añade la consulta acotada de sus existencias.','Por avance: máximo 20 productos de análisis o 5 de sincronización. Por producto: máximo 1000 series y 2001 mutaciones, con límites adicionales de tamaño de documento. Por catálogo: máximo 1000 documentos.']};
await writeFile(new URL('../../docs/importacion-maestro/source-analysis.json',import.meta.url),JSON.stringify(report,null,2)+'\n');
console.log(JSON.stringify(report,null,2));

