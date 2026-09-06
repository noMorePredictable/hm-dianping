package com.hmdp.service;

/**
 * 消费创建订单消息的业务结果。
 *
 * <p>“重复消息”不是异常：RabbitMQ 的可靠语义是至少一次，消费者必须
 * 把同一订单的再次投递当作成功。真正的库存不一致才需要补偿。</p>
 */
public enum SeckillOrderCreateResult {
    CREATED,
    IDEMPOTENT_SUCCESS,
    DUPLICATE_PURCHASE,
    DB_OUT_OF_STOCK
}
