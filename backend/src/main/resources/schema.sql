-- ClixSell POS database schema (SQLite)
-- Based on the PRODUCTS and SUPPLIERS tables of the project ERD (report, Figure 3.13).
-- Runs on every start; IF NOT EXISTS keeps existing data.

CREATE TABLE IF NOT EXISTS suppliers (
    supplier_id      INTEGER PRIMARY KEY AUTOINCREMENT,
    name             TEXT    NOT NULL UNIQUE COLLATE NOCASE,
    phone            TEXT,
    whatsapp_number  TEXT,
    address          TEXT
);

CREATE TABLE IF NOT EXISTS products (
    product_id     INTEGER PRIMARY KEY AUTOINCREMENT,
    supplier_id    INTEGER NOT NULL REFERENCES suppliers (supplier_id),
    name           TEXT    NOT NULL,
    barcode        TEXT    NOT NULL UNIQUE,
    category       TEXT    NOT NULL,
    selling_price  REAL    NOT NULL CHECK (selling_price > 0),
    quantity       INTEGER NOT NULL CHECK (quantity >= 0),
    min_stock      INTEGER NOT NULL CHECK (min_stock >= 0),
    image          BLOB,
    image_name     TEXT,
    created_at     TEXT    NOT NULL,
    updated_at     TEXT    NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_products_supplier ON products (supplier_id);
