package com.stcloud;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@SpringBootApplication(scanBasePackages = "com.stcloud")
@MapperScan("com.stcloud.**.mapper")
@EnableAsync
@EnableScheduling
@EnableTransactionManagement
public class StCloudApplication {

    public static void main(String[] args) {
        SpringApplication.run(StCloudApplication.class, args);
    }
}
