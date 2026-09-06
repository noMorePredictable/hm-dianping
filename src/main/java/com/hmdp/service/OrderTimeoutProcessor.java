package com.hmdp.service;

import com.hmdp.entity.VoucherOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** 统一处理 RabbitMQ 超时消息和数据库定时扫描发现的漏网订单。 */
@Service
public class OrderTimeoutProcessor {

    private static final Logger log = LoggerFactory.getLogger(OrderTimeoutProcessor.class);

    private final IVoucherOrderService voucherOrderService;
    private final SeckillReservationService reservationService;

    public OrderTimeoutProcessor(
            IVoucherOrderService voucherOrderService,
            SeckillReservationService reservationService) {
        this.voucherOrderService = voucherOrderService;
        this.reservationService = reservationService;
    }

    public void process(Long orderId) {
        boolean cancelled = voucherOrderService.closeUnpaidOrder(orderId);
        if (!cancelled) {
            return;
        }

        VoucherOrder order = voucherOrderService.getById(orderId);
        SeckillReservation reservation = reservationService.find(orderId);
        if (order == null || reservation == null) {
            // 抛异常让 RabbitMQ 重试；数据库扫描兜底也会再次发现已取消但未完成 Redis 回补的订单。
            throw new IllegalStateException("关单后找不到资格预占记录，orderId=" + orderId);
        }
        reservationService.cancelCreatedOrder(reservation);
        log.info("未支付订单已关闭并回补库存，orderId={}", orderId);
    }
}
