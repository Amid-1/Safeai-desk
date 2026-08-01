package ru.safeai.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class SafeaiBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(
                SafeaiBackendApplication.class,
                args
        );
    }
}