import test from 'node:test';
import assert from 'node:assert/strict';
import { probeGemini } from '../gemini-probe.mjs';
const response=value=>new Response(JSON.stringify({candidates:[{content:{parts:[{text:JSON.stringify(value)}]}}]}));
test('Gemini readiness checks the same structured generation contract without sending inventory',async()=>{
 const result=await probeGemini({apiKey:'test-secret',model:'test-model',fetcher:async(url,request)=>{
  assert.equal(new URL(url).search,'');assert.equal(request.headers['x-goog-api-key'],'test-secret');const body=JSON.parse(request.body);assert.equal(body.generationConfig.responseJsonSchema.type,'array');assert.ok(!request.body.includes('test-secret'));return response([{ok:true}]);
 }});assert.deepEqual(result,{status:'ok',model:'test-model'});assert.ok(!JSON.stringify(result).includes('test-secret'));
});
test('Missing configuration and invalid model do not call Gemini',async()=>{
 const fetcher=()=>{throw Error('must not call');};assert.equal((await probeGemini({fetcher})).reason,'MISSING_CONFIGURATION');assert.equal((await probeGemini({apiKey:'secret',model:'invalid/key',fetcher})).reason,'INVALID_MODEL');
});
test('Provider, malformed response and network failures remain failures without leaking secret messages',async()=>{
 for(const fetcher of [async()=>new Response('PRIVATE test-secret',{status:403}),async()=>response([{ok:false}]),async()=>new Response('not json'),async()=>{throw Error('PRIVATE test-secret');}]){
  const result=await probeGemini({apiKey:'test-secret',model:'test-model',fetcher});assert.equal(result.status,'failed');assert.ok(!JSON.stringify(result).includes('PRIVATE'));assert.ok(!JSON.stringify(result).includes('test-secret'));
 }
});
