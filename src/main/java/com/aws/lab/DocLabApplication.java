package com.aws.lab;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class DocLabApplication {

    public static void main(String[] args) {
        System.out.println(">>> Starting DocLab Intelligence Platform (Spring Boot)...");
        SpringApplication.run(DocLabApplication.class, args);
    }
}
