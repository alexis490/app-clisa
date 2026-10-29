/** Optional deployment check: one tiny synthetic request, no inventory or credential logging. */
export async function probeGemini({apiKey,model,fetcher=fetch}={}){
 if(!apiKey||!model)return {status:'failed',reason:'MISSING_CONFIGURATION'};
 if(!/^[a-z0-9.-]+$/.test(model))return {status:'failed',reason:'INVALID_MODEL'};
 try{
  const response=await fetcher(`https://generativelanguage.googleapis.com/v1beta/models/${model}:generateContent`,{
   method:'POST',headers:{'Content-Type':'application/json','x-goog-api-key':apiKey},signal:AbortSignal.timeout(8000),
   body:JSON.stringify({contents:[{role:'user',parts:[{text:'Prueba técnica de conexión. Devuelve exclusivamente [{"ok":true}]. No hay datos de inventario.'}]}],generationConfig:{temperature:0,maxOutputTokens:256,responseMimeType:'application/json',responseJsonSchema:{type:'array',items:{type:'object',properties:{ok:{type:'boolean'}},required:['ok'],additionalProperties:false}}}})
  });
  if(!response.ok)return {status:'failed',reason:`HTTP_${response.status}`,model};
  let bytes=0,text='';for await(const chunk of response.body){bytes+=chunk.length;if(bytes>32768)return {status:'failed',reason:'RESPONSE_LIMIT',model};text+=Buffer.from(chunk).toString('utf8');}
  const envelope=JSON.parse(text),raw=envelope.candidates?.[0]?.content?.parts?.map(p=>p.text??'').join('');
  const value=JSON.parse(raw);return Array.isArray(value)&&value.length===1&&value[0]?.ok===true?{status:'ok',model}:{status:'failed',reason:'INVALID_RESPONSE',model};
 }catch{return {status:'failed',reason:'CONNECTION_OR_RESPONSE_FAILED',model};}
}
