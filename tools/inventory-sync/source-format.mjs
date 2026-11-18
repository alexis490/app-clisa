import XLSX from 'xlsx';
import { sha } from './canonical.mjs';

const norm=value=>String(value??'').normalize('NFD').replace(/\p{M}/gu,'').toLowerCase().replace(/[^a-z0-9]/g,'');
const aliases={serial:['lotendeserie','lotenumerodeserie','numerodeserie','serie','lote','serial','lotserialnumber'],code:['codigo','codigoproducto','referenciainterna','sku','internalreference'],name:['producto','nombre','nombreproducto','product'],quantity:['cantidad','stock','quantity','existencias']};
const text=cell=>cell==null?'':cell.t==='n'?XLSX.utils.format_cell(cell):String(cell.v??'');
function group(value){
 const s=value.trim();if(!s.startsWith('['))return null;let depth=0;
 for(let i=0;i<s.length;i++){if(s[i]==='[')depth++;if(s[i]===']'&&--depth===0)return s.slice(1,i).trim()&&s.slice(i+1).trim()?{code:s.slice(1,i),name:s.slice(i+1).trim()}:null;}
 return null;
}

/** Structural presence only: no duplicate checks, totals, classification, database reads or writes. */
export function inspectInventoryFormat(input,filename){
 const bytes=Buffer.from(input),extension=filename.split('.').at(-1).toLowerCase();
 if(!bytes.length||bytes.length>25*1024*1024)throw Error('FILE_SIZE');
 if(!['xlsx','xls','csv'].includes(extension))throw Error('FILE_TYPE');
 if(extension==='xlsx'&&(bytes.length<4||bytes.readUInt16LE(0)!==0x4b50)||extension==='xls'&&!bytes.subarray(0,8).equals(Buffer.from('d0cf11e0a1b11ae1','hex')))throw Error('FILE_TYPE');
 const book=XLSX.read(extension==='csv'?new TextDecoder('utf-8',{fatal:true}).decode(bytes):bytes,{type:extension==='csv'?'string':'buffer',cellNF:true,cellFormula:true,raw:extension==='csv',sheetRows:250002});
 if(book.SheetNames.length>20)throw Error('SHEET_LIMIT');
 const checks={serial:false,code:false,name:false,quantity:false};let rows=0;
 for(const sheet of book.SheetNames){
  const ws=book.Sheets[sheet];if(!ws['!ref'])continue;
  const range=XLSX.utils.decode_range(ws['!ref']);if(range.e.r>=250000||range.e.c>1023)throw Error('ROW_COLUMN_LIMIT');
  let columns=null,brackets=false,serial=false,code=false,name=false,quantity=false;
  for(let r=range.s.r;r<=range.e.r;r++){
   if(++rows>250000)throw Error('ROW_LIMIT');
   const cells=Array.from({length:range.e.c+1},(_,c)=>ws[XLSX.utils.encode_cell({r,c})]),values=cells.map(text);
   if(values.some(v=>v.length>1024))throw Error('CELL_LIMIT');
   if(!columns){
    const found=Object.fromEntries(Object.entries(aliases).map(([key,names])=>[key,values.map((v,i)=>names.includes(norm(v))?i:-1).filter(i=>i>=0)]));
    const recognized=Object.entries(found).filter(([,indices])=>indices.length===1);
    if(recognized.length>=2&&new Set(recognized.map(([,indices])=>indices[0])).size===recognized.length)columns=Object.fromEntries(recognized.map(([key,indices])=>[key,indices[0]]));
    continue;
   }
   const lot=(values[columns.serial]??'').trim(),product=(values[columns.name]??'').trim();
   const heading=group(lot.replace(/ \(\d+\)$/,''))||(!lot?group(product):null);
   if(heading){brackets=true;code=true;name=true;}
   const isTotal=['total','totalgeneral','subtotal'].includes(norm(lot))||['total','totalgeneral','subtotal'].includes(norm(product));
   if(!heading&&!isTotal&&lot&&cells[columns.serial]?.f==null)serial=true;
   if((values[columns.code]??'').trim())code=true;
   if(product&&!isTotal)name=true;
   const q=cells[columns.quantity];
   if(q&&q.f==null&&q.t!=='e'&&q.t!=='b'&&String(q.v??'').trim()!==''&&Number.isFinite(Number(q.v)))quantity=true;
  }
  // All required evidence must belong to one recognized table, not unrelated sheets.
  if(columns){const candidate={serial:columns.serial!=null&&brackets&&serial,code:columns.code!=null&&code,name:columns.name!=null&&name,quantity:columns.quantity!=null&&quantity};
   if(Object.values(candidate).filter(Boolean).length>Object.values(checks).filter(Boolean).length)Object.assign(checks,candidate);
   if(Object.values(candidate).every(Boolean))return {status:'FORMAT_CHECKED',fileSha256:sha(bytes),checks:candidate,ready:true};}
 }
 return {status:'FORMAT_CHECKED',fileSha256:sha(bytes),checks,ready:false};
}
