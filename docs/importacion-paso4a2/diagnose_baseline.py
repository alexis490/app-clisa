import openpyxl,re,collections,json,csv
from pathlib import Path
from decimal import Decimal
source=Path(r'C:\Users\TUF\Documents\stock.production.lot123 - 2000.xlsx')
w=openpyxl.load_workbook(source,read_only=True,data_only=False)
issues=[]; groups=[]; seenp={}; seens={}; current=None

def label(v):
 t=(v or '').strip()
 if not t.startswith('['):return None
 depth=0
 for i,c in enumerate(t):
  if c=='[':depth+=1
  if c==']':
   depth-=1
   if depth==0:
    a,b=t[1:i].strip(),t[i+1:].strip()
    return (a,b) if a and b else None

def issue(kind,row,p,cause):issues.append(dict(type=kind,sheet=s.title,row=row,sku=p['code'] if p else None,product=p['name'] if p else None,cause=cause))
for s in w:
 for row,values in enumerate(s.iter_rows(values_only=True),1):
  a,b,c,q=values[:4];a=str(a or '');b=str(b or '');c=str(c or '')
  if row==1:continue
  if not any(v is not None for v in values):continue
  suffix=re.fullmatch(r'^(.*) \(([0-9]+)\)$',a.strip()) if not b and not c else None
  lt=label(suffix[1] if suffix else a); dt=label(c)
  heading=lt or (dt if not a else None)
  if heading or suffix:
   code,name=heading if heading else (None,suffix[1])
   current={'code':code,'name':name,'row':row,'sheet':s.title,'quantity':q,'declared':int(suffix[2]) if suffix else None,'serials':[],'raw':a}
   groups.append(current)
   if code in seenp and code is not None:issue('DUPLICATE_PRODUCT',row,current,'Duplicate SKU; first row '+str(seenp[code]))
   if code is None:issue('MISSING_SKU',row,current,'No bracket SKU in heading')
   else:seenp.setdefault(code,row)
   continue
  explicit=dt[0] if dt else b.strip() or None
  if not current or explicit is not None and explicit!=current['code']:
   issue('UNASSOCIATED_SERIAL',row,current,a); continue
  if c and (dt[1] if dt else c.strip())!=current['name']:
   issue('NAME_MISMATCH',row,current,c);continue
  if a.strip() in seens:issue('DUPLICATE_SERIAL',row,current,a+' first row '+str(seens[a.strip()]))
  seens.setdefault(a.strip(),row)
  if a!=a.strip():issue('SERIAL_WHITESPACE',row,current,a)
  if q is None or not isinstance(q,(int,float)) or Decimal(str(q))%1:issue('INVALID_QUANTITY',row,current,str(q))
  elif q<0:issue('NEGATIVE_QUANTITY',row,current,str(q))
  current['serials'].append({'row':row,'serial':a,'quantity':q,'reference':b,'product':c})
 for p in groups:
  if p['sheet']!=s.title:continue
  if p['declared'] is not None and p['declared']!=len(p['serials']):issue('SUMMARY_COUNT',p['row'],p,str(p['declared']))
  if not p['serials'] and (p['quantity'] or 0)>0:issue('SUMMARY_NO_DETAIL',p['row'],p,str(p['quantity']))
  elif p['serials'] and p['quantity'] is not None and all(x['quantity'] is not None for x in p['serials']) and sum(Decimal(str(x['quantity'])) for x in p['serials'])!=Decimal(str(p['quantity'])):issue('SUMMARY_QUANTITY',p['row'],p,str(p['quantity']))
out=Path('docs/importacion-paso4a2')
counts=collections.Counter(x['serial'].strip() for p in groups for x in p['serials'])
skus=collections.Counter(p['code'] for p in groups if p['code'] is not None)
def valid(x):return x['quantity'] is not None and x['quantity']>=0 and x['serial']==x['serial'].strip() and counts[x['serial'].strip()]==1
vp=[p for p in groups if p['code'] is not None and skus[p['code']]==1 and all(valid(x) for x in p['serials'])]
metrics={'groups':len(groups),'serialRows':sum(len(p['serials']) for p in groups),'validSerialRows':sum(valid(x) for p in groups for x in p['serials']),'validUniqueSkuGroups':len(vp),'serialsInValidGroups':sum(len(p['serials']) for p in vp),'zeroQuantityGroups':sum(p['quantity']==0 for p in groups),'missingSkuGroups':sum(p['code'] is None for p in groups),'uninterpretedRows':0,'issueCounts':dict(collections.Counter(i['type'] for i in issues)),'incidents':len(issues)}
(out/'baseline-independent.json').write_text(json.dumps(metrics,indent=2),encoding='utf-8')
with (out/'incidencias-antes.csv').open('w',encoding='utf-8-sig',newline='') as f:
 writer=csv.DictWriter(f,fieldnames=['type','sheet','row','sku','product','cause']);writer.writeheader();writer.writerows(issues)
print('INCIDENTS',len(issues),collections.Counter(i['type'] for i in issues))
print('GROUPS',len(groups),'SERIALS',sum(len(p['serials']) for p in groups),'ZERO_GROUPS',sum(p['quantity']==0 for p in groups))
print('MISSING_SAMPLE',[(p['row'],p['raw'],p['serials'][:1]) for p in groups if p['code'] is None][:8])
print('OTHER', [i for i in issues if i['type'] not in ('MISSING_SKU','DUPLICATE_SERIAL')][:15])
