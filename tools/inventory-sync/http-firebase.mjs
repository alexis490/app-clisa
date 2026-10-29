import { initializeApp,cert,deleteApp } from 'firebase-admin/app';
import { getAuth } from 'firebase-admin/auth';
import { getFirestore } from 'firebase-admin/firestore';
import { randomUUID } from 'node:crypto';

/** Read-only facade. No implicit ADC/CLI credentials and no inventory SDK exposed to HTTP routes. */
export function createReadOnlyFirebase({config,account}){
 if(!account)return null;
 if(Object.hasOwn(process.env,'FIRESTORE_EMULATOR_HOST')||Object.hasOwn(process.env,'FIREBASE_AUTH_EMULATOR_HOST'))throw Error('INVALID_CONFIGURATION');
 const app=initializeApp({projectId:config.projectId,credential:cert(account)},'http-'+randomUUID());
 const auth=getAuth(app),db=getFirestore(app);
 return Object.freeze({verifyIdToken:token=>auth.verifyIdToken(token,true),getUser:uid=>auth.getUser(uid),
  readProfile:async uid=>{const doc=await db.doc(`usuarios/${uid}`).get();return doc.exists?{role:doc.get('role'),estado:doc.get('estado')}:null;},
  ...(config.automaticImportEnabled?{inventoryDb:db}:{}),
  close:async()=>{await db.terminate();await deleteApp(app);}});
}
