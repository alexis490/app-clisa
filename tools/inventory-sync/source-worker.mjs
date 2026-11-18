import { parentPort,workerData } from 'node:worker_threads';
import { readInventory } from './source-reader.mjs';
import { inspectInventoryFormat } from './source-format.mjs';
try{parentPort.postMessage({source:workerData.formatOnly?inspectInventoryFormat(workerData.bytes,workerData.filename):readInventory(Buffer.from(workerData.bytes),workerData.filename,workerData.mappings)});}
catch{parentPort.postMessage({error:'INVALID_SOURCE'});}
