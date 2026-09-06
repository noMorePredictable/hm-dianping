package com.hmdp.config;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Queue;

import static org.assertj.core.api.Assertions.assertThat;

/** 不连接 RabbitMQ，先验证最关键的队列参数没有被配置改坏。 */
class RabbitMqConfigTest {

    private final RabbitMqConfig config = new RabbitMqConfig();

    @Test
    void createOrderQueueShouldDeadLetterToDlq() {
        Queue queue = config.seckillOrderQueue();

        assertThat(queue.isDurable()).isTrue();
        assertThat(queue.getArguments())
                .containsEntry("x-dead-letter-exchange", RabbitMqConfig.SECKILL_ORDER_DEAD_LETTER_EXCHANGE)
                .containsEntry("x-dead-letter-routing-key", RabbitMqConfig.SECKILL_ORDER_DEAD_LETTER_ROUTING_KEY);
    }

    @Test
    void delayQueueShouldRouteExpiredMessageToCloseQueue() {
        Queue queue = config.seckillOrderDelayQueue();

        assertThat(queue.isDurable()).isTrue();
        assertThat(queue.getArguments())
                .containsEntry("x-dead-letter-exchange", RabbitMqConfig.SECKILL_ORDER_EXCHANGE)
                .containsEntry("x-dead-letter-routing-key", RabbitMqConfig.SECKILL_ORDER_CLOSE_ROUTING_KEY);
    }
}
