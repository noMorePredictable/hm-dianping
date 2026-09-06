package com.hmdp.mq;

import com.hmdp.config.RabbitMqConfig;
import com.hmdp.config.SeckillProperties;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mq.message.OrderTimeoutMessage;
import com.hmdp.service.SeckillReservationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * 秒杀相关消息的唯一发送入口。
 *
 * <p>消息持久化只能降低 Broker 重启时丢消息的概率；真正的可靠投递还需要
 * Confirm、Return、可恢复的发送记录和幂等消费者共同完成。</p>
 */
@Component
public class SeckillMessagePublisher {

    public static final String EVENT_TYPE_HEADER = "eventType";
    public static final String ORDER_ID_HEADER = "orderId";
    public static final String EVENT_CREATE_ORDER = "CREATE_ORDER";
    public static final String EVENT_CLOSE_ORDER = "CLOSE_ORDER";

    private static final Logger log = LoggerFactory.getLogger(SeckillMessagePublisher.class);

    private final RabbitTemplate rabbitTemplate;
    private final SeckillReservationService reservationService;
    private final SeckillProperties properties;

    public SeckillMessagePublisher(
            RabbitTemplate rabbitTemplate,
            SeckillReservationService reservationService,
            SeckillProperties properties) {
        this.rabbitTemplate = rabbitTemplate;
        this.reservationService = reservationService;
        this.properties = properties;
    }

    /**
     * 发送创建订单消息。
     *
     * @return false 表示订单已经进入终态，无需发送；true 表示已交给 RabbitTemplate
     */
    public boolean publishCreateOrder(VoucherOrder order) {
        long attempt = reservationService.markPublishing(order.getId());
        if (attempt < 0) {
            return false;
        }

        CorrelationData correlationData =
                new CorrelationData(EVENT_CREATE_ORDER + ":" + order.getId());
        try {
            rabbitTemplate.convertAndSend(
                    RabbitMqConfig.SECKILL_ORDER_EXCHANGE,
                    RabbitMqConfig.SECKILL_ORDER_ROUTING_KEY,
                    order,
                    message -> {
                        // deliveryMode=PERSISTENT 与 durable queue 配合，Broker 重启后消息仍可恢复。
                        message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                        message.getMessageProperties().setMessageId(order.getId().toString());
                        message.getMessageProperties().setCorrelationId(
                                EVENT_CREATE_ORDER + ":" + order.getId());
                        message.getMessageProperties().setHeader(EVENT_TYPE_HEADER, EVENT_CREATE_ORDER);
                        message.getMessageProperties().setHeader(ORDER_ID_HEADER, order.getId());
                        message.getMessageProperties().setHeader("publishAttempt", attempt);
                        return message;
                    },
                    correlationData
            );
            return true;
        } catch (AmqpException e) {
            // 本地发送异常仍保留 Redis 预占，由恢复任务重投，不会形成永久“扣库存无订单”。
            reservationService.markPublishFailed(order.getId(), "SEND_EXCEPTION:" + e.getMessage());
            throw e;
        }
    }

    /**
     * 把订单放入“无消费者”的等待队列；TTL 到期后由死信路由转入真正的关单队列。
     */
    public void publishOrderTimeout(Long orderId) {
        CorrelationData correlationData =
                new CorrelationData(EVENT_CLOSE_ORDER + ":" + orderId);
        rabbitTemplate.convertAndSend(
                RabbitMqConfig.SECKILL_ORDER_EXCHANGE,
                RabbitMqConfig.SECKILL_ORDER_DELAY_ROUTING_KEY,
                new OrderTimeoutMessage(orderId),
                message -> {
                    message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                    message.getMessageProperties().setMessageId("timeout:" + orderId);
                    message.getMessageProperties().setCorrelationId(
                            EVENT_CLOSE_ORDER + ":" + orderId);
                    message.getMessageProperties().setHeader(EVENT_TYPE_HEADER, EVENT_CLOSE_ORDER);
                    message.getMessageProperties().setHeader(ORDER_ID_HEADER, orderId);
                    // 每条消息设置过期时间，便于以后按券或活动定制超时时长。
                    message.getMessageProperties().setExpiration(
                            Long.toString(properties.getOrderTimeoutMillis()));
                    return message;
                },
                correlationData
        );
    }

    public void handleConfirm(CorrelationData correlationData, boolean ack, String cause) {
        ParsedCorrelation parsed = ParsedCorrelation.parse(correlationData);
        if (parsed == null) {
            log.warn("收到无法识别的 RabbitMQ Confirm，ack={}, cause={}", ack, cause);
            return;
        }
        if (EVENT_CREATE_ORDER.equals(parsed.eventType)) {
            if (ack) {
                reservationService.markPublished(parsed.orderId);
            } else {
                reservationService.markPublishFailed(parsed.orderId, "NACK:" + cause);
                log.error("创建订单消息被 Broker NACK，orderId={}, cause={}", parsed.orderId, cause);
            }
            return;
        }

        // 延迟关单消息还有数据库扫描兜底；这里保留告警，方便定位 RabbitMQ 故障。
        if (!ack) {
            log.error("订单超时消息被 Broker NACK，orderId={}, cause={}", parsed.orderId, cause);
        }
    }

    public void handleReturned(String eventType, Long orderId, String replyText) {
        if (EVENT_CREATE_ORDER.equals(eventType) && orderId != null) {
            reservationService.markPublishFailed(orderId, "RETURNED:" + replyText);
        }
        log.error("RabbitMQ 消息无法路由，eventType={}, orderId={}, replyText={}",
                eventType, orderId, replyText);
    }

    private static class ParsedCorrelation {
        private final String eventType;
        private final Long orderId;

        private ParsedCorrelation(String eventType, Long orderId) {
            this.eventType = eventType;
            this.orderId = orderId;
        }

        private static ParsedCorrelation parse(CorrelationData correlationData) {
            if (correlationData == null || correlationData.getId() == null) {
                return null;
            }
            String[] parts = correlationData.getId().split(":", 2);
            if (parts.length != 2) {
                return null;
            }
            try {
                return new ParsedCorrelation(parts[0], Long.valueOf(parts[1]));
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }
}
