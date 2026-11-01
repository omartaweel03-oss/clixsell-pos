package com.clixsell.pos;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Starts the ClixSell POS backend on http://localhost:8080. */
@SpringBootApplication
public class ClixsellBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(ClixsellBackendApplication.class, args);
    }
}
