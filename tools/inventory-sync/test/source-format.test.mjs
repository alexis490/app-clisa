import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import XLSX from 'xlsx';
import { inspectInventoryFormat } from '../source-format.mjs';
import { sha } from '../canonical.mjs';

const bytes=readFileSync(new URL('../../../app/src/androidTest/assets/upload-source.xlsx',import.meta.url));
const book=()=>XLSX.read(bytes,{type:'buffer'});
const encode=(workbook,bookType='xlsx')=>XLSX.write(workbook,{type:'buffer',bookType});
test('El archivo real activa los cuatro campos sin ejecutar análisis de inventario',()=>{
 const result=inspectInventoryFormat(bytes,'stock.production.lot123.xlsx');
 assert.equal(result.ready,true);assert.equal(result.fileSha256,sha(bytes));
 assert.deepEqual(result.checks,{serial:true,code:true,name:true,quantity:true});assert.equal(result.issues,undefined);assert.equal(result.products,undefined);
});
for(const [bookType,extension] of [['biff8','xls'],['csv','csv']])test(`El formato básico también funciona con ${extension}`,()=>{
 assert.equal(inspectInventoryFormat(encode(book(),bookType),`stock.${extension}`).ready,true);
});
test('Sin encabezado Cantidad se conserva la evidencia restante y no se habilita Analizar',()=>{
 const b=book(),ws=b.Sheets[b.SheetNames[0]];for(const key of Object.keys(ws))if(ws[key]?.v==='Cantidad')ws[key]={t:'s',v:'Otro campo'};
 const result=inspectInventoryFormat(encode(b),'stock.xlsx');assert.equal(result.ready,false);assert.equal(result.checks.quantity,false);assert.equal(result.checks.serial,true);
});
test('Los encabezados entre corchetes y las series son evidencias diferentes y ambas se requieren',()=>{
 const b=book(),ws=b.Sheets[b.SheetNames[0]];
 for(const key of Object.keys(ws))if(typeof ws[key]?.v==='string'&&ws[key].v.startsWith('['))ws[key]={t:'s',v:ws[key].v.replaceAll('[','').replaceAll(']','')};
 assert.equal(inspectInventoryFormat(encode(b),'stock.xlsx').checks.serial,false);
 const summary=XLSX.utils.book_new();XLSX.utils.book_append_sheet(summary,XLSX.utils.aoa_to_sheet([['Lote/Nº de serie','Referencia interna','Producto','Cantidad'],['[SKU-1] Producto','','',0]]),'Datos');
 assert.equal(inspectInventoryFormat(encode(summary),'stock.xlsx').ready,false);
});
test('Duplicados y cantidades negativas pertenecen al análisis posterior',()=>{
 const b=book(),ws=b.Sheets[b.SheetNames[0]];
 for(const key of Object.keys(ws))if(/^D\d+$/.test(key)&&ws[key].t==='n'){ws[key].v=-1;break;}
 assert.equal(inspectInventoryFormat(encode(b),'stock.xlsx').ready,true);
});
test('Un archivo sin tabla no se presenta como válido',()=>{
 const b=XLSX.utils.book_new();XLSX.utils.book_append_sheet(b,XLSX.utils.aoa_to_sheet([['Reporte vacío']]),'Datos');
 assert.deepEqual(inspectInventoryFormat(encode(b),'stock.xlsx').checks,{serial:false,code:false,name:false,quantity:false});
});
