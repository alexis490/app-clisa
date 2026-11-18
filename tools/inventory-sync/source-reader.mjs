import XLSX from 'xlsx';
import { quantity, validCode, sha } from './canonical.mjs';

const norm=s=>String(s??'').normalize('NFD').replace(/\p{M}/gu,'').toLowerCase().replace(/[^a-z0-9]/g,'');
const aliases={code:['codigo','codigoproducto','referenciainterna','sku','internalreference'],name:['producto','nombre','nombreproducto','product'],serial:['lotendeserie','lotenumerodeserie','numerodeserie','serie','lote','serial','lotserialnumber'],quantity:['cantidad','stock','quantity']};
const label=text=>{
 if(!text.startsWith('['))return null;let depth=0;
 for(let i=0;i<text.length;i++){if(text[i]==='[')depth++;if(text[i]===']'&&--depth===0)return {code:text.slice(1,i),name:text.slice(i+1).trim()};}
 return null;
};
const cellText=c=>c==null?'':c.t==='n'?XLSX.utils.format_cell(c):String(c.v??'');
const count=c=>{if(c?.f||c?.t==='e'||c?.t==='b')return null;const s=String(c?.v??'');return /^\d+$/.test(s)&&quantity(Number(s))?Number(s):null;};

/** Reads values only. Product totals never include summary rows twice. */
export function readInventory(bytes,filename,mappings={}){
 if(!Buffer.isBuffer(bytes))bytes=Buffer.from(bytes);
 if(!bytes.length||bytes.length>25*1024*1024)throw Error('FILE_SIZE');
 const extension=filename.split('.').at(-1).toLowerCase();
 if(!['xlsx','xls','csv'].includes(extension))throw Error('FILE_TYPE');
 if(extension==='xlsx'&&(bytes.length<4||bytes.readUInt16LE(0)!==0x4b50)||extension==='xls'&&!bytes.subarray(0,8).equals(Buffer.from('d0cf11e0a1b11ae1','hex')))throw Error('FILE_TYPE');
 const book=XLSX.read(extension==='csv'?new TextDecoder('utf-8',{fatal:true}).decode(bytes):bytes,{type:extension==='csv'?'string':'buffer',cellNF:true,cellFormula:true,raw:extension==='csv',sheetRows:250002});
 if(book.SheetNames.length>20)throw Error('SHEET_LIMIT');
 const products=[],issues=[],mappingRequired=[];let rowCount=0;
 const issue=(p,sheet,row,type,description,serial=null,details={})=>{const value={productCode:p?.code??null,productName:p?.name??null,serial,sheet,row,type,description,reason:'No se pueden determinar existencias inequívocas.',...details};issues.push(value);if(p)p.critical=true;};
 for(const sheet of book.SheetNames){
  const ws=book.Sheets[sheet];if(!ws['!ref'])continue;
  const range=XLSX.utils.decode_range(ws['!ref']);if(range.e.r>=250000||range.e.c>1023)throw Error('ROW_COLUMN_LIMIT');
  let columns=null,current=null,grouped=false;const flat=new Map();
  const mapping=Object.hasOwn(mappings,sheet)?mappings[sheet]:undefined;
  if(mapping&&(!['code','name','serial','quantity'].every(k=>Number.isInteger(mapping[k])&&mapping[k]>=0&&mapping[k]<=range.e.c)||new Set(['code','name','serial','quantity'].map(k=>mapping[k])).size!==4||!Number.isInteger(mapping.headerRow)||mapping.headerRow<1))throw Error('INVALID_MAPPING');
  for(let r=range.s.r;r<=range.e.r;r++){
   const cells=Array.from({length:range.e.c+1},(_,c)=>ws[XLSX.utils.encode_cell({r,c})]);
   const texts=cells.map(cellText);if(texts.some(s=>s.length>1024))throw Error('CELL_LIMIT');if(texts.every(s=>!s.trim()))continue;if(++rowCount>250000)throw Error('ROW_LIMIT');
   if(!columns){
    const detected=Object.fromEntries(Object.entries(aliases).map(([k,a])=>[k,texts.map((s,i)=>a.includes(norm(s))?i:-1).filter(i=>i>=0)]));
    if(Object.values(detected).every(v=>v.length===1)){columns=Object.fromEntries(Object.entries(detected).map(([k,v])=>[k,v[0]]));continue;}
    if(mapping&&r+1===mapping.headerRow){columns=mapping;continue;}
    if(mapping&&r+1<mapping.headerRow)continue;
    if(r<range.s.r+30)continue;
    break;
   }
   const {code:ci,name:ni,serial:si,quantity:qi}=columns;
   const code=texts[ci],name=texts[ni],serial=texts[si],q=count(cells[qi]);
   if([serial,name].some(s=>['total','totalgeneral','subtotal'].includes(norm(s)))&&!code.trim()){current=null;continue;}
   const suffix=serial.match(/^(.*) \((\d+)\)$/),heading=label((suffix?.[1]??serial).trim())??(!serial.trim()?label(name.trim()):null);
   const location={sheet,row:r+1};
   if(heading||suffix){
    grouped=true;current={code:heading?.code??null,name:heading?.name??suffix[1],reportedQuantity:q,reportedSerialCount:suffix?Number(suffix[2]):null,serials:[],...location};products.push(current);
    if(!validCode(current.code)||!current.name.trim())issue(current,sheet,r+1,'IDENTITY','Código o nombre obligatorio ausente.');
    if(code.trim()&&code!==current.code)issue(current,sheet,r+1,'REFERENCE_MISMATCH','La referencia interna difiere del código del encabezado.');
    if(texts[qi].trim()&&q===null)issue(current,sheet,r+1,'INVALID_QUANTITY','Cantidad de resumen inválida.',null,{negative:Number(cells[qi]?.v)<0});
    if(cells.some(c=>c?.f||c?.t==='e'))issue(current,sheet,r+1,'CELL_FORMAT','Fórmula o error de celda: exporta valores.');
    continue;
   }
   const detail=label(name.trim()),explicit=detail?.code??code;
   let owner=current;
   if(!grouped){
    owner=flat.get(explicit);
    if(!owner){owner={code:explicit||null,name:detail?.name??name,reportedQuantity:null,reportedSerialCount:null,serials:[],...location};flat.set(explicit,owner);products.push(owner);}
    if(!validCode(owner.code)||!owner.name.trim())issue(owner,sheet,r+1,'IDENTITY','Código o nombre obligatorio ausente.');
   }
   if(!owner){issue(explicit?{code:explicit,name:detail?.name??name}:null,sheet,r+1,'UNASSOCIATED','Registro sin producto inequívoco.',serial);continue;}
   if(explicit&&explicit!==owner.code){issue(owner,sheet,r+1,'UNASSOCIATED','Serie con referencia distinta del encabezado.',serial);issue({code:explicit,name:detail?.name??name},sheet,r+1,'UNASSOCIATED','La referencia pertenece a un registro ambiguo.',serial);continue;}
   if(name.trim()&&(detail?.name??name)!==owner.name)issue(owner,sheet,r+1,'NAME_MISMATCH','Nombres diferentes para el mismo producto.',serial);
   if(cells.some(c=>c?.f||c?.t==='e'))issue(owner,sheet,r+1,'CELL_FORMAT','Fórmula o error de celda: exporta valores.',serial);
   // Numeric identifiers beyond Excel precision are never silently accepted.
   if([ci,si].some(i=>cells[i]?.t==='n'&&(!Number.isSafeInteger(cells[i].v)||Math.abs(cells[i].v)>=1e15)))issue(owner,sheet,r+1,'IDENTIFIER_PRECISION','Identificador numérico sin precisión garantizada; expórtalo como texto.',serial);
   if(!validCode(serial))issue(owner,sheet,r+1,'INVALID_SERIAL','Serie/lote vacío o con espacios exteriores.',serial);
   if(q===null)issue(owner,sheet,r+1,'INVALID_QUANTITY','Cantidad negativa, fraccionaria o inválida.',serial,{negative:Number(cells[qi]?.v)<0});
   owner.serials.push({code:serial,quantity:q,...location});
  }
  if(!columns){mappingRequired.push({sheet,rows:Array.from({length:Math.min(10,range.e.r-range.s.r+1)},(_,n)=>({row:range.s.r+n+1,values:Array.from({length:Math.min(30,range.e.c+1)},(_,c)=>cellText(ws[XLSX.utils.encode_cell({r:range.s.r+n,c})]))}))});}
 }
 const skus=new Map(),serials=new Map();
 for(const p of products){
  if(issues.some(e=>e.productCode!==null&&e.productCode===p.code))p.critical=true;
  if(issues.some(e=>e.productCode===null&&e.productName===p.name&&['IDENTITY','UNASSOCIATED'].includes(e.type)))p.critical=true;
  if(p.code){const prev=skus.get(p.code);if(prev){issue(prev,prev.sheet,prev.row,'DUPLICATE_SKU','Código de producto duplicado.');issue(p,p.sheet,p.row,'DUPLICATE_SKU','Código de producto duplicado.');}skus.set(p.code,p);}
  let total=0;
  for(const s of p.serials){
   const prev=serials.get(s.code);if(prev){issue(prev.p,prev.s.sheet,prev.s.row,'DUPLICATE_SERIAL','Serie/lote duplicado.',s.code);issue(p,s.sheet,s.row,'DUPLICATE_SERIAL','Serie/lote duplicado.',s.code);}else serials.set(s.code,{p,s});
   if(s.quantity===null||!quantity(total+s.quantity))issue(p,s.sheet,s.row,'INVALID_TOTAL','Stock inválido o fuera de rango.',s.code);else total+=s.quantity;
  }
  if(p.reportedQuantity!==null&&p.reportedQuantity!==total)issue(p,p.sheet,p.row,'SUMMARY_MISMATCH','El resumen no coincide con la suma de series.');
  if(!p.serials.length&&p.reportedQuantity!==0)issue(p,p.sheet,p.row,'SUMMARY_WITHOUT_DETAIL','No hay series ni un resumen explícito de cantidad cero.');
  if(p.reportedSerialCount!==null&&p.reportedSerialCount!==p.serials.length)issue(p,p.sheet,p.row,'COUNT_MISMATCH','El recuento de series no coincide con el resumen.');
  p.stock=total;p.critical=Boolean(p.critical);
 }
 if(products.length>5000||issues.length>10000)throw Error('SOURCE_LIMIT');
 return {fileSha256:sha(bytes),filename,bytes:bytes.length,rowCount,products,issues,mappingRequired};
}
