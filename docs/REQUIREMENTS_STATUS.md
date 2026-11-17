# ClixSell – Requirements status (report Tables 3.2–3.6, Figures 3.1–3.10)

Updated 2026-11-17 (`main` = 09be153). **Done** = implemented and tested · **Partial** · **Missing**.

## Screens (Figures, report pages 30–39)
| Figure | Screen | Status | Notes |
|---|---|---|---|
| 3.1 | Guardian Mode | Missing | needs Purchase Orders + alerts (approval needed, database change) |
| 3.2 | POS | Done | camera scan (SR4) and AI (SR14) not yet |
| 3.3 | Inventory / Products | Partial | Import / Export missing |
| 3.4 | Product Details | Partial | no separate details view; table + edit form |
| 3.5 | Stock Alerts | Done | "Create purchase request" disabled with reason until Purchase Orders exist |
| 3.6 | Suspended Invoices | Done | "Customer" column = Cashier (decision A) |
| 3.7 | Sales Analytics | Done | |
| 3.8 | Reports | Done | profit with sale-time cost |
| 3.9 | Settings | Done | 8 cards; Printer and AI Mobile "Not available yet" (no printing / AI yet) |
| 3.10 | AI Product Identification (mobile) | Missing | the only dedicated mobile screen |

## Cashier (Table 3.2)
| ID | Requirement | Status |
|---|---|---|
| UR-C1 | Log in | Done |
| UR-C2 | Start a new sale | Done |
| UR-C3 | Scan barcodes | Partial – USB / keyboard scanner works; camera missing |
| UR-C4 | Search products | Done |
| UR-C5 | Add products to the invoice | Done |
| UR-C6 | Remove one item | Done |
| UR-C7 | Change quantities | Done |
| UR-C8 | Suspend and resume | Done |
| UR-C9 | Different payment methods | Done (cash, card, e-wallet, bank transfer) |
| UR-C10 | QR digital receipt | Done (on screen) |
| UR-C11 | Return a product | Done |
| UR-C12 | Identify products by camera | Missing |

## Manager (Table 3.3)
| ID | Requirement | Status |
|---|---|---|
| UR-M1 | Log in | Done |
| UR-M2–M4 | Add / edit / delete products | Done |
| UR-M5 | Product details | Partial (no details view) |
| UR-M6 | Manage inventory quantities | Partial (by editing; no adjustment history) |
| UR-M7 | Sales reports | Done |
| UR-M8 | Inventory reports | Done |
| UR-M9 | Low-stock alerts | Done |
| UR-M10 | Manage suspended invoices | Done |
| UR-M11 | Import / export products | Missing |
| UR-M12 | System settings | Done (Settings screen) |
| UR-M13 | Review AI purchase recommendations | Missing |
| UR-M14 | Approve / reject purchase orders | Missing |
| UR-M15 | Send approved orders via WhatsApp | Missing |
| UR-M16 | Activate / deactivate Guardian Mode | Missing |
| UR-M17 | Monitor AI alerts and business risks | Missing |

## Customer (Table 3.4)
| ID | Requirement | Status |
|---|---|---|
| UR-U1 | Receive a digital receipt | Partial – QR shown on the cashier screen, not sent to the customer |
| UR-U2 | Request a return through the cashier | Done |

## System requirements (Table 3.5)
| SR | Status | Notes |
|---|---|---|
| SR1 authenticate (username/email + password) | Partial | username only |
| SR2 role of the logged-in user | Done | |
| SR3 create sale record | Partial (approved) | created when first saved (suspend / complete) |
| SR4 camera barcode scanning | Missing | |
| SR5–SR11 search, add, remove, quantity, suspend, totals, payments | Done | |
| SR12 QR receipt | Done | |
| SR13 returns update stock | Done | |
| SR14 image recognition | Missing | |
| SR15–SR17 add / edit / delete products | Done | |
| SR18 complete product details | Partial | |
| SR19 stock after sales, returns, adjustments | Partial | adjustments by editing, no history |
| SR20 sales reports and analytics | Done | |
| SR21 inventory reports | Done | |
| SR22 low-stock alerts | Done | |
| SR23 view / resume / delete suspended | Done | |
| SR24 import / export product files | Missing | |
| SR25 settings, taxes, language, users, backups | Done | backups by System Admin; restore manual |
| SR26–SR29 purchase recommendations, pending, WhatsApp, send after approval | Missing | |
| SR30–SR31 Guardian Mode, AI alerts | Missing | |
| SR32–SR33 offline storage + sync | Missing | |

## Non-functional (Table 3.6)
| Quality | Status |
|---|---|
| Security (login, role-based access) | Done (+ store isolation, lockout) |
| Data integrity | Done (transactions, stock checks, migrations with backup) |
| Maintainability (modules) | Done |
| Scalability (more branches) | Done (multi-store) |
| Usability, Performance | Done for current screens (not formally measured) |
| Availability / Reliability / Synchronization (offline) | Missing |
| Portability (Android) | Partial – web / desktop run; Android platform not created yet |
