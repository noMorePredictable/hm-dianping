package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.config.RabbitMqConfig;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.Collections;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    private static final Logger log = LoggerFactory.getLogger(VoucherOrderServiceImpl.class);
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RabbitTemplate rabbitTemplate;

    @Override
    public Result seckillVoucher(Long voucherId) {
        // 1. 获取当前用户
        Long userId = UserHolder.getUser().getId();

        // 2. 执行 Lua 脚本，原子判断库存和一人一单，并预扣减 Redis 库存
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                userId.toString()
        );
        if (result == null) {
            return Result.fail("秒杀失败，请稍后重试");
        }

        int status = result.intValue();
        if (status == 1) {
            return Result.fail("库存不足！");
        }
        if (status == 2) {
            return Result.fail("不允许重复下单");
        }
        if (status != 0) {
            return Result.fail("秒杀失败，请稍后重试");
        }

        // 3. 创建订单任务
        long orderId = redisIdWorker.nextId("order");
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);

        // 4. 将订单作为持久化消息发送给 RabbitMQ
        CorrelationData correlationData = new CorrelationData(String.valueOf(orderId));
        try {
            rabbitTemplate.convertAndSend(
                    RabbitMqConfig.SECKILL_ORDER_EXCHANGE,
                    RabbitMqConfig.SECKILL_ORDER_ROUTING_KEY,
                    voucherOrder,
                    message -> {
                        message.getMessageProperties().setMessageId(String.valueOf(orderId));
                        message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                        return message;
                    },
                    correlationData
            );
        } catch (AmqpException e) {
            log.error("发送秒杀订单消息失败，orderId={}", orderId, e);
            return Result.fail("系统繁忙，请稍后重试");
        }

        // 5. 发送完成后立即返回，数据库由 RabbitMQ 消费者异步写入
        return Result.ok(orderId);
    }

    @Override
    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        Long voucherId = voucherOrder.getVoucherId();

        // 1. 数据库再次校验一人一单，作为最终兜底
        Integer count = query()
                .eq("voucher_id", voucherId)
                .eq("user_id", userId)
                .count();
        if (count > 0) {
            log.error("用户已购买过该优惠券，userId={}, voucherId={}", userId, voucherId);
            return;
        }

        // 2. 扣减数据库库存，并再次校验实时库存，避免超卖
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId)
                .gt("stock", 0)
                .update();
        if (!success) {
            log.error("数据库库存不足，voucherId={}", voucherId);
            return;
        }

        // 3. 保存订单
        if (!save(voucherOrder)) {
            throw new IllegalStateException("订单保存失败");
        }
    }
}
