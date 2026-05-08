package com.agentum;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

// API 服务入口。当前保持单体应用，模块边界通过 com.agentum 下的业务包先行稳定。
@SpringBootApplication
public class AgentumApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentumApiApplication.class, args);
    }
}
