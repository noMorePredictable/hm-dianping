package com.hmdp.utils;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * 逻辑过期缓存工具：
 * 1. 缓存空字符串，阻止不存在的数据反复查询数据库（缓存穿透）。
 * 2. 数据逻辑过期后先返回旧值，再异步重建缓存（缓存击穿）。
 *
 * 该类不会替换或修改现有 CacheClient，只有业务代码主动调用时才会生效。
 */
@Slf4j
@Component
public class LogicalExpireCacheClient {

    private static final ExecutorService CACHE_REBUILD_EXECUTOR =
            Executors.newFixedThreadPool(10);

    private static final String LOCK_KEY_PREFIX = "lock:logical:";
    private static final long LOCK_TTL_SECONDS = 10L;

    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT =
            new DefaultRedisScript<>(
                    "if redis.call('get', KEYS[1]) == ARGV[1] then " +
                            "return redis.call('del', KEYS[1]) else return 0 end",
                    Long.class
            );

    private final StringRedisTemplate stringRedisTemplate;

    public LogicalExpireCacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 写入带逻辑过期时间的数据。Redis 的 key 本身不设置物理过期时间。
     */
    public <R> void setWithLogicalExpire(
            String key,
            R value,
            Long time,
            TimeUnit unit) {

        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(
                LocalDateTime.now().plusSeconds(unit.toSeconds(time))
        );

        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    /**
     * 查询逻辑过期缓存。
     *
     * @param keyPrefix         Redis key 前缀，例如 cache:shop:
     * @param id                要查询的数据 id
     * @param type              返回对象类型，例如 Shop.class
     * @param dbFallback        缓存未命中或重建时使用的数据库查询函数
     * @param logicalExpireTime 正常数据的逻辑有效时长
     * @param logicalExpireUnit 正常数据逻辑有效时长的单位
     * @param nullExpireTime    空值缓存的物理有效时长
     * @param nullExpireUnit    空值缓存物理有效时长的单位
     * @return 查询到的对象；数据不存在时返回 null
     */
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

        // Redis 中没有这个 key：查询数据库，并建立缓存。
        if (json == null) {
            R value = dbFallback.apply(id);
            if (value == null) {
                // 空字符串代表“数据库中不存在”，短时间缓存可防止缓存穿透。
                stringRedisTemplate.opsForValue().set(
                        key, "", nullExpireTime, nullExpireUnit
                );
                return null;
            }
            setWithLogicalExpire(key, value, logicalExpireTime, logicalExpireUnit);
            return value;
        }

        // Redis 命中空字符串：数据库中不存在，直接返回，不再访问数据库。
        if (StrUtil.isBlank(json)) {
            return null;
        }

        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        R value = JSONUtil.toBean(JSONUtil.toJsonStr(redisData.getData()), type);

        // 逻辑时间尚未过期，直接返回缓存数据。
        if (redisData.getExpireTime().isAfter(LocalDateTime.now())) {
            return value;
        }

        // 逻辑时间已经过期：尝试获得锁，只有获得锁的线程负责异步重建。
        String lockKey = LOCK_KEY_PREFIX + key;
        String lockToken = UUID.randomUUID().toString();
        if (tryLock(lockKey, lockToken)) {
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    // 获得锁后再次检查，避免前一个线程已经完成重建。
                    String latestJson = stringRedisTemplate.opsForValue().get(key);
                    if (isNotExpired(latestJson)) {
                        return;
                    }

                    R latestValue = dbFallback.apply(id);
                    if (latestValue == null) {
                        stringRedisTemplate.opsForValue().set(
                                key, "", nullExpireTime, nullExpireUnit
                        );
                    } else {
                        setWithLogicalExpire(
                                key,
                                latestValue,
                                logicalExpireTime,
                                logicalExpireUnit
                        );
                    }
                } catch (Exception e) {
                    log.error("重建逻辑过期缓存失败，key={}", key, e);
                } finally {
                    unlock(lockKey, lockToken);
                }
            });
        }

        // 不等待缓存重建，本次请求直接返回旧数据。
        return value;
    }

    private boolean isNotExpired(String json) {
        if (StrUtil.isBlank(json)) {
            return false;
        }
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        return redisData.getExpireTime() != null
                && redisData.getExpireTime().isAfter(LocalDateTime.now());
    }

    private boolean tryLock(String key, String token) {
        Boolean success = stringRedisTemplate.opsForValue().setIfAbsent(
                key,
                token,
                LOCK_TTL_SECONDS,
                TimeUnit.SECONDS
        );
        return Boolean.TRUE.equals(success);
    }

    private void unlock(String key, String token) {
        stringRedisTemplate.execute(
                UNLOCK_SCRIPT,
                Collections.singletonList(key),
                token
        );
    }
}
