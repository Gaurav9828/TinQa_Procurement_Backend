package com.tinqa.procurement;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ProcurementApplication {

    public static void main(String[] args) {
        SpringApplication.run(ProcurementApplication.class, args);
    }
}