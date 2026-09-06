package com.hmdp.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.AnonymousQueue;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMqConfig {

    public static final String SECKILL_ORDER_EXCHANGE = "seckill.order.exchange";
    public static final String SECKILL_ORDER_QUEUE = "seckill.order.queue";
    public static final String SECKILL_ORDER_ROUTING_KEY = "seckill.order.create";

    public static final String SECKILL_ORDER_DEAD_LETTER_EXCHANGE = "seckill.order.dlx";
    public static final String SECKILL_ORDER_DEAD_LETTER_QUEUE = "seckill.order.dlq";
    public static final String SECKILL_ORDER_DEAD_LETTER_ROUTING_KEY = "seckill.order.dead";

    /** 等待订单超时的队列没有消费者，消息到期后通过 DLX 转入关单队列。 */
    public static final String SECKILL_ORDER_DELAY_QUEUE = "seckill.order.delay.queue";
    public static final String SECKILL_ORDER_DELAY_ROUTING_KEY = "seckill.order.delay";
    public static final String SECKILL_ORDER_CLOSE_QUEUE = "seckill.order.close.queue";
    public static final String SECKILL_ORDER_CLOSE_ROUTING_KEY = "seckill.order.close";

    public static final String CACHE_INVALIDATION_EXCHANGE = "cache.invalidation.exchange";

    @Bean("seckillOrderExchange")
    public DirectExchange seckillOrderExchange() {
        return new DirectExchange(SECKILL_ORDER_EXCHANGE, true, false);
    }

    @Bean("seckillOrderQueue")
    public Queue seckillOrderQueue() {
        return QueueBuilder.durable(SECKILL_ORDER_QUEUE)
                .deadLetterExchange(SECKILL_ORDER_DEAD_LETTER_EXCHANGE)
                .deadLetterRoutingKey(SECKILL_ORDER_DEAD_LETTER_ROUTING_KEY)
                .build();
    }

    @Bean
    public Binding seckillOrderBinding(
            @Qualifier("seckillOrderQueue") Queue queue,
            @Qualifier("seckillOrderExchange") DirectExchange exchange) {
        return BindingBuilder.bind(queue)
                .to(exchange)
                .with(SECKILL_ORDER_ROUTING_KEY);
    }

    @Bean("seckillOrderDeadLetterExchange")
    public DirectExchange seckillOrderDeadLetterExchange() {
        return new DirectExchange(SECKILL_ORDER_DEAD_LETTER_EXCHANGE, true, false);
    }

    @Bean("seckillOrderDeadLetterQueue")
    public Queue seckillOrderDeadLetterQueue() {
        return QueueBuilder.durable(SECKILL_ORDER_DEAD_LETTER_QUEUE).build();
    }

    @Bean
    public Binding seckillOrderDeadLetterBinding(
            @Qualifier("seckillOrderDeadLetterQueue") Queue queue,
            @Qualifier("seckillOrderDeadLetterExchange") DirectExchange exchange) {
        return BindingBuilder.bind(queue)
                .to(exchange)
                .with(SECKILL_ORDER_DEAD_LETTER_ROUTING_KEY);
    }

    @Bean("seckillOrderDelayQueue")
    public Queue seckillOrderDelayQueue() {
        return QueueBuilder.durable(SECKILL_ORDER_DELAY_QUEUE)
                // 队列本身不消费；过期消息变成“死信”后路由到真正的关单队列。
                .deadLetterExchange(SECKILL_ORDER_EXCHANGE)
                .deadLetterRoutingKey(SECKILL_ORDER_CLOSE_ROUTING_KEY)
                .build();
    }

    @Bean
    public Binding seckillOrderDelayBinding(
            @Qualifier("seckillOrderDelayQueue") Queue queue,
            @Qualifier("seckillOrderExchange") DirectExchange exchange) {
        return BindingBuilder.bind(queue)
                .to(exchange)
                .with(SECKILL_ORDER_DELAY_ROUTING_KEY);
    }

    @Bean("seckillOrderCloseQueue")
    public Queue seckillOrderCloseQueue() {
        return QueueBuilder.durable(SECKILL_ORDER_CLOSE_QUEUE).build();
    }

    @Bean
    public Binding seckillOrderCloseBinding(
            @Qualifier("seckillOrderCloseQueue") Queue queue,
            @Qualifier("seckillOrderExchange") DirectExchange exchange) {
        return BindingBuilder.bind(queue)
                .to(exchange)
                .with(SECKILL_ORDER_CLOSE_ROUTING_KEY);
    }

    @Bean("cacheInvalidationExchange")
    public FanoutExchange cacheInvalidationExchange() {
        return new FanoutExchange(CACHE_INVALIDATION_EXCHANGE, true, false);
    }

    @Bean("cacheInvalidationQueue")
    public Queue cacheInvalidationQueue() {
        // 每个应用实例创建独占临时队列，Fanout 才能让所有 JVM 都清除自己的 L1 缓存。
        return new AnonymousQueue();
    }

    @Bean
    public Binding cacheInvalidationBinding(
            @Qualifier("cacheInvalidationQueue") Queue queue,
            @Qualifier("cacheInvalidationExchange") FanoutExchange exchange) {
        return BindingBuilder.bind(queue).to(exchange);
    }

    @Bean
    public MessageConverter rabbitMessageConverter(ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper);
    }
}
