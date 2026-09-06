package com.hmdp.service;

import com.hmdp.config.SeckillProperties;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.Set;

import static com.hmdp.utils.RedisConstants.SECKILL_ORDER_KEY;
import static com.hmdp.utils.RedisConstants.SECKILL_PUBLISH_PENDING_KEY;
import static com.hmdp.utils.RedisConstants.SECKILL_RESERVATION_KEY;
import static com.hmdp.utils.RedisConstants.SECKILL_STOCK_KEY;

/**
 * 管理 Redis 中的秒杀“资格预占”状态机。
 *
 * <p>Redis 预扣与 RabbitMQ 发布不属于同一个事务，因此必须留下可恢复记录。
 * 这里的 Lua 脚本把状态变化、待恢复集合和库存补偿做成原子操作。</p>
 */
@Service
public class SeckillReservationService {

    private static final DefaultRedisScript<Long> MARK_PUBLISHING_SCRIPT =
            script("seckill_mark_publishing.lua");
    private static final DefaultRedisScript<Long> MARK_PUBLISHED_SCRIPT =
            script("seckill_mark_published.lua");
    private static final DefaultRedisScript<Long> MARK_CREATED_SCRIPT =
            script("seckill_mark_created.lua");
    private static final DefaultRedisScript<Long> MARK_PUBLISH_FAILED_SCRIPT =
            script("seckill_mark_publish_failed.lua");
    private static final DefaultRedisScript<Long> COMPENSATE_SCRIPT =
            script("seckill_compensate.lua");
    private static final DefaultRedisScript<Long> CANCEL_SCRIPT =
            script("seckill_cancel.lua");

    private final StringRedisTemplate stringRedisTemplate;
    private final SeckillProperties properties;

    public SeckillReservationService(
            StringRedisTemplate stringRedisTemplate,
            SeckillProperties properties) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.properties = properties;
    }

    /** 返回本次实际发送的序号；返回 -1 表示订单已经处于终态，不应再次发送。 */
    public long markPublishing(Long orderId) {
        Long result = stringRedisTemplate.execute(
                MARK_PUBLISHING_SCRIPT,
                Arrays.asList(reservationKey(orderId), SECKILL_PUBLISH_PENDING_KEY),
                orderId.toString(),
                Long.toString(System.currentTimeMillis()
                        + properties.getPublishConfirmTimeoutMillis()),
                Long.toString(properties.getReservationTtlSeconds())
        );
        return result == null ? -1L : result;
    }

    /** Broker Confirm ACK 后移出待恢复集合。 */
    public void markPublished(Long orderId) {
        stringRedisTemplate.execute(
                MARK_PUBLISHED_SCRIPT,
                Arrays.asList(reservationKey(orderId), SECKILL_PUBLISH_PENDING_KEY),
                orderId.toString()
        );
    }

    /** 发布失败不立即丢单，而是交给定时恢复任务重投。 */
    public void markPublishFailed(Long orderId, String reason) {
        stringRedisTemplate.execute(
                MARK_PUBLISH_FAILED_SCRIPT,
                Arrays.asList(reservationKey(orderId), SECKILL_PUBLISH_PENDING_KEY),
                orderId.toString(),
                Long.toString(System.currentTimeMillis() + 1_000L),
                abbreviate(reason),
                Long.toString(properties.getReservationTtlSeconds())
        );
    }

    /** 数据库订单已经创建，资格预占进入 CREATED 终态。 */
    public void markCreated(Long orderId) {
        stringRedisTemplate.execute(
                MARK_CREATED_SCRIPT,
                Arrays.asList(reservationKey(orderId), SECKILL_PUBLISH_PENDING_KEY),
                orderId.toString(),
                Long.toString(properties.getReservationTtlSeconds())
        );
    }

    /**
     * 最终无法创建订单时补回 Redis 库存。
     * Lua 会拒绝补偿 CREATED/CANCELLED 等终态，因此重复调用是安全的。
     */
    public boolean compensate(SeckillReservation reservation, String reason) {
        return compensate(reservation, reason, true);
    }

    public boolean compensate(
            SeckillReservation reservation,
            String reason,
            boolean removeUserQualification) {
        if (reservation == null) {
            return false;
        }
        Long result = stringRedisTemplate.execute(
                COMPENSATE_SCRIPT,
                Arrays.asList(
                        reservationKey(reservation.getOrderId()),
                        SECKILL_STOCK_KEY + reservation.getVoucherId(),
                        SECKILL_ORDER_KEY + reservation.getVoucherId(),
                        SECKILL_PUBLISH_PENDING_KEY
                ),
                reservation.getOrderId().toString(),
                reservation.getUserId().toString(),
                abbreviate(reason),
                Long.toString(properties.getReservationTtlSeconds()),
                removeUserQualification ? "1" : "0"
        );
        return Long.valueOf(1L).equals(result);
    }

    /** 未支付订单关闭后的幂等 Redis 库存回补。 */
    public boolean cancelCreatedOrder(SeckillReservation reservation) {
        if (reservation == null) {
            return false;
        }
        Long result = stringRedisTemplate.execute(
                CANCEL_SCRIPT,
                Arrays.asList(
                        reservationKey(reservation.getOrderId()),
                        SECKILL_STOCK_KEY + reservation.getVoucherId(),
                        SECKILL_ORDER_KEY + reservation.getVoucherId(),
                        SECKILL_PUBLISH_PENDING_KEY
                ),
                reservation.getOrderId().toString(),
                reservation.getUserId().toString(),
                Long.toString(properties.getReservationTtlSeconds())
        );
        return Long.valueOf(1L).equals(result);
    }

    public SeckillReservation find(Long orderId) {
        Map<Object, Object> values = stringRedisTemplate.opsForHash()
                .entries(reservationKey(orderId));
        if (values.isEmpty()) {
            return null;
        }
        SeckillReservation reservation = new SeckillReservation();
        reservation.setOrderId(toLong(values.get("orderId")));
        reservation.setVoucherId(toLong(values.get("voucherId")));
        reservation.setUserId(toLong(values.get("userId")));
        reservation.setStatus(toString(values.get("status")));
        reservation.setAttempts(toInt(values.get("attempts")));
        reservation.setReason(toString(values.get("reason")));
        return reservation;
    }

    /** 查询已经到达恢复时间的订单 ID。 */
    public Set<String> findDueOrderIds(int batchSize) {
        Set<String> result = stringRedisTemplate.opsForZSet().rangeByScore(
                SECKILL_PUBLISH_PENDING_KEY,
                0,
                System.currentTimeMillis(),
                0,
                batchSize
        );
        return result == null ? Collections.emptySet() : result;
    }

    public void removePending(Long orderId) {
        stringRedisTemplate.opsForZSet().remove(SECKILL_PUBLISH_PENDING_KEY, orderId.toString());
    }

    private static String reservationKey(Long orderId) {
        return SECKILL_RESERVATION_KEY + orderId;
    }

    private static DefaultRedisScript<Long> script(String location) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(location));
        script.setResultType(Long.class);
        return script;
    }

    private static Long toLong(Object value) {
        return value == null ? null : Long.valueOf(value.toString());
    }

    private static int toInt(Object value) {
        return value == null ? 0 : Integer.parseInt(value.toString());
    }

    private static String toString(Object value) {
        return value == null ? null : value.toString();
    }

    private static String abbreviate(String value) {
        if (value == null) {
            return "unknown";
        }
        return value.length() <= 200 ? value : value.substring(0, 200);
    }
}
