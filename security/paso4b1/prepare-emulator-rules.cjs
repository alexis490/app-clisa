// Emulator proposal only. Never deploys; starts from the freshly inspected deployed rules.
const fs=require('node:fs');
let text=fs.readFileSync('security/paso4b1/production-inspection/deployed-0-firestore.proposed.rules','utf8');
for(const collection of ['productos','existencias','registros']){
  const match=new RegExp(`match /${collection}/\\{[^}]+\\} \\{`).exec(text);
  if(!match)throw Error('Missing match '+collection);
  const start=match.index,body=start+match[0].length;let end=body,depth=1;
  for(;end<text.length&&depth;end++){if(text[end]==='{')depth++;if(text[end]==='}')depth--;}
  const piece=text.slice(start,end).replace(/allow (create|update): if /g,'allow $1: if inventarioLibre4b1() && ');
  text=text.slice(0,start)+piece+text.slice(end);
}
text=text.replace('    match /productos/{productoId}',`    function inventarioLibre4b1() {
      return !exists(/databases/$(database)/documents/importaciones/_control_clisa)
        || get(/databases/$(database)/documents/importaciones/_control_clisa).data.get('locked',true) == false;
    }
    match /productos/{productoId}`);
text=text.replace('allow create: if importacionValida(importacionId);','allow create: if false;');
fs.writeFileSync('security/paso4b1/firestore.emulator.rules',text);
