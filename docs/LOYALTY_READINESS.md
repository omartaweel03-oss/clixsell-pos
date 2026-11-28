# Loyalty readiness (requirement 8)

**Status: readiness only. No loyalty program is implemented.** Nothing in the app gives, shows or
redeems points, and no customer is identified at the POS. This page records what is ready and what a
future, separately approved loyalty phase would add.

## What is ready now

| Ready | Where | Why it matters for loyalty |
|---|---|---|
| `SaleCompletedEvent` | `backend/.../sale/SaleCompletedEvent.java`, published by `SaleService.complete` | One event per completed sale (never for a suspension or a refused sale), with `storeId`, `saleId`, `cashierId`, `paymentMethod`, `totalMinor` and `paidMinor` (agorot). Test: `LoyaltyReadinessTest`. |
| Exact money in agorot | `sales.total` + `sales.rounding_minor`, `returns.refund_minor` + `returns.rounding_minor` (V7) | Points can be computed from exact integers. Cash rounding is kept apart, so it can be excluded. |
| Returns linked to their sale and line | `returns.sale_id`, `returns.sale_item_id` | Points given for a sale can be taken back proportionally when it is returned. |
| Store isolation | every table has `store_id`; services take the store from the session | Each store's program stays separate. |
| Append-only financial history | V7 triggers (closed shifts, payments, returns, cheque events) | Points can be audited against sales that cannot be changed later. |

## Rules a loyalty module must follow

1. **Listen after commit only.** Use `@TransactionalEventListener(phase = AFTER_COMMIT)`, so points are
   given only for sales that were really saved. A failure in loyalty must never undo a sale.
2. **Make awards idempotent.** Use one award per `saleId`, with a UNIQUE constraint, so a retried event
   never gives points twice.
3. **Choose the base amount.** `totalMinor` (invoice total, tax included, no cash rounding) is the
   recommended base, not `paidMinor`.
4. **Handle returns.** Reverse points proportionally from `returns.refund_minor - returns.rounding_minor`,
   and never let a balance go below zero without an explicit, approved rule.
5. **Cheque sales.** Award points only once the cheque is CLEARED (listen to cheque events), or award
   them provisionally and reverse them if it BOUNCES. This is a management decision.
6. **Respect store isolation.** Customers, balances and awards carry `store_id`, using composite keys as
   the V6/V7 tables do.

## What a loyalty phase would add (needs its own schema approval, e.g. V8)

* A `customers` table (per store: name, phone, consent) and an optional `sales.customer_id`.
* `loyalty_accounts` and `loyalty_transactions` (append-only: EARN / REVERSE / REDEEM / ADJUST, with
  UNIQUE (sale_id, type) for earn and reverse).
* POS: attach a customer to the invoice (phone search or card scan), and show the balance.
* Redemption as a payment or a discount. This changes the payment rules: one payment per sale is
  enforced today, so redemption needs an approved design first.
* Reports: points issued, redeemed and outstanding (a liability).

None of these tables or screens exist today, and none were created in this phase (no V8).
