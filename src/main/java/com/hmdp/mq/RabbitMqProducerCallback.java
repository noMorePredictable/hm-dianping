package com.hmdp.mq;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;

@Component
public class RabbitMqProducerCallback implements RabbitTemplate.ConfirmCallback, RabbitTemplate.ReturnCallback {

    private static final Logger log = LoggerFactory.getLogger(RabbitMqProducerCallback.class);

    @Resource
    private RabbitTemplate rabbitTemplate;

    @PostConstruct
    private void init() {
        rabbitTemplate.setConfirmCallback(this);
        rabbitTemplate.setReturnCallback(this);
    }

    @Override
    public void confirm(CorrelationData correlationData, boolean ack, String cause) {
        if (ack) {
            return;
        }

        String orderId = correlationData == null ? "unknown" : correlationData.getId();
        log.error("RabbitMQ 未确认秒杀订单消息，orderId={}, cause={}", orderId, cause);
    }

    @Override
    public void returnedMessage(
            Message message,
            int replyCode,
            String replyText,
            String exchange,
            String routingKey) {
        log.error(
                "RabbitMQ 秒杀订单消息无法路由，messageId={}, replyCode={}, replyText={}, exchange={}, routingKey={}",
                message.getMessageProperties().getMessageId(),
                replyCode,
                replyText,
                exchange,
                routingKey
        );
    }
}
