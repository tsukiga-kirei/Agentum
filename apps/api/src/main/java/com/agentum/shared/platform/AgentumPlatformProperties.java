package com.agentum.shared.platform;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * 平台级运行参数。
 */
@Component
@Validated
@ConfigurationProperties(prefix = "agentum.platform")
public class AgentumPlatformProperties {

    /** IANA 时区 ID，例如 Asia/Shanghai；由 AGENTUM_TIMEZONE 注入。 */
    @NotBlank
    private String timezone = "Asia/Shanghai";

    public String getTimezone() {
        return timezone;
    }

    public void setTimezone(String timezone) {
        this.timezone = timezone;
    }
}
