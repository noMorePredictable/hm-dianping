package com.hmdp.mq;

import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;

@Component
public class RabbitMqProducerCallback implements RabbitTemplate.ConfirmCallback, RabbitTemplate.ReturnsCallback {

    @Resource
    private RabbitTemplate rabbitTemplate;
    @Resource
    private SeckillMessagePublisher seckillMessagePublisher;

    @PostConstruct
    private void init() {
        rabbitTemplate.setConfirmCallback(this);
        rabbitTemplate.setReturnsCallback(this);
    }

    @Override
    public void confirm(CorrelationData correlationData, boolean ack, String cause) {
        // Confirm 表示消息是否到达 Exchange，不等于业务订单已经创建。
        // 缓存广播等非关键消息没有 CorrelationData，不进入秒杀状态机。
        if (correlationData == null) {
            return;
        }
        seckillMessagePublisher.handleConfirm(correlationData, ack, cause);
    }

    @Override
    public void returnedMessage(ReturnedMessage returned) {
        Object eventType = returned.getMessage().getMessageProperties()
                .getHeaders().get(SeckillMessagePublisher.EVENT_TYPE_HEADER);
        Object orderId = returned.getMessage().getMessageProperties()
                .getHeaders().get(SeckillMessagePublisher.ORDER_ID_HEADER);
        seckillMessagePublisher.handleReturned(
                eventType == null ? null : eventType.toString(),
                orderId == null ? null : Long.valueOf(orderId.toString()),
                returned.getReplyText()
        );
    }
}
