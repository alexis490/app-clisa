import http from 'node:http';
import { pathToFileURL } from 'node:url';
import { readHttpConfig } from './http-config.mjs';
import { createReadOnlyFirebase } from './http-firebase.mjs';
import { createHttpApp } from './http-app.mjs';
import { probeGemini } from './gemini-probe.mjs';

export function createHttpServer(options){
 const server=http.createServer({maxHeaderSize:16384},createHttpApp(options));
 server.requestTimeout=options.config.requestTimeoutMs;
 server.headersTimeout=Math.min(10000,options.config.requestTimeoutMs);
 server.keepAliveTimeout=5000;server.maxRequestsPerSocket=100;
 server.setTimeout(Math.min(10000,options.config.requestTimeoutMs),socket=>socket.destroy());
 server.on('clientError',(error,socket)=>{if(socket.writable)socket.end('HTTP/1.1 400 Bad Request\r\nConnection: close\r\nContent-Length: 0\r\n\r\n');});
 return server;
}
if(process.argv[1]&&import.meta.url===pathToFileURL(process.argv[1]).href){
 let firebase,server;
 try{
  const settings=readHttpConfig();firebase=createReadOnlyFirebase(settings);
  server=createHttpServer({config:settings.config,firebase,logger:entry=>console.log(JSON.stringify(entry))});
  server.listen(settings.config.port,'0.0.0.0',()=>{
   console.log(JSON.stringify({event:'listening',port:settings.config.port}));
   if(process.env.VERIFY_GEMINI_ON_STARTUP==='true')void probeGemini({apiKey:process.env.GEMINI_API_KEY,model:process.env.GEMINI_MODEL})
    .then(result=>console.log(JSON.stringify({event:'gemini_probe',...result})));
  });
  server.on('error',()=>{console.error(JSON.stringify({event:'startup_failed',code:'SERVER_UNAVAILABLE'}));process.exit(1);});
  let stopping=false;
  const stop=()=>{
   if(stopping)return;stopping=true;
   const timer=setTimeout(()=>{server.closeAllConnections();process.exit(0);},10000);timer.unref();
   server.close(async()=>{try{await firebase?.close();}finally{clearTimeout(timer);process.exit(0);}});
   server.closeIdleConnections();
  };
  process.once('SIGTERM',stop);process.once('SIGINT',stop);
 }catch{console.error(JSON.stringify({event:'startup_failed',code:'INVALID_CONFIGURATION'}));process.exitCode=1;}
}
