package com.yyx.ciyuantown;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@MapperScan("com.yyx.ciyuantown.business.**.mapper")
@EnableScheduling
public class CiyuantownBackApplication {

    public static void main(String[] args) {
        SpringApplication.run(CiyuantownBackApplication.class, args);
    }

}

