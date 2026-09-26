package com.apigw;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 微服务网关 apigw 启动类。
 *
 * 同一个应用里跑两件事：
 * 1. Spring Cloud Gateway 的转发链路（请求进来 → 匹配路由 → 转发上游）；
 * 2. 管理接口（/api/gateway/routes...），负责路由与匹配规则的配置维护。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class ApigwApplication {

    public static void main(String[] args) {
        SpringApplication.run(ApigwApplication.class, args);
    }
}
