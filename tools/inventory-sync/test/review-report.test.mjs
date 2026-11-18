import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { readInventory } from '../source-reader.mjs';
import { productRecordRows,reviewAccumulator } from '../review-report.mjs';
const original=readInventory(readFileSync(new URL('../../../app/src/androidTest/assets/upload-source.xlsx',import.meta.url)),'stock.xlsx');
const sample=original.products.filter(p=>!p.critical&&p.stock>0).slice(0,5);
const unit=(p,previous,excluded=false)=>({source:structuredClone(p),recordRowCount:productRecordRows(p).size,plan:{excluded,reason:'Conflicto comprobado'},input:{products:previous===null?[]:[{data:{stock:previous}}]}});

test('Compara stock y cuenta registros completos sin confundir filas con incidencias',()=>{
 const a=reviewAccumulator({rowCount:4043},[]),[up,down,same,fresh,excluded]=sample;
 for(const u of [unit(up,up.stock-1),unit(down,down.stock+1),unit(same,same.stock),unit(fresh,null),unit(excluded,excluded.stock,true)])a.add(u);
 const {report,extraIssues}=a.finish(7);
 assert.deepEqual(report.comparison,{unchanged:1,increased:1,decreased:1,newProducts:1,absent:7,unknownStock:0});
 assert.equal(report.uniqueProducts,5);assert.equal(report.validProducts,4);assert.equal(report.issueCount,1);assert.equal(extraIssues[0].type,'DATABASE_CONFLICT');
 assert.equal(report.validation.validRecords,sample.slice(0,4).reduce((n,p)=>n+productRecordRows(p).size,0));
 assert.equal(report.validation.separatedRecords,productRecordRows(excluded).size);
});
test('Las cinco categorías de calidad son excluyentes y conservan todos los motivos',()=>{
 const types=[['DUPLICATE_SKU'],['DUPLICATE_SERIAL'],['INVALID_SERIAL',''],['INVALID_QUANTITY','S1',true],['INVALID_QUANTITY','S2',false],['CELL_FORMAT']];
 const issues=types.map(([type,serial,negative],i)=>({type,serial,negative,sheet:'Datos',row:i+2}));
 const {report}=reviewAccumulator({rowCount:10},issues).finish(null);
 assert.deepEqual(report.quality,{duplicateCodes:1,duplicateSeries:1,emptySeries:1,negativeQuantities:1,otherErrors:2,negativeOnly:true});
 assert.equal(report.issueCount,6);assert.equal(report.validation.separatedRecords,6);assert.equal(report.comparison.absent,null);
});
test('Las filas planas y los códigos duplicados no inflan productos únicos ni registros',()=>{
 const p={code:'SKU',name:'Producto',sheet:'Datos',row:2,stock:1,serials:[{sheet:'Datos',row:2,code:'S1',quantity:1}]};
 assert.equal(productRecordRows(p).size,1);
 const a=reviewAccumulator({rowCount:3},[]);a.add(unit(p,null,true));a.add(unit({...p,row:3,serials:[{...p.serials[0],row:3}]},null,true));
 assert.equal(a.finish(0).report.uniqueProducts,1);
});
test('Los valores no verificables permanecen desconocidos y no se inventan ceros',()=>{
 const a=reviewAccumulator({rowCount:50},[]),u=unit(sample[0],undefined);u.source.serialCount=2000;delete u.recordRowCount;a.add(u);
 const {report}=a.finish(null);assert.equal(report.comparison.unknownStock,1);assert.equal(report.comparison.absent,null);assert.equal(report.validation.validRecords,null);
});
test('Los expedientes antiguos sin detalle negativo se identifican como cantidades inválidas',()=>{
 const {report}=reviewAccumulator({rowCount:2},[{type:'INVALID_QUANTITY',sheet:'Datos',row:2,serial:'S1'}]).finish(0);
 assert.equal(report.quality.negativeOnly,false);assert.equal(report.quality.negativeQuantities,1);assert.equal(report.quality.otherErrors,0);
});
