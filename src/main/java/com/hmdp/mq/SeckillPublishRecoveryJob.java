package com.hmdp.mq;

import com.hmdp.config.SeckillProperties;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.service.SeckillReservation;
import com.hmdp.service.SeckillReservationService;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import static com.hmdp.utils.RedisConstants.SECKILL_LIFECYCLE_LOCK_KEY;

/**
 * 修复“Redis 已预扣，但应用在 RabbitMQ Confirm 前宕机”的消息空窗。
 * 多实例可能同时扫描，所以每个 orderId 再使用一把短粒度 Redisson 锁。
 */
@Component
public class SeckillPublishRecoveryJob {

    private static final Logger log = LoggerFactory.getLogger(SeckillPublishRecoveryJob.class);

    private final SeckillReservationService reservationService;
    private final SeckillMessagePublisher messagePublisher;
    private final IVoucherOrderService voucherOrderService;
    private final RedissonClient redissonClient;
    private final SeckillProperties properties;

    public SeckillPublishRecoveryJob(
            SeckillReservationService reservationService,
            SeckillMessagePublisher messagePublisher,
            IVoucherOrderService voucherOrderService,
            RedissonClient redissonClient,
            SeckillProperties properties) {
        this.reservationService = reservationService;
        this.messagePublisher = messagePublisher;
        this.voucherOrderService = voucherOrderService;
        this.redissonClient = redissonClient;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${hmdp.seckill.recovery-fixed-delay-millis:3000}")
    public void recoverUnconfirmedMessages() {
        for (String idText : reservationService.findDueOrderIds(properties.getRecoveryBatchSize())) {
            Long orderId;
            try {
                orderId = Long.valueOf(idText);
            } catch (NumberFormatException e) {
                continue;
            }
            recoverOne(orderId);
        }
    }

    private void recoverOne(Long orderId) {
        // 与消费者共用生命周期锁，避免恢复任务判断“无订单”后补偿时，消费者正在提交订单。
        RLock lock = redissonClient.getLock(SECKILL_LIFECYCLE_LOCK_KEY + orderId);
        if (!lock.tryLock()) {
            return;
        }
        try {
            SeckillReservation reservation = reservationService.find(orderId);
            if (reservation == null || isFinished(reservation.getStatus())) {
                reservationService.removePending(orderId);
                return;
            }

            // 数据库存在订单时说明消息至少被成功消费过一次，只是 ACK/状态更新丢失。
            if (voucherOrderService.getById(orderId) != null) {
                reservationService.markCreated(orderId);
                messagePublisher.publishOrderTimeout(orderId);
                return;
            }

            if (reservation.getAttempts() >= properties.getPublishMaxAttempts()) {
                reservationService.compensate(reservation, "PUBLISH_RETRY_EXHAUSTED");
                log.error("创建订单消息重投耗尽并已补偿，orderId={}", orderId);
                return;
            }

            VoucherOrder order = new VoucherOrder();
            order.setId(orderId);
            order.setUserId(reservation.getUserId());
            order.setVoucherId(reservation.getVoucherId());
            messagePublisher.publishCreateOrder(order);
            log.warn("恢复任务重新发送创建订单消息，orderId={}, previousAttempts={}",
                    orderId, reservation.getAttempts());
        } catch (RuntimeException e) {
            // 保留 ZSET 中的记录，下一个周期继续恢复；数据库或 RabbitMQ 短暂故障不会丢单。
            log.error("恢复创建订单消息失败，orderId={}", orderId, e);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private boolean isFinished(String status) {
        return "PUBLISHED".equals(status)
                || "CREATED".equals(status)
                || "COMPENSATED".equals(status)
                || "CANCELLED".equals(status);
    }
}
