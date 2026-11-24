"""Settings regression check (roles, taxes, password, backups) on a disposable demo database. Run via run.sh."""
from safety import require_disposable_db  # stops unless this is the disposable test database
import json, sqlite3, sys, urllib.request, os, glob
A='http://localhost:8080/api'; DB=require_disposable_db(sys.argv[1]); db=sqlite3.connect(DB); db.isolation_level=None
R={'pass':0,'fail':0}; FAILS=[]
def call(p,t=None,m='GET',b=None):
    r=urllib.request.Request(A+p,method=m,data=json.dumps(b).encode() if b is not None else None,
        headers={'Content-Type':'application/json',**({'Authorization':'Bearer '+t} if t else {})})
    try:
        x=urllib.request.urlopen(r); body=x.read(); return x.status,(json.loads(body) if body else None)
    except urllib.error.HTTPError as e:
        body=e.read()
        try: return e.code,(json.loads(body) if body else None)
        except ValueError: return e.code,None
def chk(name,a,b):
    ok=a==b; R['pass' if ok else 'fail']+=1
    print(('PASS ' if ok else 'FAIL ')+name+('' if ok else f'  got {a!r} expected {b!r}'))
one=lambda s,*a: db.execute(s,a).fetchone()[0]
def login(u,p): return call('/auth/login',m='POST',b={'username':u,'password':p})
T={}
for u,p in [('sysadmin','Admin12345'),('shini.admin','Admin-Pass-1'),('neshan.admin','Admin-Pass-1'),
            ('shini.cashier','Cashier-Pass-1'),('neshan.cashier','Cashier-Pass-1')]:
    s,b=login(u,p); T[u]=b['token']
shini=one("select store_id from stores where name like 'Shini%'"); neshan=one("select store_id from stores where name like 'Neshan%'")

print('--- 1. Store settings: who may change them')
body={'address':'Al-Irsal St, Ramallah','phone':'+970 2 295 1234','taxRate':10,'maxDiscountPercent':15}
for u,exp in [('sysadmin',403),('shini.cashier',403),('neshan.cashier',403)]:
    chk(f'{u} PUT /store/settings -> {exp}', call('/store/settings',T[u],'PUT',body)[0], exp)
chk('anonymous PUT /store/settings -> 401', call('/store/settings',None,'PUT',body)[0], 401)
neshan_before=db.execute('select name,address,phone,tax_rate,max_discount_percent from stores where store_id=?',(neshan,)).fetchone()
old_sales=db.execute("select sale_id,tax_rate,tax,total,discount from sales where status='COMPLETED' order by sale_id").fetchall()
s,b=call('/store/settings',T['shini.admin'],'PUT',dict(body, storeId=neshan, name='Taken'))
chk('shini.admin PUT own settings -> 200 (storeId/name in body ignored)', (s,b['storeId'],b['name'],b['taxRate']), (200,shini,'Shini Supermarket',10.0))
chk('DB: Shini tax 10, max discount 15, phone saved', db.execute('select tax_rate,max_discount_percent,phone from stores where store_id=?',(shini,)).fetchone(), (10.0,15.0,'+970 2 295 1234'))
chk('DB: Neshan unchanged', db.execute('select name,address,phone,tax_rate,max_discount_percent from stores where store_id=?',(neshan,)).fetchone(), neshan_before)
s,b=call('/store/settings',T['shini.admin'],'PUT',{'address':None,'phone':'call me','taxRate':101,'maxDiscountPercent':-1})
chk('invalid values -> 400 with field errors', (s, sorted(b['fieldErrors'])), (400, ['maxDiscountPercent','phone','taxRate']))
chk('DB: unchanged after invalid', one('select tax_rate from stores where store_id=?',shini), 10.0)
chk('shini.cashier GET /store sees tax 10', call('/store',T['shini.cashier'])[1]['taxRate'], 10.0)
chk('neshan.cashier GET /store sees tax 16', call('/store',T['neshan.cashier'])[1]['taxRate'], 16.0)

print('--- 2. Sales after the change')
water=one("select product_id from products where store_id=? and name like 'Mineral Water%'",shini)
s,b=call('/sales',T['shini.cashier'],'POST',{'items':[{'productId':water,'quantity':2}],'paymentMethod':'CASH'})
chk('new Shini sale uses 10% (5.00 + 0.50 = 5.50)', (s,b['taxRate'],b['total']), (201,10.0,5.5))
chk('completed sales before the change keep tax/total/discount',
    db.execute("select sale_id,tax_rate,tax,total,discount from sales where status='COMPLETED' and sale_id<=? order by sale_id",(old_sales[-1][0],)).fetchall(), old_sales)
s,b=call('/sales',T['shini.cashier'],'POST',{'items':[{'productId':water,'quantity':4}],'paymentMethod':'CASH','discountType':'PERCENT','discountValue':20})
chk('discount 20% > new max 15% -> 400', s, 400)
yog=one("select product_id from products where store_id=? and name like 'Hamoudeh%'",neshan)
s,b=call('/sales',T['neshan.cashier'],'POST',{'items':[{'productId':yog,'quantity':1}],'paymentMethod':'CASH'})
chk('Neshan sale still 16%', (s,b['taxRate']), (201,16.0))

print('--- 3. Own password: admins yes, cashiers no')
for u,p in [('shini.cashier','Cashier-Pass-1'),('neshan.cashier','Cashier-Pass-1')]:
    h=one('select password_hash from users where username=?',u)
    chk(f'{u} PUT /auth/me/password -> 403', call('/auth/me/password',T[u],'PUT',{'currentPassword':p,'newPassword':'Cashier-New-9'})[0], 403)
    chk(f'{u}: password unchanged, still logged in', (one('select password_hash from users where username=?',u)==h, call('/auth/me',T[u])[0], login(u,p)[0]), (True,200,200))
other=login('neshan.admin','Admin-Pass-1')[1]['token']
s,b=call('/auth/me/password',T['neshan.admin'],'PUT',{'currentPassword':'Wrong-1234','newPassword':'Neshan-New-Pass-2'})
chk('wrong current password -> 400 (not 401)', (s,b['fieldErrors']), (400,{'currentPassword':'Current password is incorrect'}))
s,b=call('/auth/me/password',T['neshan.admin'],'PUT',{'currentPassword':'Admin-Pass-1','newPassword':'Neshan-New-Pass-2'})
chk('neshan.admin changes own password -> 204, empty body', (s,b), (204,None))
chk('this session kept, other session ended', (call('/auth/me',T['neshan.admin'])[0], call('/auth/me',other)[0]), (200,401))
chk('login: old 401, new 200', (login('neshan.admin','Admin-Pass-1')[0], login('neshan.admin','Neshan-New-Pass-2')[0]), (401,200))
chk('hash is BCrypt', one("select substr(password_hash,1,2) from users where username='neshan.admin'"), '$2')
chk('other users unaffected', (login('shini.admin','Admin-Pass-1')[0], call('/auth/me',T['shini.cashier'])[0]), (200,200))
ncash=one("select user_id from users where username='neshan.cashier'")
chk('neshan.admin resets own cashier password -> 200', call(f'/store/cashiers/{ncash}/password',T['neshan.admin'],'PUT',{'password':'Reset-By-Admin-1'})[0], 200)
chk('shini.admin cannot reset Neshan cashier -> 404', call(f'/store/cashiers/{ncash}/password',T['shini.admin'],'PUT',{'password':'Taken-Over-1'})[0], 404)
chk('neshan.cashier logs in with the reset password', login('neshan.cashier','Reset-By-Admin-1')[0], 200)
# put the demo passwords back for the browser test
T['neshan.admin']=login('neshan.admin','Neshan-New-Pass-2')[1]['token']
call('/auth/me/password',T['neshan.admin'],'PUT',{'currentPassword':'Neshan-New-Pass-2','newPassword':'Admin-Pass-1'})
call(f'/store/cashiers/{ncash}/password',T['neshan.admin'],'PUT',{'password':'Cashier-Pass-1'})

print('--- 4. Backups: System Admin only')
for u in ['shini.admin','neshan.admin','shini.cashier','neshan.cashier']:
    T[u]=login(u,'Admin-Pass-1' if 'admin' in u else 'Cashier-Pass-1')[1]['token']
    chk(f'{u} POST/GET backups -> 403', (call('/admin/backups',T[u],'POST')[0], call('/admin/backups',T[u])[0]), (403,403))
chk('anonymous -> 401', call('/admin/backups')[0], 401)
counts={t:one(f'select count(*) from {t}') for t in ['stores','users','products','sales','returns']}
s,b=call('/admin/backups',T['sysadmin'],'POST')
chk('sysadmin creates backup -> 201, integrity ok', (s,b['integrityCheck']), (201,'ok'))
chk('backup row counts = live database', b['rowCounts'], counts)
s,l=call('/admin/backups',T['sysadmin'])
path=os.path.join(l['folder'], b['fileName'])
chk('listed with size and date', (l['backups'][0]['fileName'], l['backups'][0]['sizeBytes']>0, l['backups'][0]['createdAt'] is not None), (b['fileName'],True,True))
chk('file on disk, owner-only permissions', (os.path.isfile(path), oct(os.stat(path).st_mode & 0o777)), (True,'0o600'))
bk=sqlite3.connect(f'file:{path}?mode=ro',uri=True)
chk('backup opens: integrity_check ok, same products/sales', (bk.execute('pragma integrity_check').fetchone()[0], bk.execute('select count(*) from products').fetchone()[0], bk.execute('select count(*) from sales').fetchone()[0]), ('ok',counts['products'],counts['sales']))
chk('live database unchanged by backup', {t:one(f'select count(*) from {t}') for t in counts}, counts)
for p in ['/admin/backups/'+b['fileName'], '/admin/backups/..%2Fclixsell.db']:
    chk(f'no file access via {p[:40]}', call(p,T['sysadmin'])[0] in (400,404,405), True)
print(f"\nSETTINGS CHECK PASS {R['pass']} FAIL {R['fail']}")
