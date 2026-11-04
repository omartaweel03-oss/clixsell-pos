package com.clixsell.pos.store;

import com.clixsell.pos.auth.UserSessionRepository;
import com.clixsell.pos.common.AccountStatus;
import com.clixsell.pos.common.ConflictException;
import com.clixsell.pos.common.NotFoundException;
import com.clixsell.pos.common.ValidationException;
import com.clixsell.pos.user.Role;
import com.clixsell.pos.user.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/** Store management, used by the System Admin (and by store users to read their own store). */
@Service
@Transactional
public class StoreService {

    private final StoreRepository stores;
    private final UserRepository users;
    private final UserSessionRepository sessions;

    public StoreService(StoreRepository stores, UserRepository users, UserSessionRepository sessions) {
        this.stores = stores;
        this.users = users;
        this.sessions = sessions;
    }

    @Transactional(readOnly = true)
    public List<StoreResponse> listStores() {
        return stores.findAllByOrderByStoreIdAsc().stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public StoreResponse getStore(long storeId) {
        return toResponse(find(storeId));
    }

    public StoreResponse createStore(StoreRequest request) {
        Store.validate(request);
        if (stores.existsByNameIgnoreCase(request.name().trim())) {
            throw duplicateName();
        }
        return toResponse(stores.save(Store.create(request)));
    }

    public StoreResponse updateStore(long storeId, StoreRequest request) {
        Store store = find(storeId);
        Store.validate(request);
        if (stores.existsByNameIgnoreCaseAndStoreIdNot(request.name().trim(), storeId)) {
            throw duplicateName();
        }
        store.updateDetails(request);
        return toResponse(stores.save(store));
    }

    /** Deactivating a store logs out all its users at once and blocks their logins. */
    public StoreResponse setStatus(long storeId, AccountStatus status) {
        if (status == null) {
            throw new ValidationException(Map.of("status", "Status must be ACTIVE or INACTIVE"));
        }
        Store store = find(storeId);
        store.setStatus(status);
        if (status == AccountStatus.INACTIVE) {
            sessions.deleteByUser_Store_StoreId(storeId);
        }
        return toResponse(stores.save(store));
    }

    Store find(long storeId) {
        return stores.findById(storeId)
                .orElseThrow(() -> new NotFoundException("STORE_NOT_FOUND", "Store " + storeId + " was not found"));
    }

    private StoreResponse toResponse(Store s) {
        return StoreResponse.from(s,
                users.countByStore_StoreIdAndRole(s.getStoreId(), Role.STORE_ADMIN),
                users.countByStore_StoreIdAndRole(s.getStoreId(), Role.CASHIER));
    }

    private static ConflictException duplicateName() {
        return new ConflictException("DUPLICATE_STORE_NAME", "name", "A store with this name already exists");
    }
}
