package com.tommy.test;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Lightweight Spring Boot 2.7 application for verifying OTel Javaagent
 * Micrometer metric collection on JDK 8 and JDK 11.
 */
@SpringBootApplication
public class LegacyTestApplication {

    public static void main(String[] args) {
        SpringApplication.run(LegacyTestApplication.class, args);
    }
}
