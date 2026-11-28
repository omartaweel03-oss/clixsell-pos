package com.clixsell.pos.returns;

import com.clixsell.pos.auth.CurrentUser;
import com.clixsell.pos.common.ConflictException;
import com.clixsell.pos.common.NotFoundException;
import com.clixsell.pos.common.ValidationException;
import com.clixsell.pos.sale.PaymentMethod;
import com.clixsell.pos.sale.Sale;
import com.clixsell.pos.cheque.ChequeService;
import com.clixsell.pos.shift.ShiftService;
import com.clixsell.pos.sale.SaleItem;
import com.clixsell.pos.sale.SaleRepository;
import com.clixsell.pos.user.User;
import com.clixsell.pos.user.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * Product returns (SR13, Figure 3.15) in one transaction: validate, refund,
 * put the stock back, save the return. Only sales of the user's own store.
 */
@Service
@Transactional
public class ReturnService {

    private final ReturnRepository returns;
    private final SaleRepository sales;
    private final UserRepository users;
    private final int maxDays;
    private final ShiftService shifts;
    private final ChequeService cheques;

    public ReturnService(ReturnRepository returns, SaleRepository sales, UserRepository users,
                         @Value("${clixsell.returns.max-days:14}") int maxDays, ShiftService shifts,
                         ChequeService cheques) {
        this.returns = returns;
        this.sales = sales;
        this.users = users;
        this.maxDays = maxDays;
        this.shifts = shifts;
        this.cheques = cheques;
    }

    private static final java.util.regex.Pattern REQUEST_KEY = java.util.regex.Pattern.compile("[A-Za-z0-9_-]{8,100}");

    public ReturnResponse processReturn(CurrentUser user, ReturnRequest request) {
        long storeId = user.requireStoreId();
        if (request == null || request.saleId() == null || request.saleItemId() == null) {
            throw new ValidationException(Map.of("saleItemId", "Choose the product to return"));
        }
        String key = request.requestKey();
        if (key != null) {
            if (!REQUEST_KEY.matcher(key).matches()) {
                throw new ValidationException(Map.of("requestKey", "A request key has 8–100 letters, digits, - or _"));
            }
            java.util.Optional<ReturnProduct> again = returns.findByStore_StoreIdAndRequestKey(storeId, key);
            if (again.isPresent()) {
                ReturnProduct r = again.get();
                if (!r.getSale().getSaleId().equals(request.saleId()) || !r.getSaleItem().getSaleItemId().equals(request.saleItemId())
                        || !java.util.Objects.equals(r.getQuantity(), request.quantity())) {
                    throw new ConflictException("REQUEST_KEY_USED", "requestKey", "This request key was used for another return");
                }
                return ReturnResponse.from(r); // the same request again: nothing new is refunded
            }
        }
        Sale sale = sales.findBySaleIdAndStore_StoreId(request.saleId(), storeId)
                .orElseThrow(() -> new NotFoundException("SALE_NOT_FOUND", "Invoice " + request.saleId() + " was not found"));
        SaleItem item = sale.getItems().stream()
                .filter(i -> i.getSaleItemId().equals(request.saleItemId()))
                .findFirst()
                .orElseThrow(() -> new NotFoundException("SALE_ITEM_NOT_FOUND", "This product is not on the invoice"));
        User cashier = users.findById(user.userId()).orElseThrow();
        // the refund goes into the open shift of the user who makes it (a cash refund needs one when
        // the store requires shifts); an old sale's closed shift is never touched
        boolean chequeSale = sale.getPaymentMethod() == PaymentMethod.CHEQUE;
        if (chequeSale) cheques.requireReturnAllowed(user, sale.getSaleId()); // D6, before any change
        boolean cash = sale.getPaymentMethod() == PaymentMethod.CASH;
        // approved policy: a cash refund needs the user's open drawer (lock taken here, same transaction)
        // (an explicit if: a conditional expression would unbox shiftForTill's null)
        Long shiftId;
        if (cash) {
            shiftId = shifts.shiftForCashRefund(user);
        } else {
            shiftId = shifts.shiftForTill(user, false);
        }
        BigDecimal forLine = BigDecimal.valueOf(returns.refundedForLine(item.getSaleItemId())).setScale(2, RoundingMode.HALF_UP);
        BigDecimal forSale = BigDecimal.valueOf(returns.refundedForSale(sale.getSaleId())).setScale(2, RoundingMode.HALF_UP);
        int q = request.quantity() == null ? 0 : request.quantity();
        boolean lastReturn = sale.getItems().stream()
                .allMatch(i -> i == item ? i.getReturnableQuantity() == q : i.getReturnableQuantity() == 0);
        ReturnProduct created = ReturnProduct.process(sale, item, request.quantity(),
                request.reason(), cashier, LocalDateTime.now(), maxDays, forLine, forSale, shiftId,
                sale.getStore().getCashRoundingMinor(), lastReturn);
        if (cash) {
            long available = shifts.drawerCashMinor(shiftId);
            if (created.getRefundMinor() > available) {
                // nothing is saved: the transaction rolls back (stock and the line's returned count too)
                throw new DrawerInsufficientException(available, created.getRefundMinor());
            }
        }
        created.setRequestKey(key);
        ReturnProduct saved = returns.save(created);
        if (chequeSale) {
            // D6: never automatic cash — what is owed back waits for the Store Admin's decision
            returns.flush();
            cheques.owe(storeId, sale.getSaleId(), saved.getReturnId(), saved.getRefundMinor());
        }
        return ReturnResponse.from(saved);
    }
}
