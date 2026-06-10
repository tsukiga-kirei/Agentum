package com.agentum.runtime;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 启用定时任务：当前仅用于运行态超时/僵死作业回收（StaleExecutionReaper）。
 */
@Configuration
@EnableScheduling
public class RuntimeSchedulingConfig {
}
