package com.agentum.shared.platform;

import jakarta.annotation.PostConstruct;
import java.time.ZoneId;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(AgentumPlatformProperties.class)
public class AgentumPlatformConfiguration {

    private final AgentumPlatformProperties properties;

    public AgentumPlatformConfiguration(AgentumPlatformProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    void syncBusinessTimezone() {
        // main() 已根据环境变量做过一次 bootstrap；这里以 Spring 配置为准再对齐，便于 profile 覆盖。
        AgentumTimezones.apply(properties.getTimezone());
    }

    @Bean
    ZoneId platformZoneId() {
        return AgentumTimezones.businessZone();
    }
}
