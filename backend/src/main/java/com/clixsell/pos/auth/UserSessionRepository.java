package com.clixsell.pos.auth;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.Optional;

/** Database access for login sessions. */
public interface UserSessionRepository extends JpaRepository<UserSession, Long> {

    Optional<UserSession> findByTokenHash(String tokenHash);

    /** Logs a user out everywhere (deactivation, password reset). */
    void deleteByUser_UserId(Long userId);

    /** Logs a user out everywhere except this session (own password changed). */
    void deleteByUser_UserIdAndTokenHashNot(Long userId, String tokenHash);

    /** Logs out every user of a store (store deactivated). */
    void deleteByUser_Store_StoreId(Long storeId);

    void deleteByExpiresAtBefore(LocalDateTime time);
}
