package com.hmdp.utils;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * 基础 Cache Aside 工具，保留给普通非热点数据使用。
 * 热点店铺已经改用 {@link LogicalExpireCacheClient}，不要把两种策略混在同一次查询中。
 */
@Slf4j
@Component
public class CacheClient {
    private final StringRedisTemplate stringRedisTemplate;
    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public void set(String key, Object value , Long Time, TimeUnit Unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), Time, Unit);
    }
    public void setWithLogicalExpire(String key, Object value, Long Time, TimeUnit Unit) {
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(Unit.toSeconds(Time)));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));

    }
    public <R,ID>R queryWithPassThrough(String keyPrefix , ID id, Class <R> type, Function<ID, R> dbFallback,Long Time, TimeUnit Unit) {
        String key = keyPrefix+id;
        //1.从redis查找
        // 泛型工具必须使用调用方传入的 keyPrefix，不能硬编码成店铺 key。
        String json = stringRedisTemplate.opsForValue().get(key);
        //2.判断存在与否
        if (StrUtil.isNotBlank(json)) {
            //3.存在返回hopJ
            return JSONUtil.toBean(json,type);
        }
        if (json!=null) {
            return null;
        }
        //4.不存在，查数据库
        R r = dbFallback.apply(id);
        //5.不存在，返回错误
        if (r == null) {
            //将空值写入redis
            stringRedisTemplate.opsForValue().set(key,"",2L, TimeUnit.MINUTES);
            return null;
        }
        //6.存在，写入redis
      this.set(key,r,Time,Unit);
        //7.返回
        return r;
    }
}
