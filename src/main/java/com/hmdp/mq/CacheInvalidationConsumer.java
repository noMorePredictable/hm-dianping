package com.hmdp.mq;

import com.hmdp.mq.message.CacheInvalidationMessage;
import com.hmdp.service.ShopCacheService;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/** 每个应用实例都消费一份广播，只删除自己的 Caffeine L1，避免广播循环。 */
@Component
public class CacheInvalidationConsumer {

    private final ShopCacheService shopCacheService;

    public CacheInvalidationConsumer(ShopCacheService shopCacheService) {
        this.shopCacheService = shopCacheService;
    }

    @RabbitListener(queues = "#{cacheInvalidationQueue.name}")
    public void invalidateLocalCache(CacheInvalidationMessage message) {
        if ("shop".equals(message.getCacheName()) && message.getKey() != null) {
            shopCacheService.evictLocal(message.getKey());
        }
    }
}
