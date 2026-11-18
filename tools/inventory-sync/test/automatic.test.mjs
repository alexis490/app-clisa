import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import XLSX from 'xlsx';
import { readInventory } from '../source-reader.mjs';
import { planProduct,countersFor } from '../automatic-plan.mjs';
import { classifyBatch } from '../automatic-classification.mjs';
import { productId } from '../canonical.mjs';

const sample=process.env.CLISA_EXCEL_SAMPLE??'C:/Users/TUF/Documents/stock.production.lot123 - 2000.xlsx';
const original=readFileSync(sample),real=readInventory(original,sample);
const valid=real.products.filter(p=>!p.critical&&p.serials.length>0);
assert.ok(valid.length>1,'La fuente autorizada debe tener productos válidos para derivar casos.');
const base=()=>structuredClone(valid[0]);
const workbook=(rows,type='xlsx')=>{const wb=XLSX.utils.book_new();XLSX.utils.book_append_sheet(wb,XLSX.utils.aoa_to_sheet(rows),'Prueba');return XLSX.write(wb,{type:'buffer',bookType:type});};
const rows=(p=base())=>[['Lote/Nº de serie','Referencia Interna','Producto','Cantidad'],[`[${p.code}] ${p.name} (${p.serials.length})`,'','',p.stock],...p.serials.map(s=>[s.code,p.code,p.name,s.quantity])];
const catalog={categories:[{id:'cat-real',name:'Periféricos',active:true}],brands:[{id:'brand-real',name:'Logitech',active:true}]},rules={aliases:[]};
const doc=(id,data,collection='existencias')=>({id,path:`${collection}/${id}`,data,version:'1:0'});

test('Excel autorizado: lectura completa, ceros iniciales y exclusión individual',()=>{
 assert.equal(real.products.length,1814);assert.equal(real.products.reduce((n,p)=>n+p.serials.length,0),22762);
 assert.ok(real.products.some(p=>p.code==='00780'));assert.ok(real.issues.length>0);assert.ok(valid.length>0);
 assert.ok(real.products.some(p=>p.critical));assert.ok(real.products.filter(p=>p.critical).every(p=>countersFor(planProduct(p,[],[],[],null)).productsOmitted===1));
});
for(const type of ['xlsx','xls','csv'])test(`Fuente real derivada: ${type} conserva códigos y no suma el resumen`,()=>{
 const p=base(),source=readInventory(workbook(rows(p),type),`prueba.${type}`);
 assert.equal(source.products.length,1);assert.equal(source.products[0].critical,false,JSON.stringify(source.issues));assert.equal(source.products[0].stock,p.stock);assert.equal(source.products[0].code,p.code);
});
test('Tabla plana, columnas alternativas y mapeo manual conservan códigos de texto',()=>{
 const p=base(),data=[['x','y','z','w'],...p.serials.map(s=>[p.code,p.name,s.code,s.quantity])];
 assert.equal(readInventory(workbook(data),'test.xlsx').mappingRequired.length,1);
 const mapped=readInventory(workbook(data),'test.xlsx',{Prueba:{headerRow:1,code:0,name:1,serial:2,quantity:3}});
 assert.equal(mapped.products[0].stock,p.stock);assert.equal(mapped.products[0].critical,false);
 const alias=[['SKU','Nombre','Serie','Cantidad'],...p.serials.map(s=>['00078',p.name,s.code,s.quantity])];
 assert.equal(readInventory(workbook(alias),'test.xlsx').products[0].code,'00078');
});
for(const mutation of ['negative','duplicate','summary','name','code','formula'])test(`Producto inválido ${mutation} se aparta sin bloquear el siguiente`,()=>{
 const p=base(),other=structuredClone(valid[1]);other.serials.forEach((s,i)=>s.code=`otra-${i}`);
 const data=rows(p);
 if(mutation==='negative')data[2][3]=-1;
 if(mutation==='duplicate')data.push([...data[2]]);
 if(mutation==='summary')data[1][3]=p.stock+1;
 if(mutation==='name')data[2][2]='Nombre diferente';
 if(mutation==='code')data[1][0]=`[ ] ${p.name} (${p.serials.length})`;
 const wb=XLSX.utils.book_new(),ws=XLSX.utils.aoa_to_sheet([...data,...rows(other).slice(1)]);if(mutation==='formula')ws.D3={t:'n',v:1,f:'1+0'};XLSX.utils.book_append_sheet(wb,ws,'Prueba');
 const result=readInventory(XLSX.write(wb,{type:'buffer',bookType:'xlsx'}),'test.xlsx');
 assert.equal(result.products[0].critical,true);assert.equal(result.products[1].critical,false,JSON.stringify(result.issues));
});
test('Comparación: mantiene IDs, actualiza cantidades, crea series y pone ausencias a cero',()=>{
 const p=base();p.serials=p.serials.slice(0,1);p.stock=p.serials[0].quantity;p.reportedQuantity=p.stock;
 const parent=doc('legacy-id',{codigo:p.code,nombre:p.name,stock:900,estado:'ACTIVO',categoriaId:'existing'},'productos');
 const serial=doc('legacy-series',{productoId:'legacy-id',codigoProducto:p.code,codigo:p.serials[0].code,cantidad:p.stock+1,estado:'DISPONIBLE'});
 const absent=doc('absent',{productoId:'legacy-id',codigoProducto:p.code,codigo:'ausente',cantidad:3,estado:'DISPONIBLE'});
 const plan=planProduct(p,[parent],[serial,absent],[serial],{fields:{},pending:['categoriaId']});
 assert.equal(plan.excluded,false);assert.equal(plan.productId,'legacy-id');assert.equal(plan.operations.find(o=>o.type==='ZERO_EXISTENCE').path,'existencias/absent');
 assert.equal(plan.operations.find(o=>o.type==='UPDATE_EXISTENCE').path,'existencias/legacy-series');assert.deepEqual(plan.operations.find(o=>o.type==='UPDATE_PRODUCT').fields,{stock:p.stock});
 assert.equal(planProduct({...p,critical:true},[parent],[serial,absent],[serial],null).operations.length,0);
 const fresh=planProduct(p,[],[],[],null);assert.equal(fresh.productId,productId(p.code));assert.ok(fresh.operations.some(o=>o.type==='CREATE_EXISTENCE'));
 assert.deepEqual(fresh,planProduct(p,[],[],[],null));
});
test('Serie asociada a otro producto o código duplicado en base excluye producto',()=>{
 const p=base(),cross=doc('other',{codigo:p.serials[0].code,productoId:'otro',codigoProducto:'otro'});
 assert.equal(planProduct(p,[],[],[cross],null).excluded,true);
 assert.equal(planProduct(p,[doc('a',{},'productos'),doc('b',{},'productos')],[],[],null).excluded,true);
});
test('Clasificación conserva catálogo válido y descripción; no consulta IA si completo',async()=>{
 let calls=0;
 const result=await classifyBatch([{code:'a',name:'Mouse Logitech',categoriaId:'cat-real',marcaId:'brand-real',descripcion:'Texto válido'}],catalog,rules,{apiKey:'secret',model:'test',fetcher:async()=>{calls++;throw Error();}});
 assert.equal(calls,0);assert.deepEqual(result[0].fields,{categoriaId:'cat-real',marcaId:'brand-real',descripcion:'Texto válido'});
});
test('Gemini solo acepta IDs activos, ignora descripción inventada y tolera fallo',async()=>{
 const products=[{code:'a',name:'Producto desconocido'}];
 const fetcher=async()=>new Response(JSON.stringify({candidates:[{content:{parts:[{text:JSON.stringify([{code:'a',categoriaId:'inventada',marcaId:'brand-real',descripcion:'Fantasía'}])}]}}]}));
 const result=await classifyBatch(products,catalog,rules,{apiKey:'fake',model:'test',fetcher});
 assert.equal(result[0].fields.categoriaId,undefined);assert.equal(result[0].fields.marcaId,'brand-real');assert.equal(result[0].fields.descripcion,products[0].name);
 const failed=await classifyBatch(products,catalog,rules,{apiKey:'fake',model:'test',fetcher:async()=>{throw Error('fail');}});assert.ok(failed[0].pending.length);
 assert.equal(planProduct(base(),[],[],[],failed[0]).excluded,false);
});
test('Contadores no declaran escrituras para productos sin cambios u omitidos',()=>{
 const p=base(),pid=productId(p.code),parent=doc(pid,{codigo:p.code,nombre:p.name,stock:p.stock,estado:'ACTIVO'},'productos');
 const entries=p.serials.map((s,i)=>doc(`s${i}`,{productoId:pid,codigoProducto:p.code,codigo:s.code,cantidad:s.quantity,estado:s.quantity?'DISPONIBLE':'SIN_STOCK'}));
 const plan=planProduct(p,[parent],entries,entries,{fields:{},pending:[]});assert.equal(plan.operations.length,0);assert.equal(countersFor(plan).productsUnchanged,1);assert.equal(countersFor(plan).productsClassified,0);
});
test('Resumen sin detalles solo es válido con cantidad cero explícita',()=>{
 const p=base(),heading=[`[${p.code}] ${p.name} (0)`,'','',''];
 const invalid=readInventory(workbook([rows(p)[0],heading]),'test.xlsx');assert.equal(invalid.products[0].critical,true);
 heading[3]=0;const zero=readInventory(workbook([rows(p)[0],heading]),'test.xlsx');assert.equal(zero.products[0].critical,false);assert.equal(zero.products[0].stock,0);
});

 test('Gemini sin aportes válidos no se atribuye productos por IA',async()=>{
 const fetcher=async()=>new Response(JSON.stringify({candidates:[{content:{parts:[{text:JSON.stringify([{code:'a',categoriaId:'inexistente',marcaId:null}])}]}}]}));
 const [r]=await classifyBatch([{code:'a',name:'Producto desconocido'}],catalog,rules,{apiKey:'fake',model:'test',fetcher});
 assert.equal(r.source,'reglas');assert.equal(r.pending.length,2);
 });
