package com.meterbill;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class MeterBillApplication {

    public static void main(String[] args) {
        SpringApplication.run(MeterBillApplication.class, args);
    }

}
