package com.hmdp.mq;

import com.hmdp.config.RabbitMqConfig;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.service.SeckillOrderCreateResult;
import com.hmdp.service.SeckillReservation;
import com.hmdp.service.SeckillReservationService;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;

import static com.hmdp.utils.RedisConstants.SECKILL_LIFECYCLE_LOCK_KEY;

@Component
public class VoucherOrderConsumer {

    private static final Logger log = LoggerFactory.getLogger(VoucherOrderConsumer.class);

    @Resource
    private IVoucherOrderService voucherOrderService;

    @Resource
    private RedissonClient redissonClient;
    @Resource
    private SeckillReservationService reservationService;
    @Resource
    private SeckillMessagePublisher messagePublisher;

    @RabbitListener(queues = RabbitMqConfig.SECKILL_ORDER_QUEUE)
    public void handleVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        Long voucherId = voucherOrder.getVoucherId();
        // 订单生命周期锁同时被恢复任务和死信消费者使用：数据库提交与最终补偿不能并发。
        RLock lifecycleLock = redissonClient.getLock(
                SECKILL_LIFECYCLE_LOCK_KEY + voucherOrder.getId());
        if (!lifecycleLock.tryLock()) {
            throw new IllegalStateException("未能获取订单生命周期锁，orderId=" + voucherOrder.getId());
        }

        RLock userLock = redissonClient.getLock("lock:order:" + userId + ":" + voucherId);
        boolean userLocked = false;
        try {
            SeckillReservation reservation = reservationService.find(voucherOrder.getId());
            if (reservation == null) {
                throw new IllegalStateException("找不到秒杀资格预占，orderId=" + voucherOrder.getId());
            }
            if ("COMPENSATED".equals(reservation.getStatus())
                    || "CANCELLED".equals(reservation.getStatus())) {
                // 恢复任务已经做过最终补偿时，迟到的旧消息不得再创建数据库订单。
                log.warn("忽略已进入终态的迟到消息，orderId={}, status={}",
                        voucherOrder.getId(), reservation.getStatus());
                return;
            }

            userLocked = userLock.tryLock();
            if (!userLocked) {
                throw new IllegalStateException(
                        "未能获取用户下单锁，userId=" + userId + ", voucherId=" + voucherId
                );
            }

            SeckillOrderCreateResult result =
                    voucherOrderService.createVoucherOrder(voucherOrder);
            if (result == SeckillOrderCreateResult.CREATED
                    || result == SeckillOrderCreateResult.IDEMPOTENT_SUCCESS) {
                // 先标记数据库订单已存在，再发送超时消息；重复超时消息由 CAS 和 Lua 消除。
                reservationService.markCreated(voucherOrder.getId());
                messagePublisher.publishOrderTimeout(voucherOrder.getId());
                log.info("秒杀订单处理完成，orderId={}, result={}", voucherOrder.getId(), result);
                return;
            }

            // 业务永久失败无需让 RabbitMQ 重试，直接幂等归还 Redis 资格。
            reservationService.compensate(
                    reservation,
                    result.name(),
                    result != SeckillOrderCreateResult.DUPLICATE_PURCHASE
            );
            log.warn("秒杀订单未创建并已补偿，orderId={}, result={}", voucherOrder.getId(), result);
        } finally {
            if (userLocked && userLock.isHeldByCurrentThread()) {
                userLock.unlock();
            }
            if (lifecycleLock.isHeldByCurrentThread()) {
                lifecycleLock.unlock();
            }
        }
    }
}
