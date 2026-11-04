# ClixSell POS – Saved for later (Future work)

Features from the report that were **postponed** during the Cashier Dashboard (POS) phase.
They are not part of Figure 3.2 and are not implemented yet. Nothing in the current code
pretends to do them (no dead buttons).

| # | Feature | Notes for when we start |
|---|---|---|
| 1 | **Camera barcode scanning** | Today the cashier types or uses a USB/Bluetooth scanner (it types the code + Enter into the POS search box, which already adds the product). Next: a camera button in the POS search box that scans and calls the same "add by barcode" code. |
| 2 | **AI product identification** | Identify a product from a camera photo and add it to the invoice. Needs a model / service choice and product images for training or matching. |
| 3 | **Offline mode (work without internet)** | Keep products and pending sales on the device, then sync to the backend when back online. Needs rules for stock conflicts and invoice numbers created offline. |
| 4 | **Receipt printing** | Today the receipt is shown on screen with a QR code (used for returns). Next: print to a receipt printer / PDF using the same receipt data. |

Before starting any of these: read the report again, write the plan, and wait for "START".
