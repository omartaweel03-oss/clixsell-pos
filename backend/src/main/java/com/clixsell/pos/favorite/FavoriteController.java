package com.clixsell.pos.favorite;

import com.clixsell.pos.auth.CurrentUser;
import com.clixsell.pos.common.NotFoundException;
import com.clixsell.pos.common.ValidationException;
import com.clixsell.pos.user.Role;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * POS favorites (feature 5): an ordered list of products per store, chosen by the Store Admin and
 * shown first on every cashier's POS screen.
 *
 * <pre>
 * GET /api/favorites   store users   the store's favorites, in order
 * PUT /api/favorites   Store Admin   {productIds: [...]} — the new complete list, in order
 * </pre>
 * The whole list is replaced in ONE transaction (delete, then insert at positions 0..n-1): the
 * database's UNIQUE (store_id, position) keeps the order total, and a failed save changes nothing.
 */
@RestController
public class FavoriteController {

    public static final int MAX_FAVORITES = 40;

    public record Favorite(long productId, String name, int position) {
    }

    public record FavoritesRequest(List<Long> productIds) {
    }

    private final FavoriteService service;

    public FavoriteController(FavoriteService service) {
        this.service = service;
    }

    @GetMapping("/api/favorites")
    public List<Favorite> list(@AuthenticationPrincipal CurrentUser user) {
        return service.list(user.requireStoreId());
    }

    @PutMapping("/api/favorites")
    public List<Favorite> replace(@AuthenticationPrincipal CurrentUser user, @RequestBody FavoritesRequest request) {
        return service.replace(user, request == null ? null : request.productIds());
    }

    @Service
    @Transactional
    public static class FavoriteService {

        private final JdbcTemplate db;

        public FavoriteService(JdbcTemplate db) {
            this.db = db;
        }

        @Transactional(readOnly = true)
        public List<Favorite> list(long storeId) {
            return db.query("""
                    SELECT f.product_id, p.name, f.position FROM product_favorites f
                    JOIN products p ON p.product_id = f.product_id
                    WHERE f.store_id = ? ORDER BY f.position""",
                    (rs, i) -> new Favorite(rs.getLong(1), rs.getString(2), rs.getInt(3)), storeId);
        }

        public List<Favorite> replace(CurrentUser user, List<Long> productIds) {
            if (user.role() != Role.STORE_ADMIN) throw new AccessDeniedException("Store Admin only");
            long storeId = user.requireStoreId();
            if (productIds == null) throw new ValidationException(Map.of("productIds", "Send the list of favorite products"));
            if (productIds.size() > MAX_FAVORITES) {
                throw new ValidationException(Map.of("productIds", "At most " + MAX_FAVORITES + " favorites"));
            }
            Set<Long> seen = new HashSet<>();
            for (Long id : productIds) {
                if (id == null || !seen.add(id)) {
                    throw new ValidationException(Map.of("productIds", "Each product may appear only once"));
                }
                Integer n = db.queryForObject("SELECT COUNT(*) FROM products WHERE product_id = ? AND store_id = ?",
                        Integer.class, id, storeId);
                if (n == null || n == 0) {
                    throw new NotFoundException("PRODUCT_NOT_FOUND", "Product " + id + " is not available in this store");
                }
            }
            String now = LocalDateTime.now().withNano(0).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            db.update("DELETE FROM product_favorites WHERE store_id = ?", storeId);
            for (int i = 0; i < productIds.size(); i++) {
                db.update("INSERT INTO product_favorites (store_id, product_id, position, created_by, created_at) "
                        + "VALUES (?, ?, ?, ?, ?)", storeId, productIds.get(i), i, user.userId(), now);
            }
            return list(storeId);
        }
    }
}
