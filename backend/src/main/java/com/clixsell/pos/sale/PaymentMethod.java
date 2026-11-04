package com.clixsell.pos.sale;

/** Payment methods of the POS screen (report SR11, Figure 3.2). Only recorded, not processed. */
public enum PaymentMethod {
    CASH,
    CARD,
    WALLET,
    BANK_TRANSFER
}
