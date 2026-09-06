package com.hmdp.utils;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Redis L2 逻辑过期缓存。
 *
 * <p>空值缓存解决穿透；冷缓存互斥加载和逻辑过期异步重建解决击穿；
 * 随机抖动过期时间避免大量 key 在同一时刻失效形成缓存雪崩。</p>
 */
@Component
public class LogicalExpireCacheClient {

    private static final Logger log = LoggerFactory.getLogger(LogicalExpireCacheClient.class);
    private static final String LOCK_KEY_PREFIX = "lock:logical:";

    private final StringRedisTemplate stringRedisTemplate;
    private final RedissonClient redissonClient;
    private final Executor cacheRebuildExecutor;
    private final Counter redisHitCounter;
    private final Counter redisMissCounter;
    private final Counter redisStaleCounter;

    /** 先在本 JVM 合并重建任务，再用 Redisson 处理跨实例竞争，避免线程池排队爆满。 */
    private final Set<String> localRebuilding = ConcurrentHashMap.newKeySet();

    public LogicalExpireCacheClient(
            StringRedisTemplate stringRedisTemplate,
            RedissonClient redissonClient,
            @Qualifier("cacheRebuildExecutor") Executor cacheRebuildExecutor,
            MeterRegistry meterRegistry) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.redissonClient = redissonClient;
        this.cacheRebuildExecutor = cacheRebuildExecutor;
        this.redisHitCounter = meterRegistry.counter("hmdp.cache.redis.requests", "result", "hit");
        this.redisMissCounter = meterRegistry.counter("hmdp.cache.redis.requests", "result", "miss");
        this.redisStaleCounter = meterRegistry.counter("hmdp.cache.redis.requests", "result", "stale");
    }

    /** 写入逻辑过期对象；±10% 随机抖动用于分散过期时刻。 */
    public <R> void setWithLogicalExpire(
            String key,
            R value,
            Long time,
            TimeUnit unit) {
        long seconds = jitteredSeconds(unit.toSeconds(time));
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(seconds));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    public <ID, R> R queryWithLogicalExpire(
            String keyPrefix,
            ID id,
            Class<R> type,
            Function<ID, R> dbFallback,
            Long logicalExpireTime,
            TimeUnit logicalExpireUnit,
            Long nullExpireTime,
            TimeUnit nullExpireUnit) {
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);

        if (json == null) {
            redisMissCounter.increment();
            // 冷 key 不直接查数据库，而是在 Redisson 锁内双重检查后加载。
            return loadMissingWithMutex(
                    key, id, type, dbFallback,
                    logicalExpireTime, logicalExpireUnit,
                    nullExpireTime, nullExpireUnit
            );
        }
        if (StrUtil.isBlank(json)) {
            redisHitCounter.increment();
            return null;
        }

        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        if (redisData.getExpireTime() == null) {
            redisStaleCounter.increment();
            // 兼容项目改造前写入的普通 JSON，本次仍返回旧值并异步转换为逻辑过期格式。
            R legacyValue = JSONUtil.toBean(json, type);
            scheduleRebuild(key, id, dbFallback, logicalExpireTime, logicalExpireUnit,
                    nullExpireTime, nullExpireUnit);
            return legacyValue;
        }

        R value = JSONUtil.toBean(JSONUtil.toJsonStr(redisData.getData()), type);
        if (redisData.getExpireTime().isAfter(LocalDateTime.now())) {
            redisHitCounter.increment();
            return value;
        }

        // 逻辑过期时先返回旧值，负责重建的后台线程完成后续更新，用户请求不等待数据库。
        redisStaleCounter.increment();
        scheduleRebuild(key, id, dbFallback, logicalExpireTime, logicalExpireUnit,
                nullExpireTime, nullExpireUnit);
        return value;
    }

    private <ID, R> R loadMissingWithMutex(
            String key,
            ID id,
            Class<R> type,
            Function<ID, R> dbFallback,
            Long logicalExpireTime,
            TimeUnit logicalExpireUnit,
            Long nullExpireTime,
            TimeUnit nullExpireUnit) {
        RLock lock = redissonClient.getLock(LOCK_KEY_PREFIX + key);
        boolean locked = false;
        try {
            // 冷缓存允许短暂等待建缓存者，但有上限，避免请求无限堆积。
            locked = lock.tryLock(2, 10, TimeUnit.SECONDS);
            if (!locked) {
                throw new IllegalStateException("缓存正在重建，请稍后重试，key=" + key);
            }

            String latest = stringRedisTemplate.opsForValue().get(key);
            if (StrUtil.isNotBlank(latest)) {
                RedisData redisData = JSONUtil.toBean(latest, RedisData.class);
                if (redisData.getExpireTime() == null) {
                    return JSONUtil.toBean(latest, type);
                }
                return JSONUtil.toBean(JSONUtil.toJsonStr(redisData.getData()), type);
            }
            if (latest != null) {
                return null;
            }

            R value = dbFallback.apply(id);
            if (value == null) {
                long seconds = jitteredSeconds(nullExpireUnit.toSeconds(nullExpireTime));
                stringRedisTemplate.opsForValue().set(key, "", seconds, TimeUnit.SECONDS);
                return null;
            }
            setWithLogicalExpire(key, value, logicalExpireTime, logicalExpireUnit);
            return value;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待缓存重建锁时线程被中断", e);
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private <ID, R> void scheduleRebuild(
            String key,
            ID id,
            Function<ID, R> dbFallback,
            Long logicalExpireTime,
            TimeUnit logicalExpireUnit,
            Long nullExpireTime,
            TimeUnit nullExpireUnit) {
        if (!localRebuilding.add(key)) {
            return;
        }
        try {
            cacheRebuildExecutor.execute(() -> {
                RLock lock = redissonClient.getLock(LOCK_KEY_PREFIX + key);
                boolean locked = false;
                try {
                    // 必须在工作线程内加/解 Redisson 锁，因为锁所有权与线程绑定。
                    locked = lock.tryLock();
                    if (!locked) {
                        return;
                    }
                    if (isNotExpired(stringRedisTemplate.opsForValue().get(key))) {
                        return;
                    }

                    R latestValue = dbFallback.apply(id);
                    if (latestValue == null) {
                        long seconds = jitteredSeconds(nullExpireUnit.toSeconds(nullExpireTime));
                        stringRedisTemplate.opsForValue().set(key, "", seconds, TimeUnit.SECONDS);
                    } else {
                        setWithLogicalExpire(key, latestValue, logicalExpireTime, logicalExpireUnit);
                    }
                } catch (RuntimeException e) {
                    log.error("异步重建逻辑过期缓存失败，key={}", key, e);
                } finally {
                    if (locked && lock.isHeldByCurrentThread()) {
                        lock.unlock();
                    }
                    localRebuilding.remove(key);
                }
            });
        } catch (RuntimeException e) {
            localRebuilding.remove(key);
            throw e;
        }
    }

    private boolean isNotExpired(String json) {
        if (StrUtil.isBlank(json)) {
            return false;
        }
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        return redisData.getExpireTime() != null
                && redisData.getExpireTime().isAfter(LocalDateTime.now());
    }

    private long jitteredSeconds(long seconds) {
        long safeSeconds = Math.max(1L, seconds);
        long range = Math.max(1L, safeSeconds / 10L);
        return Math.max(1L, safeSeconds + ThreadLocalRandom.current().nextLong(-range, range + 1));
    }
}
