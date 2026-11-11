package com.clixsell.pos.demo;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** With the "demo" profile a new database gets the demo stores, staff, products, sales and a return. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:sqlite:target/demo-clixsell.db?foreign_keys=on")
@ActiveProfiles("demo")
class DemoDataTest {

    static {
        try {
            Files.deleteIfExists(Path.of("target/demo-clixsell.db"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Autowired
    JdbcTemplate db;

    private int count(String sql) {
        return db.queryForObject(sql, Integer.class);
    }

    @Test
    void demoDataIsAddedWithTheBusinessRules() {
        assertThat(db.queryForList("select name from stores order by store_id", String.class))
                .containsExactly("Shini Supermarket", "Neshan Market");
        assertThat(count("select count(*) from users where role = 'STORE_ADMIN'")).isEqualTo(2);
        assertThat(count("select count(*) from users where role = 'CASHIER'")).isEqualTo(3);
        assertThat(count("select count(*) from products where store_id = 1")).isEqualTo(10);
        assertThat(count("select count(*) from products where store_id = 2")).isEqualTo(10);
        assertThat(db.queryForObject("select password_hash from users where username = 'shini.cashier'",
                String.class)).startsWith("$2");

        assertThat(count("select count(*) from sales where status = 'COMPLETED'")).isEqualTo(5);
        assertThat(count("select count(*) from sales where status = 'SUSPENDED'")).isEqualTo(2);
        assertThat(count("select count(*) from receipts")).isEqualTo(5);
        assertThat(count("select count(*) from returns")).isEqualTo(1);
        // each store numbers its own invoices
        assertThat(db.queryForList("select invoice_number from sales where store_id = 2 order by sale_id",
                String.class)).containsExactly("INV-000001", "INV-000002", "INV-000003");

        // milk: 40 − 2 sold + 1 returned = 39 (the suspended invoice reserves nothing)
        assertThat(count("select quantity from products where barcode = '6251000000011'")).isEqualTo(39);
        // first sale: 2×6.50 + 5.00 + 35.00 = 53.00, tax 16% = 8.48, total 61.48
        assertThat(db.queryForObject("select total from sales where store_id = 1 and invoice_number = 'INV-000001'",
                Double.class)).isEqualTo(61.48);
    }
}
