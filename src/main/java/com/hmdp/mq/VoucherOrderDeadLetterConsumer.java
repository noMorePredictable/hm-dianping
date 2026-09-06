package com.hmdp.mq;

import com.hmdp.config.RabbitMqConfig;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.service.SeckillReservation;
import com.hmdp.service.SeckillReservationService;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import static com.hmdp.utils.RedisConstants.SECKILL_LIFECYCLE_LOCK_KEY;

/**
 * 创建订单消息重试耗尽后的最后处理器。
 *
 * <p>先查询数据库再决定是否补偿，避免“数据库已经提交，但消费者在 ACK 前宕机”
 * 时错误归还 Redis 库存。</p>
 */
@Component
public class VoucherOrderDeadLetterConsumer {

    private static final Logger log = LoggerFactory.getLogger(VoucherOrderDeadLetterConsumer.class);

    private final IVoucherOrderService voucherOrderService;
    private final SeckillReservationService reservationService;
    private final SeckillMessagePublisher messagePublisher;
    private final RedissonClient redissonClient;

    public VoucherOrderDeadLetterConsumer(
            IVoucherOrderService voucherOrderService,
            SeckillReservationService reservationService,
            SeckillMessagePublisher messagePublisher,
            RedissonClient redissonClient) {
        this.voucherOrderService = voucherOrderService;
        this.reservationService = reservationService;
        this.messagePublisher = messagePublisher;
        this.redissonClient = redissonClient;
    }

    @RabbitListener(queues = RabbitMqConfig.SECKILL_ORDER_DEAD_LETTER_QUEUE)
    public void handleDeadLetter(VoucherOrder message) {
        RLock lifecycleLock = redissonClient.getLock(
                SECKILL_LIFECYCLE_LOCK_KEY + message.getId());
        if (!lifecycleLock.tryLock()) {
            // 抛异常让 DLQ 消费稍后重试，而不是与正在提交的主消费者并发补偿。
            throw new IllegalStateException("死信未能获取订单生命周期锁，orderId=" + message.getId());
        }
        try {
            VoucherOrder saved = voucherOrderService.getById(message.getId());
            if (saved != null) {
                // 数据库已成功时，这是“提交后 ACK 前失败”产生的死信，不应补偿库存。
                reservationService.markCreated(message.getId());
                messagePublisher.publishOrderTimeout(message.getId());
                log.warn("死信对应订单已存在，按幂等成功处理，orderId={}", message.getId());
                return;
            }

            SeckillReservation reservation = reservationService.find(message.getId());
            boolean hasAnotherOrder = voucherOrderService.query()
                    .eq("voucher_id", message.getVoucherId())
                    .eq("user_id", message.getUserId())
                    .count() > 0;
            reservationService.compensate(reservation, "CREATE_ORDER_DLQ", !hasAnotherOrder);
            log.error("创建订单消息进入死信队列，已补偿资格，orderId={}", message.getId());
        } finally {
            if (lifecycleLock.isHeldByCurrentThread()) {
                lifecycleLock.unlock();
            }
        }
    }
}
