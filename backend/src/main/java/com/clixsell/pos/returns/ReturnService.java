package com.clixsell.pos.returns;

import com.clixsell.pos.auth.CurrentUser;
import com.clixsell.pos.common.NotFoundException;
import com.clixsell.pos.common.ValidationException;
import com.clixsell.pos.sale.Sale;
import com.clixsell.pos.sale.SaleItem;
import com.clixsell.pos.sale.SaleRepository;
import com.clixsell.pos.user.User;
import com.clixsell.pos.user.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    public ReturnService(ReturnRepository returns, SaleRepository sales, UserRepository users,
                         @Value("${clixsell.returns.max-days:14}") int maxDays) {
        this.returns = returns;
        this.sales = sales;
        this.users = users;
        this.maxDays = maxDays;
    }

    public ReturnResponse processReturn(CurrentUser user, ReturnRequest request) {
        long storeId = user.requireStoreId();
        if (request == null || request.saleId() == null || request.saleItemId() == null) {
            throw new ValidationException(Map.of("saleItemId", "Choose the product to return"));
        }
        Sale sale = sales.findBySaleIdAndStore_StoreId(request.saleId(), storeId)
                .orElseThrow(() -> new NotFoundException("SALE_NOT_FOUND", "Invoice " + request.saleId() + " was not found"));
        SaleItem item = sale.getItems().stream()
                .filter(i -> i.getSaleItemId().equals(request.saleItemId()))
                .findFirst()
                .orElseThrow(() -> new NotFoundException("SALE_ITEM_NOT_FOUND", "This product is not on the invoice"));
        User cashier = users.findById(user.userId()).orElseThrow();
        ReturnProduct saved = returns.save(ReturnProduct.process(sale, item, request.quantity(),
                request.reason(), cashier, LocalDateTime.now(), maxDays));
        return ReturnResponse.from(saved);
    }
}
