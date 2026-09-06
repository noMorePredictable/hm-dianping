package com.hmdp.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.mq.CacheInvalidationPublisher;
import com.hmdp.utils.LogicalExpireCacheClient;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.amqp.AmqpException;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import static com.hmdp.utils.RedisConstants.CACHE_NULL_TTL;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TTL;

/**
 * 店铺 Caffeine L1 + Redis L2 双层缓存。
 *
 * <p>L1 减少 Redis 网络访问；BloomFilter 在 Redis 层拦截明显不存在的 ID；
 * L2 使用逻辑过期保证热点 key 过期时请求仍能快速返回。</p>
 */
@Service
public class ShopCacheService {

    private static final Logger log = LoggerFactory.getLogger(ShopCacheService.class);
    private static final String SHOP_BLOOM_FILTER = "bloom:shop:id:v1";

    private final Cache<Long, Shop> localCache = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(5, TimeUnit.MINUTES)
            .recordStats()
            .build();

    private final AtomicBoolean bloomReady = new AtomicBoolean(false);
    private final ShopMapper shopMapper;
    private final LogicalExpireCacheClient logicalExpireCacheClient;
    private final StringRedisTemplate stringRedisTemplate;
    private final RBloomFilter<Long> shopIdBloomFilter;
    private final RedissonClient redissonClient;
    private final CacheInvalidationPublisher invalidationPublisher;
    private final Counter localHitCounter;
    private final Counter localMissCounter;
    private final Counter bloomRejectedCounter;

    public ShopCacheService(
            ShopMapper shopMapper,
            LogicalExpireCacheClient logicalExpireCacheClient,
            StringRedisTemplate stringRedisTemplate,
            RedissonClient redissonClient,
            CacheInvalidationPublisher invalidationPublisher,
            MeterRegistry meterRegistry) {
        this.shopMapper = shopMapper;
        this.logicalExpireCacheClient = logicalExpireCacheClient;
        this.stringRedisTemplate = stringRedisTemplate;
        this.shopIdBloomFilter = redissonClient.getBloomFilter(SHOP_BLOOM_FILTER);
        this.redissonClient = redissonClient;
        this.invalidationPublisher = invalidationPublisher;
        this.localHitCounter = meterRegistry.counter("hmdp.cache.local.requests", "result", "hit");
        this.localMissCounter = meterRegistry.counter("hmdp.cache.local.requests", "result", "miss");
        this.bloomRejectedCounter = meterRegistry.counter("hmdp.cache.bloom.rejected");
    }

    @EventListener(ApplicationReadyEvent.class)
    @Scheduled(
            initialDelayString = "${hmdp.cache.bloom-retry-millis:30000}",
            fixedDelayString = "${hmdp.cache.bloom-retry-millis:30000}")
    public void initializeBloomFilter() {
        if (bloomReady.get()) {
            return;
        }
        try {
            // 预计容量与误判率必须根据真实数据量调整；布隆过滤器允许误判，但不允许漏判。
            shopIdBloomFilter.tryInit(100_000L, 0.01D);
            List<Object> ids = shopMapper.selectObjs(new QueryWrapper<Shop>().select("id"));
            for (Object value : ids) {
                if (value instanceof Number) {
                    shopIdBloomFilter.add(((Number) value).longValue());
                }
            }
            bloomReady.set(true);
            log.info("店铺布隆过滤器初始化完成，shopCount={}", ids.size());
        } catch (RuntimeException e) {
            // 初始化失败时降级为空值缓存，不能使用未就绪 Bloom，否则会产生 false negative。
            bloomReady.set(false);
            log.error("店铺布隆过滤器初始化失败，已降级为空值缓存，30 秒后重试", e);
        }
    }

    public Shop queryById(Long id) {
        Shop local = localCache.getIfPresent(id);
        if (local != null) {
            localHitCounter.increment();
            return local;
        }
        localMissCounter.increment();

        if (bloomReady.get() && !shopIdBloomFilter.contains(id)) {
            bloomRejectedCounter.increment();
            return null;
        }

        Shop shop = logicalExpireCacheClient.queryWithLogicalExpire(
                CACHE_SHOP_KEY,
                id,
                Shop.class,
                shopMapper::selectById,
                CACHE_SHOP_TTL,
                TimeUnit.MINUTES,
                CACHE_NULL_TTL,
                TimeUnit.MINUTES
        );
        if (shop != null) {
            localCache.put(id, shop);
        }
        return shop;
    }

    /** 数据库事务提交后调用：先删共享 L2，再广播所有实例删除各自 L1。 */
    public void evictAndBroadcast(Long shopId) {
        localCache.invalidate(shopId);
        stringRedisTemplate.delete(CACHE_SHOP_KEY + shopId);
        shopIdBloomFilter.add(shopId);
        try {
            invalidationPublisher.publishShopInvalidation(shopId);
        } catch (AmqpException e) {
            // 当前实例与 Redis 已失效；其他实例的 L1 最多在 5 分钟本地 TTL 后自然收敛。
            log.error("广播店铺缓存失效失败，shopId={}", shopId, e);
        }
    }

    /** RabbitMQ 广播消费者只清本地缓存，不能再次广播。 */
    public void evictLocal(Long shopId) {
        localCache.invalidate(shopId);
    }

    /** 与缓存重建使用同一把锁，防止更新提交后又被并发查询写回旧数据。 */
    public RLock getWriteLock(Long shopId) {
        return redissonClient.getLock("lock:logical:" + CACHE_SHOP_KEY + shopId);
    }

    public CacheStats localCacheStats() {
        return localCache.stats();
    }
}
