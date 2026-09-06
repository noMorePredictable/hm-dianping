package com.hmdp.utils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.config.SeckillProperties;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;

/**
 * 秒杀接口的前置限流器。
 *
 * <p>请求在进入 Controller/Service 前就被削峰，避免恶意用户或突发流量
 * 占满 Tomcat 线程。Lua 同时约束“单券总流量”和“单用户流量”，多实例下
 * 仍共享同一份窗口计数。</p>
 */
@Component
public class SeckillRateLimitInterceptor implements HandlerInterceptor {

    private static final DefaultRedisScript<Long> RATE_LIMIT_SCRIPT;

    static {
        RATE_LIMIT_SCRIPT = new DefaultRedisScript<>();
        RATE_LIMIT_SCRIPT.setLocation(new ClassPathResource("seckill_rate_limit.lua"));
        RATE_LIMIT_SCRIPT.setResultType(Long.class);
    }

    private final StringRedisTemplate stringRedisTemplate;
    private final SeckillProperties properties;
    private final ObjectMapper objectMapper;

    public SeckillRateLimitInterceptor(
            StringRedisTemplate stringRedisTemplate,
            SeckillProperties properties,
            ObjectMapper objectMapper) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean preHandle(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler) throws Exception {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            // LoginInterceptor 的 order 更小，正常情况下未登录请求会先被它拒绝。
            return true;
        }

        String voucherId = lastPathSegment(request.getRequestURI());
        SeckillProperties.RateLimit limit = properties.getRateLimit();
        Long result = stringRedisTemplate.execute(
                RATE_LIMIT_SCRIPT,
                Arrays.asList(
                        "rate:seckill:global:" + voucherId,
                        "rate:seckill:user:" + voucherId + ":" + user.getId()
                ),
                Long.toString(limit.getWindowMillis()),
                Integer.toString(limit.getGlobalRequests()),
                Integer.toString(limit.getUserRequests()),
                UUID.randomUUID().toString()
        );

        if (result == null || result == 0L) {
            return true;
        }

        response.setStatus(429);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        String message = result == 2L ? "请求过于频繁，请稍后重试" : "秒杀人数过多，请稍后重试";
        response.getWriter().write(objectMapper.writeValueAsString(Result.fail(message)));
        return false;
    }

    private String lastPathSegment(String uri) {
        int index = uri.lastIndexOf('/');
        return index < 0 ? uri : uri.substring(index + 1);
    }
}
