package com.clixsell.pos.sale;

/** Payment methods of the POS screen (report SR11, Figure 3.2). Only recorded, not processed. */
public enum PaymentMethod {
    CASH,
    CARD,
    WALLET,
    BANK_TRANSFER,
    /** Feature 9 (V7): recorded with its cheque (number, bank, amount, due date); managed by the Store Admin. */
    CHEQUE
}
