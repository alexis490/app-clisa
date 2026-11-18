import test from 'node:test';
import assert from 'node:assert/strict';
import {exportUnit} from '../import-export.mjs';
const unit=()=>({source:{code:'P',name:'Producto',stock:3,sheet:'Datos',row:2},input:{products:[{id:'p',data:{stock:5}}]},plan:{excluded:false,productId:'p',operations:[{type:'UPDATE_PRODUCT',path:'productos/p',fields:{stock:3,secret:'must-not-export'}}]},receipt:{status:'APPLIED'},issues:[]});
test('exportación usa recibos aplicados y stock archivado, sin secretos ni tienda inventada',()=>{
 const row=exportUnit(unit(),'job/productos/00000');assert.equal(row.product.beforeStock,5);assert.equal(row.product.newStock,3);assert.equal(row.product.difference,-2);assert.equal(row.product.store,null);assert.equal(row.operations[0].state,'COMPLETADO');assert.ok(!JSON.stringify(row).includes('must-not-export'));
});
test('propuestas fallidas no muestran stock nuevo ni error interno sensible',()=>{
 const u=unit();u.receipt={status:'FAILED',reason:'secret-token-sensitive'};const r=exportUnit(u,'j/u');assert.equal(r.product.newStock,null);assert.equal(r.product.difference,null);assert.equal(r.operations[0].state,'FALLIDO');assert.equal(r.operations[0].error,'DETALLE_NO_DISPONIBLE');
});
test('producto nuevo no inventa stock anterior, datos ausentes no inventan operación',()=>{
 const u=unit();u.input.products=[];u.plan.operations[0].type='CREATE_PRODUCT';const r=exportUnit(u,'j/u');assert.equal(r.product.previousExists,false);assert.equal(r.product.beforeStock,null);assert.equal(r.product.difference,null);
 delete u.plan.operations;const missing=exportUnit(u,'j/u');assert.equal(missing.product.type,null);assert.equal(missing.operations[0].type,null);
});
test('omitidos y productos sin recibo conservan exclusión y estado desconocido',()=>{
 const u=unit();u.input.products=[];u.plan={excluded:true,reason:'Conflicto de inventario',operations:[]};u.receipt={status:'OMITTED'};
 const r=exportUnit(u,'j/u');assert.equal(r.product.previousExists,null);assert.equal(r.product.newStock,null);assert.equal(r.operations[0].state,'OMITIDO');assert.equal(r.issues[0].type,'DATABASE_CONFLICT');
 delete u.receipt;assert.equal(exportUnit(u,'j/u').product.state,'NO_DISPONIBLE');
});
