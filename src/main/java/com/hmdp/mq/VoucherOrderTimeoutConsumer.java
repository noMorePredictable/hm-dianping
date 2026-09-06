package com.hmdp.mq;

import com.hmdp.config.RabbitMqConfig;
import com.hmdp.mq.message.OrderTimeoutMessage;
import com.hmdp.service.OrderTimeoutProcessor;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/** 消费 TTL 到期后的关单消息。 */
@Component
public class VoucherOrderTimeoutConsumer {

    private final OrderTimeoutProcessor timeoutProcessor;

    public VoucherOrderTimeoutConsumer(OrderTimeoutProcessor timeoutProcessor) {
        this.timeoutProcessor = timeoutProcessor;
    }

    @RabbitListener(queues = RabbitMqConfig.SECKILL_ORDER_CLOSE_QUEUE)
    public void closeUnpaidOrder(OrderTimeoutMessage message) {
        timeoutProcessor.process(message.getOrderId());
    }
}
