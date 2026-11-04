-- V3: sales (Cashier Dashboard / POS).
-- Tables from the project ERD (report, Figure 3.13): SALES, SALE_ITEMS, PAYMENTS,
-- RECEIPTS, RETURNS. Added for the multi-store requirement: store_id on sales and
-- returns, so every store's sales stay separate. Existing data is not changed.

-- Per-store POS settings (edited by the System Admin on the store).
ALTER TABLE stores ADD COLUMN tax_rate REAL NOT NULL DEFAULT 16
    CHECK (tax_rate >= 0 AND tax_rate <= 100);
ALTER TABLE stores ADD COLUMN max_discount_percent REAL NOT NULL DEFAULT 10
    CHECK (max_discount_percent >= 0 AND max_discount_percent <= 100);
-- Invoice numbers count separately per store: INV-000001, INV-000002, ...
ALTER TABLE stores ADD COLUMN next_invoice_number INTEGER NOT NULL DEFAULT 1;

CREATE TABLE sales (
    sale_id         INTEGER PRIMARY KEY AUTOINCREMENT,
    store_id        INTEGER NOT NULL REFERENCES stores (store_id),
    cashier_id      INTEGER NOT NULL REFERENCES users (user_id),
    invoice_number  TEXT    NOT NULL,
    status          TEXT    NOT NULL CHECK (status IN ('SUSPENDED', 'COMPLETED', 'CANCELED')),
    subtotal        REAL    NOT NULL,
    discount        REAL    NOT NULL DEFAULT 0,
    tax_rate        REAL    NOT NULL,
    tax             REAL    NOT NULL,
    total           REAL    NOT NULL,
    created_at      TEXT    NOT NULL,
    updated_at      TEXT    NOT NULL,
    completed_at    TEXT,
    UNIQUE (store_id, invoice_number)
);
CREATE INDEX idx_sales_store_status ON sales (store_id, status);

-- product_name / barcode are copied so receipts stay correct if a product is
-- later renamed or deleted (then product_id becomes NULL).
CREATE TABLE sale_items (
    sale_item_id       INTEGER PRIMARY KEY AUTOINCREMENT,
    sale_id            INTEGER NOT NULL REFERENCES sales (sale_id) ON DELETE CASCADE,
    product_id         INTEGER REFERENCES products (product_id) ON DELETE SET NULL,
    product_name       TEXT    NOT NULL,
    barcode            TEXT,
    quantity           INTEGER NOT NULL CHECK (quantity > 0),
    unit_price         REAL    NOT NULL,
    line_total         REAL    NOT NULL,
    returned_quantity  INTEGER NOT NULL DEFAULT 0 CHECK (returned_quantity >= 0 AND returned_quantity <= quantity)
);
CREATE INDEX idx_sale_items_sale ON sale_items (sale_id);

CREATE TABLE payments (
    payment_id      INTEGER PRIMARY KEY AUTOINCREMENT,
    sale_id         INTEGER NOT NULL REFERENCES sales (sale_id) ON DELETE CASCADE,
    payment_method  TEXT    NOT NULL CHECK (payment_method IN ('CASH', 'CARD', 'WALLET', 'BANK_TRANSFER')),
    amount          REAL    NOT NULL,
    payment_status  TEXT    NOT NULL CHECK (payment_status IN ('PAID')),
    paid_at         TEXT    NOT NULL
);
CREATE INDEX idx_payments_sale ON payments (sale_id);

CREATE TABLE receipts (
    receipt_id     INTEGER PRIMARY KEY AUTOINCREMENT,
    sale_id        INTEGER NOT NULL UNIQUE REFERENCES sales (sale_id) ON DELETE CASCADE,
    qr_code_value  TEXT    NOT NULL UNIQUE,
    receipt_url    TEXT,
    issued_at      TEXT    NOT NULL
);

CREATE TABLE returns (
    return_id      INTEGER PRIMARY KEY AUTOINCREMENT,
    store_id       INTEGER NOT NULL REFERENCES stores (store_id),
    sale_id        INTEGER NOT NULL REFERENCES sales (sale_id),
    sale_item_id   INTEGER NOT NULL REFERENCES sale_items (sale_item_id),
    product_id     INTEGER REFERENCES products (product_id) ON DELETE SET NULL,
    cashier_id     INTEGER NOT NULL REFERENCES users (user_id),
    quantity       INTEGER NOT NULL CHECK (quantity > 0),
    reason         TEXT    NOT NULL,
    refund_amount  REAL    NOT NULL,
    refund_method  TEXT    NOT NULL,
    return_date    TEXT    NOT NULL
);
CREATE INDEX idx_returns_sale ON returns (sale_id);
