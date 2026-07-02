package com.trekmate.exe;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ExeBackendApplication {
    public static void main(String[] args) {
        SpringApplication.run(ExeBackendApplication.class, args);
    }
}
