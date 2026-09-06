package com.hmdp.mq;

import com.hmdp.config.SeckillProperties;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.service.OrderTimeoutProcessor;
import com.hmdp.service.SeckillReservation;
import com.hmdp.service.SeckillReservationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * RabbitMQ 延迟消息之外的数据库兜底扫描。
 * 即使关单消息发布失败，过期未支付订单也会最终被关闭。
 */
@Component
public class OrderTimeoutFallbackJob {

    private static final Logger log = LoggerFactory.getLogger(OrderTimeoutFallbackJob.class);

    private final IVoucherOrderService voucherOrderService;
    private final SeckillReservationService reservationService;
    private final OrderTimeoutProcessor timeoutProcessor;
    private final SeckillProperties properties;

    public OrderTimeoutFallbackJob(
            IVoucherOrderService voucherOrderService,
            SeckillReservationService reservationService,
            OrderTimeoutProcessor timeoutProcessor,
            SeckillProperties properties) {
        this.voucherOrderService = voucherOrderService;
        this.reservationService = reservationService;
        this.timeoutProcessor = timeoutProcessor;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${hmdp.seckill.close-fallback-fixed-delay-millis:60000}")
    public void closeExpiredOrders() {
        LocalDateTime deadline = LocalDateTime.now().minusNanos(
                properties.getOrderTimeoutMillis() * 1_000_000L);
        List<Long> orderIds = voucherOrderService.query()
                .in("status", 1, 4)
                .le("create_time", deadline)
                .last("LIMIT 100")
                .list()
                .stream()
                .map(VoucherOrder::getId)
                .collect(Collectors.toList());

        for (Long orderId : orderIds) {
            SeckillReservation reservation = reservationService.find(orderId);
            // status=4 且 Redis 仍是 CREATED，代表上次在 DB 提交后、Redis 回补前中断。
            if (reservation == null || "CANCELLED".equals(reservation.getStatus())) {
                continue;
            }
            try {
                timeoutProcessor.process(orderId);
            } catch (RuntimeException e) {
                log.error("兜底关闭订单失败，orderId={}", orderId, e);
            }
        }
    }
}
