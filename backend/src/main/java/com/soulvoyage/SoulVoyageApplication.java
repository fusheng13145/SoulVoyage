package com.soulvoyage;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling   // M5 首个定时任务：危机生命周期巡检 / 注销冷静期（下篇·S1/S2）
public class SoulVoyageApplication {
    public static void main(String[] args) {
        SpringApplication.run(SoulVoyageApplication.class, args);
    }
}
