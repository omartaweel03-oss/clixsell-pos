package com.clixsell.pos.user;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** Database access for users. Store users are always looked up together with their store. */
public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByUsernameIgnoreCase(String username);

    boolean existsByUsernameIgnoreCase(String username);

    boolean existsByRole(Role role);

    List<User> findAllByStore_StoreIdAndRoleOrderByUserIdAsc(Long storeId, Role role);

    Optional<User> findByUserIdAndStore_StoreIdAndRole(Long userId, Long storeId, Role role);

    long countByStore_StoreIdAndRole(Long storeId, Role role);

    List<User> findAllByStore_StoreId(Long storeId);
}
