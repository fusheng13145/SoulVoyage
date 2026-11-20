package com.leyon.soul;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.scheduling.annotation.EnableScheduling;

// 本服务只认 JWT（无 httpBasic/formLogin），排除 Spring 默认的内存用户：
// 否则启动日志会打印一条随机 "generated security password"，既无用又像后门口令，误导排查。
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@EnableScheduling   // M5 首个定时任务：危机生命周期巡检 / 注销冷静期（下篇·S1/S2）
public class SoulApplication {
    public static void main(String[] args) {
        SpringApplication.run(SoulApplication.class, args);
    }
}
