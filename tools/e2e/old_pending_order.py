"""Writes ONE past purchase order (PENDING_APPROVAL, created N days ago) into the DISPOSABLE test
database, like an order made days before the test — the only way to have an overdue order now.
The database comes from the safety gate (never a user database); every V6 trigger still applies.
Usage: python3 old_pending_order.py <supplierId> <storeAdminUsername> <productId> <orderNumber> <daysAgo>"""
import datetime
import sqlite3
import sys
import uuid

from safety import require_disposable_db

db = require_disposable_db()
supplier, admin_name, product, number, days = int(sys.argv[1]), sys.argv[2], int(sys.argv[3]), sys.argv[4], int(sys.argv[5])
c = sqlite3.connect(db, timeout=30)
admin = c.execute("SELECT user_id FROM users WHERE username = ? AND role = 'STORE_ADMIN'", (admin_name,)).fetchone()[0]
store = c.execute('SELECT store_id FROM suppliers WHERE supplier_id = ?', (supplier,)).fetchone()[0]
name = c.execute('SELECT name FROM suppliers WHERE supplier_id = ?', (supplier,)).fetchone()[0]
at = (datetime.datetime.now() - datetime.timedelta(days=days)).strftime('%Y-%m-%dT%H:%M:%S')
with c:
    c.execute("""INSERT INTO purchase_orders (store_id, order_number, supplier_id, supplier_name, status, source, created_by,
                 created_at, client_request_id, request_hash) VALUES (?, ?, ?, ?, 'PENDING_APPROVAL', 'MANUAL', ?, ?, ?, ?)""",
              (store, number, supplier, name, admin, at, str(uuid.uuid4()), 'a' * 64))
    order = c.execute('SELECT order_id FROM purchase_orders WHERE store_id = ? AND order_number = ?', (store, number)).fetchone()[0]
    c.execute("""INSERT INTO purchase_order_items (order_id, store_id, product_id, product_store_id, product_name, barcode,
                 quantity, basis, calc_json, open_product_id)
                 SELECT ?, store_id, product_id, store_id, name, barcode, 2, 'MANUAL', '{}', product_id
                 FROM products WHERE product_id = ? AND store_id = ?""", (order, product, store))
print(order)
