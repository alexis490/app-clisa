import { initializeApp, deleteApp } from 'firebase-admin/app';
import { getFirestore } from 'firebase-admin/firestore';
import { getAuth } from 'firebase-admin/auth';
import { randomUUID } from 'node:crypto';
import { check } from './canonical.mjs';

export const PROJECT='demo-clisa-sync-4b1', HOST='127.0.0.1:8086', AUTH_HOST='127.0.0.1:9098';
const known=new WeakSet();
export function assertEmulator(runtime){
  check(process.env.FIRESTORE_EMULATOR_HOST===HOST&&process.env.FIREBASE_AUTH_EMULATOR_HOST===AUTH_HOST,'EMULATOR_ENDPOINT_REQUIRED');
  check(process.env.GCLOUD_PROJECT===PROJECT,'DEMO_PROJECT_REQUIRED');
  if(runtime)check(known.has(runtime)&&runtime.db.projectId===PROJECT,'UNTRUSTED_RUNTIME');
}
export function createEmulatorRuntime(){
  assertEmulator();
  const app=initializeApp({projectId:PROJECT},'sync-'+randomUUID());
  const runtime=Object.freeze({db:getFirestore(app),auth:getAuth(app),close:()=>deleteApp(app)});
  known.add(runtime);return runtime;
}
