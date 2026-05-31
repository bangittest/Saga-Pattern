package com.demo.order;

//import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
//import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class OrderServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrderServiceApplication.class, args);

        System.out.println("Random Key: " +
                RandomStringGenerator.generate(16));
        System.out.println("HELLO1111111111111111111111111111111111111111111111");
        System.out.println("123TEST");
    }

//    @Bean
//    CommandLineRunner crashApp() {
//        return args -> {
//            System.out.println("Application started...");
//            throw new RuntimeException("Crash for testing");
//        };
//    }
}