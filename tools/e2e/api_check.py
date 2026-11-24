"""API regression check (all modules, two stores) on a disposable demo database. Run via run.sh."""
from safety import require_disposable_db  # stops unless this is the disposable test database
import json, sqlite3, sys, urllib.request, datetime
A='http://localhost:8080/api'; db=sqlite3.connect(require_disposable_db(sys.argv[1])); db.isolation_level=None
R={'pass':0,'fail':0}; FAILS=[]
def call(p,t=None,m='GET',b=None):
    r=urllib.request.Request(A+p,method=m,data=json.dumps(b).encode() if b is not None else None,headers={'Content-Type':'application/json',**({'Authorization':'Bearer '+t} if t else {})})
    try: x=urllib.request.urlopen(r); body=x.read(); return x.status,(json.loads(body) if body else None)
    except urllib.error.HTTPError as e:
        body=e.read(); return e.code,(json.loads(body) if body else None)
def chk(sec,name,a,b):
    ok=a==b; R['pass' if ok else 'fail']+=1
    if not ok: FAILS.append(f'[{sec}] {name}: got {a}, expected {b}')
q=lambda s,*a: db.execute(s,a).fetchall()
one=lambda s,*a: db.execute(s,a).fetchone()[0]
def login(u,p): return call('/auth/login',m='POST',b={'username':u,'password':p})
# A) login + roles
s,b=login('sysadmin','Admin12345'); chk('A','sysadmin login',(s,b['user']['role']),(200,'SYSTEM_ADMIN')); SYS=b['token']
s,b=login('shini.admin','Admin-Pass-1'); chk('A','store admin login',(s,b['user']['role'],b['user']['storeName']),(200,'STORE_ADMIN','Shini Supermarket')); SA=b['token']
s,b=login('shini.cashier','Cashier-Pass-1'); chk('A','cashier login',(s,b['user']['role']),(200,'CASHIER')); SC=b['token']
NA=login('neshan.admin','Admin-Pass-1')[1]['token']; NC=login('neshan.cashier','Cashier-Pass-1')[1]['token']
chk('A','wrong password',login('shini.cashier','nope')[0],401)
chk('A','no token',call('/products')[0],401)
matrix=[('/admin/stores',{SYS:200,SA:403,SC:403}),('/store/cashiers',{SYS:403,SA:200,SC:403}),('/products',{SYS:403,SA:200,SC:200}),
        ('/sales/suspended',{SYS:403,SA:200,SC:200}),('/reports/inventory',{SYS:403,SA:200,SC:403}),('/reports/summary?from=2026-11-01&to=2026-11-30',{SYS:403,SA:200,SC:403})]
names={SYS:'sysadmin',SA:'store admin',SC:'cashier'}
for path,exp in matrix:
    for t,st in exp.items(): chk('A',f'{names[t]} GET {path.split("?")[0]}',call(path,t)[0],st)
chk('A','cashier cannot add product',call('/products',SC,'POST',{'name':'X1','barcode':'12345678','category':'dairy','sellingPrice':1,'quantity':1,'minStock':0,'supplierName':'AB'})[0],403)
# B) isolation
sp=call('/products',SA)[1]; np_=call('/products',NA)[1]
chk('B','shini sees only store 1',all(p['storeId']==1 for p in sp),True); chk('B','neshan sees only store 2',all(p['storeId']==2 for p in np_),True)
chk('B','other store product -> 404',call(f'/products/{np_[0]["productId"]}',SA)[0],404)
chk('B','cannot sell other store product',call('/sales',SC,'POST',{'items':[{'productId':np_[0]['productId'],'quantity':1}],'paymentMethod':'CASH'})[0] in (400,404),True)
chk('B','storeId param ignored in reports',call('/reports/inventory?storeId=1',NA)[1]['totalProducts'],one('select count(*) from products where store_id=2'))
# C) product CRUD
s,p=call('/products',SA,'POST',{'name':'Check Halva 500g','barcode':'6259990000011','category':'snacks','sellingPrice':9,'quantity':4,'minStock':5,'supplierName':'Nablus Sweets','costPrice':6.5})
chk('C','insert 201',s,201); pid=p['productId']
chk('C','insert in DB',q('select name,quantity,cost_price,store_id from products where product_id=?',pid),[('Check Halva 500g',4,6.5,1)])
chk('C','duplicate barcode 409',call('/products',SA,'POST',{'name':'Dup','barcode':'6259990000011','category':'snacks','sellingPrice':1,'quantity':1,'minStock':0,'supplierName':'AB'})[0],409)
chk('C','same barcode allowed in other store',call('/products',NA,'POST',{'name':'Neshan Halva','barcode':'6259990000011','category':'snacks','sellingPrice':1,'quantity':1,'minStock':0,'supplierName':'AB'})[0],201)
s,_=call(f'/products/{pid}',SA,'PUT',{'name':'Check Halva 500g','barcode':'6259990000011','category':'snacks','sellingPrice':10,'quantity':12,'minStock':5,'supplierName':'Nablus Sweets','costPrice':6.5})
chk('C','update',(s,q('select selling_price,quantity from products where product_id=?',pid)),(200,[(10.0,12)]))
s,tmp=call('/products',SA,'POST',{'name':'Check Temp','barcode':'6259990000028','category':'other','sellingPrice':1,'quantity':1,'minStock':0,'supplierName':'AB'})
chk('C','delete',(call(f'/products/{tmp["productId"]}',SA,'DELETE')[0],one('select count(*) from products where product_id=?',tmp['productId'])),(204,0))
chk('C','cashier gets no cost',[x['costPrice'] for x in call('/products',SC)[1] if x['productId']==pid],[None])
# D) POS
milk=1; st0=one('select quantity from products where product_id=1')
n_before=one('select count(*) from sales where store_id=1')
s,sale=call('/sales',SC,'POST',{'items':[{'productId':pid,'quantity':2},{'productId':milk,'quantity':3}],'discountType':'PERCENT','discountValue':5,'paymentMethod':'CARD'})
# subtotal 20 + 19.5 = 39.5; disc 1.975->1.98; taxable 37.52; tax 6.0032->6.00; total 43.52
chk('D','sale 201',s,201); chk('D','totals',(sale['subtotal'],sale['discount'],sale['tax'],sale['total']),(39.5,1.98,6.0,43.52))
chk('D','rows saved',(one('select count(*) from sale_items where sale_id=?',sale['saleId']),q('select payment_method,amount from payments where sale_id=?',sale['saleId']),one('select count(*) from receipts where sale_id=?',sale['saleId'])),(2,[('CARD',43.52)],1))
chk('D','stock reduced',(one('select quantity from products where product_id=?',pid),one('select quantity from products where product_id=1')),(10,st0-3))
chk('D','unit cost snapshot',q('select unit_cost from sale_items where sale_id=? and product_id=?',sale['saleId'],pid),[(6.5,)])
s,su=call('/sales/suspended',SC,'POST',{'items':[{'productId':pid,'quantity':1}]})
chk('D','suspend keeps stock',(s,one('select quantity from products where product_id=?',pid)),(201,10))
s,done=call('/sales',SC,'POST',{'saleId':su['saleId'],'items':[{'productId':pid,'quantity':1}],'paymentMethod':'CASH'})
chk('D','resume+complete keeps number',(s,done['invoiceNumber']),(201,su['invoiceNumber']))
s,su2=call('/sales/suspended',SC,'POST',{'items':[{'productId':pid,'quantity':1}]})
chk('D','cancel suspended',(call(f'/sales/suspended/{su2["saleId"]}',SC,'DELETE')[0],one('select status from sales where sale_id=?',su2['saleId'])),(200,'CANCELED'))
chk('D','insufficient stock 409',call('/sales',SC,'POST',{'items':[{'productId':pid,'quantity':999}],'paymentMethod':'CASH'})[0],409)
item=[i for i in sale['items'] if i['productId']==pid][0]
s,ret=call('/returns',SC,'POST',{'saleId':sale['saleId'],'saleItemId':item['saleItemId'],'quantity':1,'reason':'Broken'})
# refund = 43.52*20/39.5*1/2 = 11.02
chk('D','return refund',(s,ret['refundAmount'],ret['refundMethod']),(201,11.02,'CARD'))
chk('D','return restores stock',one('select quantity from products where product_id=?',pid),10)
chk('D','over-return refused',call('/returns',SC,'POST',{'saleId':sale['saleId'],'saleItemId':item['saleItemId'],'quantity':2,'reason':'x'})[0],400)
chk('D','lookup by QR',call('/sales/lookup?code='+sale['receiptCode'],SC)[1]['saleId'],sale['saleId'])
chk('D','invoice numbers per store',one("select count(*) from sales where store_id=1")-n_before,3)
# E) reports & profit
today=datetime.date.today(); f=today.replace(day=1); t=(f.replace(month=f.month%12+1,year=f.year+(f.month==12))-datetime.timedelta(days=1))
lo,hi=f'{f}T00:00:00',f'{t+datetime.timedelta(days=1)}T00:00:00'; per=f'from={f}&to={t}'
sm=call('/reports/summary?'+per,SA)[1]
gross=one("select coalesce(sum(total),0) from sales where store_id=1 and status='COMPLETED' and completed_at>=? and completed_at<?",lo,hi)
ref=one("select coalesce(sum(refund_amount),0) from returns where store_id=1 and return_date>=? and return_date<?",lo,hi)
units=one("select coalesce(sum(si.quantity),0) from sale_items si join sales s using(sale_id) where s.store_id=1 and s.status='COMPLETED' and s.completed_at>=? and s.completed_at<?",lo,hi)
runits=one("select coalesce(sum(quantity),0) from returns where store_id=1 and return_date>=? and return_date<?",lo,hi)
chk('E','net revenue',sm['netSales'],round(gross-ref,2)); chk('E','refunds',sm['refunds'],round(ref,2))
chk('E','invoice count',sm['invoiceCount'],one("select count(*) from sales where store_id=1 and status='COMPLETED' and completed_at>=? and completed_at<?",lo,hi))
chk('E','net units',sm['netUnitsSold'],units-runits)
# profit: only halva lines have cost (milk has none) -> PARTIAL. halva: sale 2 units rev = 20*37.52/39.5 = 18.997.. cost 13; + resumed sale 1 unit rev 10 cost 6.5; return 1 unit rev 9.4987 cost 6.5
rev_h = 20*(39.5-1.98)/39.5 + 10 - 20*(39.5-1.98)/39.5/2
chk('E','profit status',sm['profit']['status'],'PARTIAL'); chk('E','profit amount',sm['profit']['amount'],round(rev_h-13,2))
tp=call('/reports/top-products?'+per,SA)[1]; chk('E','top products total = net revenue',tp['totalSalesValue'],sm['netSales'])
chk('E','monthly = net',[m['netSales'] for m in call(f'/reports/monthly-sales?year={f.year}',SA)[1]['months']][f.month-1],sm['netSales'])
chk('E','daily sum = net',round(sum(d['netSales'] for d in call('/reports/daily-sales?'+per,SA)[1]),2),sm['netSales'])
# F) stock alerts
inv=call('/reports/inventory',SA)[1]
chk('F','out count = SQL',inv['outOfStock'],one('select count(*) from products where store_id=1 and quantity=0'))
chk('F','low count = SQL',inv['lowStock'],one('select count(*) from products where store_id=1 and quantity>0 and quantity<=min_stock'))
chk('F','list = low+out',len(inv['reorder']),inv['lowStock']+inv['outOfStock'])
chk('F','halva (10, min 5) not alert',any(r['productId']==pid for r in inv['reorder']),False)
call('/sales',SC,'POST',{'items':[{'productId':pid,'quantity':5}],'paymentMethod':'CASH'})
inv=call('/reports/inventory',SA)[1]; h=[r for r in inv['reorder'] if r['productId']==pid]
chk('F','after selling 5: halva LOW (5 <= 5)',h and (h[0]['quantity'],h[0]['stockStatus']),(5,'LOW_STOCK'))
call('/sales',SC,'POST',{'items':[{'productId':pid,'quantity':5}],'paymentMethod':'CASH'})
h=[r for r in call('/reports/inventory',SA)[1]['reorder'] if r['productId']==pid]
chk('F','sold out: OUT_OF_STOCK first',h and h[0]['stockStatus'],'OUT_OF_STOCK')
chk('F','neshan alerts unaffected',[r['name'] for r in call('/reports/inventory',NA)[1]['reorder']],[r[0] for r in q("select name from products where store_id=2 and quantity<=min_stock order by quantity=0 desc, cast(quantity as real)/nullif(min_stock,0), name")])
print(f"PASS {R['pass']}  FAIL {R['fail']}")
for x in FAILS: print('  ', x)
