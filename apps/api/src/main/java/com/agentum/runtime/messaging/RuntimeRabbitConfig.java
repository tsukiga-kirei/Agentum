package com.agentum.runtime.messaging;

import com.agentum.runtime.execution.RuntimeExecutionProperties;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 运行态 RabbitMQ 拓扑：启动时声明 exchange 与执行/死信队列，避免依赖手工初始化脚本。
 *
 * <p>执行队列绑定死信交换：业务校验类异常（不可重试）会被拒绝进入 DLQ，便于后续人工排查与重投。</p>
 */
@Configuration
@EnableConfigurationProperties(RuntimeExecutionProperties.class)
public class RuntimeRabbitConfig {

    @Bean
    public Declarables runtimeTopology(RuntimeExecutionProperties properties) {
        RuntimeExecutionProperties.Rabbitmq rabbit = properties.getRabbitmq();
        TopicExchange exchange = new TopicExchange(rabbit.getExchange(), true, false);
        Queue executeQueue = QueueBuilder.durable(rabbit.getQueueNodeExecute())
            .deadLetterExchange(rabbit.getExchange())
            .deadLetterRoutingKey(rabbit.getQueueNodeExecuteDlq())
            .build();
        Queue dlq = QueueBuilder.durable(rabbit.getQueueNodeExecuteDlq()).build();
        Binding executeBinding = BindingBuilder.bind(executeQueue).to(exchange).with(rabbit.getQueueNodeExecute());
        Binding dlqBinding = BindingBuilder.bind(dlq).to(exchange).with(rabbit.getQueueNodeExecuteDlq());
        return new Declarables(exchange, executeQueue, dlq, executeBinding, dlqBinding);
    }

    @Bean
    public Jackson2JsonMessageConverter runtimeMessageConverter(com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper);
    }

    @Bean
    public RabbitTemplate runtimeRabbitTemplate(ConnectionFactory connectionFactory, Jackson2JsonMessageConverter converter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(converter);
        return template;
    }

    /**
     * Worker 监听容器：prefetch=1 防止单消费者囤积长任务；并发数决定同时执行的节点作业数量。
     */
    @Bean
    public SimpleRabbitListenerContainerFactory runtimeListenerContainerFactory(
        ConnectionFactory connectionFactory,
        Jackson2JsonMessageConverter converter,
        RuntimeExecutionProperties properties
    ) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(converter);
        factory.setPrefetchCount(1);
        int concurrency = Math.max(1, properties.getExecution().getWorkerConcurrency());
        factory.setConcurrentConsumers(concurrency);
        factory.setMaxConcurrentConsumers(concurrency);
        // 业务异常不自动 requeue（进入 DLQ）；可重试错误由 Listener 主动重新入队。
        factory.setDefaultRequeueRejected(false);
        return factory;
    }
}
