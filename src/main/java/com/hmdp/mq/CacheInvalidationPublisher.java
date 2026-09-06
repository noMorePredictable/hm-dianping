package com.hmdp.mq;

import com.hmdp.config.RabbitMqConfig;
import com.hmdp.mq.message.CacheInvalidationMessage;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/** 发布缓存失效广播，解决多实例 Caffeine 各自持有旧值的问题。 */
@Component
public class CacheInvalidationPublisher {

    private final RabbitTemplate rabbitTemplate;

    public CacheInvalidationPublisher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    public void publishShopInvalidation(Long shopId) {
        rabbitTemplate.convertAndSend(
                RabbitMqConfig.CACHE_INVALIDATION_EXCHANGE,
                "",
                new CacheInvalidationMessage("shop", shopId)
        );
    }
}
