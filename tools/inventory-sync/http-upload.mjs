import Busboy from '@fastify/busboy';
import { HttpError } from './http-proposal.mjs';

/** Two bounded memory streams. No disk/temp directory or filename-derived path. */
export function readProposalUpload(req,config,signal,{automatic=false}={}){
 if(!/^multipart\/form-data\s*;/i.test(req.headers['content-type']??''))throw new HttpError(415,'MULTIPART_REQUIRED');
 return new Promise((resolve,reject)=>{
  let parser,settled=false,wireBytes=0;const result={},seen=new Set(),maxWire=config.excelBytes+config.contractBytes+65536;
  const cleanup=()=>{req.off('data',wire);req.off('aborted',abort);req.off('error',abort);signal.removeEventListener('abort',abort);};
  const fail=error=>{if(settled)return;settled=true;cleanup();if(parser){req.unpipe(parser);parser.destroy();}req.resume();reject(error);};
  const abort=()=>fail(new HttpError(408,'UPLOAD_INTERRUPTED'));
  const wire=chunk=>{wireBytes+=chunk.length;if(wireBytes>maxWire)fail(new HttpError(413,'UPLOAD_TOO_LARGE'));};
  try{parser=new Busboy({headers:req.headers,limits:{files:2,fields:0,parts:2,fileSize:config.excelBytes,headerPairs:20,headerSize:8192}});}catch{return fail(new HttpError(400,'INVALID_MULTIPART'));}
  parser.on('file',(name,file,filename,encoding,mime)=>{
   file.on('error',()=>fail(new HttpError(400,'INVALID_MULTIPART')));
   if(!['contract','excel'].includes(name)||seen.has(name)||typeof filename!=='string'||filename.length>200||encoding!=='7bit'&&encoding!=='binary'){
    file.resume();return fail(new HttpError(400,'INVALID_UPLOAD_PARTS'));
   }
   seen.add(name);const chunks=[];let length=0;
   const cap=name==='excel'?config.excelBytes:config.contractBytes;
   if(!(name==='excel'&&automatic?/\.(xlsx|xls|csv)$/i.test(filename):filename.toLowerCase().endsWith(name==='excel'?'.xlsx':'.json'))){file.resume();return fail(new HttpError(415,'INVALID_UPLOAD_TYPE'));}
   if(name==='excel'&&automatic)result.filename=filename;
   file.on('limit',()=>fail(new HttpError(413,'UPLOAD_TOO_LARGE')));
   file.on('data',chunk=>{if(settled)return;length+=chunk.length;if(length>cap)fail(new HttpError(413,'UPLOAD_TOO_LARGE'));else chunks.push(chunk);});
   file.on('end',()=>{if(!settled)result[name]=Buffer.concat(chunks,length);});
  });
  for(const event of ['filesLimit','fieldsLimit','partsLimit'])parser.on(event,()=>fail(new HttpError(400,'INVALID_UPLOAD_PARTS')));
  parser.on('error',()=>fail(new HttpError(400,'INVALID_MULTIPART')));
  parser.on('finish',()=>{
   if(settled)return;
   if(seen.size!==2||!result.contract?.length||!result.excel?.length)return fail(new HttpError(400,'MISSING_UPLOAD_PARTS'));
   if(!automatic&&(result.excel.length<4||result.excel.readUInt32LE(0)!==0x04034b50))return fail(new HttpError(415,'XLSX_REQUIRED'));
   settled=true;cleanup();resolve(result);
  });
  req.on('data',wire);req.once('aborted',abort);req.once('error',abort);signal.addEventListener('abort',abort,{once:true});
  if(signal.aborted)return abort();req.pipe(parser);
 });
}
