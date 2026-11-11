package com.clixsell.pos.demo;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** With the normal settings (no "demo" profile) a new database stays empty. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:sqlite:target/no-demo-clixsell.db?foreign_keys=on")
class DemoDataDisabledByDefaultTest {

    static {
        try {
            Files.deleteIfExists(Path.of("target/no-demo-clixsell.db"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Autowired
    JdbcTemplate db;

    @Test
    void newDatabaseGetsNoDemoData() {
        assertThat(db.queryForObject("select count(*) from stores", Integer.class)).isZero();
        assertThat(db.queryForObject("select count(*) from products", Integer.class)).isZero();
        assertThat(db.queryForObject("select count(*) from sales", Integer.class)).isZero();
        // only the first System Admin exists
        assertThat(db.queryForList("select username from users", String.class)).containsExactly("sysadmin");
    }
}
