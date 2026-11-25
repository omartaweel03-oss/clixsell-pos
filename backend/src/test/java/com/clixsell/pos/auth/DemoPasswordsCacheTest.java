package com.clixsell.pos.auth;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** The detection cache: answers repeat checks without BCrypt, stays bounded, never goes stale. */
class DemoPasswordsCacheTest {

    /** Counts the BCrypt comparisons. */
    private static final class Counting implements PasswordEncoder {
        final BCryptPasswordEncoder real = new BCryptPasswordEncoder(4);
        final AtomicInteger matches = new AtomicInteger();

        @Override
        public String encode(CharSequence raw) {
            return real.encode(raw);
        }

        @Override
        public boolean matches(CharSequence raw, String encoded) {
            matches.incrementAndGet();
            return real.matches(raw, encoded);
        }
    }

    @Test
    void anUnchangedPasswordIsCheckedOnlyOnceAndANewHashIsCheckedAgain() {
        Counting enc = new Counting();
        DemoPasswords demo = new DemoPasswords(enc, false);
        String legacy = enc.encode("Cashier-Pass-1");
        assertThat(demo.hashUsesDemoPassword(legacy)).isTrue();
        int afterFirst = enc.matches.get();
        assertThat(demo.hashUsesDemoPassword(legacy)).isTrue();
        assertThat(enc.matches.get()).as("second check from the cache").isEqualTo(afterFirst);
        String reset = enc.encode("Till-Cashier-Pass-4"); // a reset gives a new hash: never a stale answer
        assertThat(demo.hashUsesDemoPassword(reset)).isFalse();
        assertThat(enc.matches.get() - afterFirst).isEqualTo(DemoPasswords.PUBLISHED_SPELLINGS.size());
    }

    @Test
    void theCacheIsBounded() {
        Counting enc = new Counting();
        DemoPasswords demo = new DemoPasswords(enc, false);
        // fake (non-BCrypt) hashes: rejected by the encoder at once, but still remembered
        for (int i = 0; i < DemoPasswords.CACHE_LIMIT + 500; i++) demo.hashUsesDemoPassword("$2a$04$" + i);
        assertThat(demo.cachedHashes()).isLessThanOrEqualTo(DemoPasswords.CACHE_LIMIT);
    }
}
