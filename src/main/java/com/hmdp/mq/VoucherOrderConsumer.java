package com.hmdp.mq;

import com.hmdp.config.RabbitMqConfig;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.IVoucherOrderService;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

@Component
public class VoucherOrderConsumer {

    private static final Logger log = LoggerFactory.getLogger(VoucherOrderConsumer.class);

    @Resource
    private IVoucherOrderService voucherOrderService;

    @Resource
    private RedissonClient redissonClient;

    @RabbitListener(queues = RabbitMqConfig.SECKILL_ORDER_QUEUE)
    public void handleVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        Long voucherId = voucherOrder.getVoucherId();
        RLock lock = redissonClient.getLock("lock:order:" + userId + ":" + voucherId);
        boolean isLock = lock.tryLock();
        if (!isLock) {
            throw new IllegalStateException(
                    "未能获取用户下单锁，userId=" + userId + ", voucherId=" + voucherId
            );
        }

        try {
            voucherOrderService.createVoucherOrder(voucherOrder);
            log.info("秒杀订单处理完成，orderId={}", voucherOrder.getId());
        } finally {
            lock.unlock();
        }
    }
}
