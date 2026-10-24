import { createHash } from 'node:crypto';

// Matches SyncCanonical (UTF-16 string lengths, integer numbers only).
export function canonical(v) {
  if(v===null)return 'n;';
  if(typeof v==='string')return `s${v.length}:${v}`;
  if(typeof v==='boolean')return v?'b1;':'b0;';
  if(typeof v==='number'&&Number.isSafeInteger(v))return `i${v};`;
  if(Array.isArray(v))return `l${v.length}:`+v.map(canonical).join('');
  if(v&&Object.getPrototypeOf(v)===Object.prototype)return `m${Object.keys(v).length}:`+Object.keys(v).sort().map(k=>canonical(k)+canonical(v[k])).join('');
  throw Error('UNSUPPORTED_WIRE_VALUE');
}
export const sha=bytes=>createHash('sha256').update(bytes).digest('hex');
export const hash=value=>sha(canonical(value));
export const productId=sku=>'p_v1_'+hash(['CLISA_PRODUCT_ID_V1',sku]);
export const existenceId=(parent,serial)=>'e_v1_'+hash(['CLISA_EXISTENCE_ID_V1',parent,serial]);
export function check(ok,reason){if(!ok)throw Error(reason);}
export const validCode=s=>typeof s==='string'&&s.length>0&&s===s.trim();
export const validId=s=>validCode(s)&&!s.includes('/')&&!['.','..'].includes(s)&&!/^__.*__$/.test(s)&&Buffer.byteLength(s)<=1500;
export const quantity=n=>Number.isSafeInteger(n)&&n>=0;
export const state=n=>n===0?'SIN_STOCK':'DISPONIBLE';
