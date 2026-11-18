import fs from 'node:fs/promises';
import XLSX from 'xlsx';
import {readInventory} from './source-reader.mjs';
const sourcePath='C:/Users/TUF/Documents/stock.production.lot123.xlsx';
const bytes=await fs.readFile(sourcePath),src=readInventory(bytes,sourcePath),wb=XLSX.read(bytes,{type:'buffer'}),raw=XLSX.utils.sheet_to_json(wb.Sheets[wb.SheetNames[0]],{header:1,defval:null});
const valid=src.products.filter(p=>!p.critical&&p.serials.length>=1&&p.serials.length<=8),used=new Set(),groups=[];
function select(predicate=()=>true){const p=valid.find(p=>!used.has(p.code)&&predicate(p));if(!p)throw Error('No suitable product');used.add(p.code);return structuredClone(p);}
function add(kind,p,edit=()=>{}){const g={kind,p,original:structuredClone(p),details:p.serials.map(s=>{const row=raw[s.row-1]??[];return [s.code,row[1]??null,`[${p.code}] ${p.name}`,s.quantity,row[4]??null,s.quantity];})};edit(g);groups.push(g);return g;}
add('Sin cambios',select(p=>p.serials.length>=2));
add('Stock aumentado',select(),g=>{g.details[0][3]+=2;g.details[0][5]=g.details[0][3];});
add('Stock disminuido',select(p=>p.stock>0),g=>{const r=g.details.find(r=>r[3]>0);r[3]--;r[5]=r[3];});
add('Cantidad cero válida',select(p=>p.stock>0),g=>g.details.forEach(r=>{r[3]=0;r[5]=0;}));
add('Serie ausente: pasar a cero',select(p=>p.serials.length>=3&&p.serials.some(s=>s.quantity>0)),g=>{const i=g.details.findIndex(r=>r[3]>0);g.omittedSerial=g.details[i][0];g.details.splice(i,1);});
add('Serie duplicada',select(p=>p.serials.length>=2),g=>g.details[1][0]=g.details[0][0]);
add('Serie vacía',select(),g=>{g.details[0][0]='';g.details[0][1]=g.p.code;g.details[0][2]=g.p.name;});
add('Cantidad negativa',select(),g=>{g.details[0][3]=-1;g.details[0][5]=-1;});
add('Cantidad fraccionaria',select(),g=>{g.details[0][3]=1.5;g.details[0][5]=1.5;});
add('Total de grupo incorrecto',select(),g=>g.summaryDelta=7);
add('Recuento de series incorrecto',select(),g=>g.countDelta=1);
add('Nombre distinto en detalle',select(),g=>g.details[0][2]=`[${g.p.code}] NOMBRE DIFERENTE DE PRUEBA`);
add('Referencia del encabezado incorrecta',select(),g=>g.headerReference='REF-INCORRECTA-PRUEBA');
const duplicate=select(p=>p.serials.length>=2),left=structuredClone(duplicate),right=structuredClone(duplicate);left.serials=duplicate.serials.slice(0,1);right.serials=duplicate.serials.slice(1);add('Código duplicado, grupo A',left);add('Código duplicado, grupo B',right);
let count=groups.reduce((n,g)=>n+g.details.length,0);
while(count<97){const p=select(p=>p.serials.length<=97-count),g=add('Control sin cambios',p);count+=g.details.length;}
const newCode='PRUEBA-CLISA-20261031-NUEVO-100';if(src.products.some(p=>p.code===newCode))throw Error('Synthetic code collides');
add('Producto nuevo con tres existencias',{code:newCode,name:'Mouse inalámbrico Logitech M220 PRUEBA CLISA',serials:[1,2,3].map(i=>({code:`CLISA-TEST-20261031-N100-00${i}`,quantity:1})),stock:3},g=>g.details.forEach(r=>r[4]='2026-10-31T12:00:00.000Z'));
const rows=[raw[0]],cases=[];
for(const g of groups){const start=rows.length+1,qty=g.details.reduce((n,r)=>n+r[3],0);rows.push([`[${g.p.code}] ${g.p.name} (${g.details.length+(g.countDelta??0)})`,g.headerReference??null,null,qty+(g.summaryDelta??0),null,qty+(g.summaryDelta??0)],...g.details);cases.push({case:g.kind,code:g.p.code,headingRow:start,firstDetailRow:start+1,lastDetailRow:rows.length,details:g.details.length,originalStock:g.original.stock??null,fileQuantity:qty,omittedSerial:g.omittedSerial??null});}
if(groups.reduce((n,g)=>n+g.details.length,0)!==100)throw Error('Wrong detail count');
await fs.writeFile(new URL('../../docs/importacion-maestro/prueba-100-build/data.json',import.meta.url),JSON.stringify({sourcePath,sourceHash:src.fileSha256,sheetName:wb.SheetNames[0],rows,cases,newCode,baseline:src.products.filter(p=>!p.critical&&used.has(p.code)),outside:src.products.find(p=>!p.critical&&!used.has(p.code))},null,2));
console.log(JSON.stringify({groups:groups.length,detailRows:100,totalRows:rows.length,cases:cases.map(c=>({case:c.case,rows:`${c.firstDetailRow}-${c.lastDetailRow}`,details:c.details}))}));
