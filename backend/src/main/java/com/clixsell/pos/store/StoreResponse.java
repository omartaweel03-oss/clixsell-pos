package com.clixsell.pos.store;

import com.clixsell.pos.common.AccountStatus;

import java.time.LocalDateTime;

/** Store data returned by the API, with how many admins and cashiers it has. */
public record StoreResponse(
        Long storeId,
        String name,
        String address,
        String phone,
        AccountStatus status,
        LocalDateTime createdAt,
        long adminCount,
        long cashierCount
) {
    public static StoreResponse from(Store s, long adminCount, long cashierCount) {
        return new StoreResponse(s.getStoreId(), s.getName(), s.getAddress(), s.getPhone(),
                s.getStatus(), s.getCreatedAt(), adminCount, cashierCount);
    }
}
