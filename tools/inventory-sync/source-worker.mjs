import { parentPort,workerData } from 'node:worker_threads';
import { readInventory } from './source-reader.mjs';
try{parentPort.postMessage({source:readInventory(Buffer.from(workerData.bytes),workerData.filename,workerData.mappings)});}
catch{parentPort.postMessage({error:'INVALID_SOURCE'});}
