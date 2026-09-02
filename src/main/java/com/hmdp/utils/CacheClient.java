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
        String json = stringRedisTemplate.opsForValue().get("cache:shop:" + id);
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
            stringRedisTemplate.opsForValue().set("cache:shop:" + id,"",2L, TimeUnit.MINUTES);
            return null;
        }
        //6.存在，写入redis
      this.set(key,r,Time,Unit);
        //7.返回
        return r;
    }
}
