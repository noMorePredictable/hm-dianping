package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.config.SeckillProperties;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.service.SeckillOrderCreateResult;
import com.hmdp.mq.SeckillMessagePublisher;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.Resource;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.time.LocalDateTime;
import com.hmdp.service.SeckillReservation;
import com.hmdp.service.SeckillReservationService;

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
    private SeckillMessagePublisher seckillMessagePublisher;
    @Resource
    private SeckillProperties seckillProperties;
    @Resource
    private SeckillReservationService reservationService;

    @Override
    public Result seckillVoucher(Long voucherId) {
        // 1. 获取当前用户
        Long userId = UserHolder.getUser().getId();

        // 2. 先生成订单 ID，让 Lua 能把“库存预扣”和“待发布记录”一起原子写入 Redis。
        long orderId = redisIdWorker.nextId("order");

        // 3. Lua 原子完成：库存校验、一人一单、预扣库存、保存资格预占记录。
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                userId.toString(),
                Long.toString(orderId),
                Long.toString(seckillProperties.getPublishConfirmTimeoutMillis()),
                Long.toString(seckillProperties.getReservationTtlSeconds())
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
        if (status == 3) {
            return Result.fail("秒杀尚未开始");
        }
        if (status == 4) {
            return Result.fail("秒杀已经结束");
        }
        if (status == 5) {
            return Result.fail("秒杀信息未初始化，请稍后重试");
        }
        if (status != 0) {
            return Result.fail("秒杀失败，请稍后重试");
        }

        // 4. 构造异步订单任务；HTTP 线程不直接访问 MySQL，从而实现削峰。
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);

        // 5. 投递到 RabbitMQ。发送异常/Confirm NACK/Return 都会进入恢复集合，
        // 后台任务重投失败后再补偿 Redis，不会永久占用库存。
        try {
            seckillMessagePublisher.publishCreateOrder(voucherOrder);
        } catch (RuntimeException e) {
            // 资格已经被 Redis 接受，所以这里仍返回订单号；恢复任务会重投或最终补偿。
            log.error("首次发送秒杀订单消息失败，已进入自动恢复，orderId={}", orderId, e);
        }

        // 6. 返回的是“已受理”订单号，数据库订单由 RabbitMQ 消费者异步创建。
        return Result.ok(orderId);
    }

    @Override
    @Transactional
    public SeckillOrderCreateResult createVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        Long voucherId = voucherOrder.getVoucherId();

        // RabbitMQ 是至少一次投递：相同 orderId 再次到达必须直接视为成功。
        if (getById(voucherOrder.getId()) != null) {
            return SeckillOrderCreateResult.IDEMPOTENT_SUCCESS;
        }

        // 1. 数据库再次校验一人一单，作为最终兜底
        long count = query()
                .eq("voucher_id", voucherId)
                .eq("user_id", userId)
                .count();
        if (count > 0) {
            log.error("用户已购买过该优惠券，userId={}, voucherId={}", userId, voucherId);
            return SeckillOrderCreateResult.DUPLICATE_PURCHASE;
        }

        // 2. 扣减数据库库存，并再次校验实时库存，避免超卖
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId)
                .gt("stock", 0)
                .update();
        if (!success) {
            log.error("数据库库存不足，voucherId={}", voucherId);
            return SeckillOrderCreateResult.DB_OUT_OF_STOCK;
        }

        // 3. 保存订单
        if (!save(voucherOrder)) {
            throw new IllegalStateException("订单保存失败");
        }
        return SeckillOrderCreateResult.CREATED;
    }

    @Override
    @Transactional
    public boolean closeUnpaidOrder(Long orderId) {
        VoucherOrder order = getById(orderId);
        if (order == null) {
            return false;
        }
        if (Integer.valueOf(4).equals(order.getStatus())) {
            // 重复的超时消息直接返回成功，后续 Redis Lua 同样是幂等的。
            return true;
        }
        if (!Integer.valueOf(1).equals(order.getStatus())) {
            // 已支付/已核销订单不能被超时任务关闭。
            return false;
        }

        // CAS：只有 status=1 的线程能把订单改成已取消，防止支付与关单并发覆盖。
        boolean changed = update()
                .set("status", 4)
                .eq("id", orderId)
                .eq("status", 1)
                .update();
        if (!changed) {
            VoucherOrder latest = getById(orderId);
            return latest != null && Integer.valueOf(4).equals(latest.getStatus());
        }

        // MySQL 库存与订单状态在同一事务中修改；Redis 在事务提交后由幂等 Lua 回补。
        boolean stockRestored = seckillVoucherService.update()
                .setSql("stock = stock + 1")
                .eq("voucher_id", order.getVoucherId())
                .update();
        if (!stockRestored) {
            throw new IllegalStateException("关闭订单时回补数据库库存失败，orderId=" + orderId);
        }
        return true;
    }

    @Override
    public Result queryOrderStatus(Long orderId) {
        Long currentUserId = UserHolder.getUser().getId();
        VoucherOrder order = getById(orderId);
        if (order != null) {
            if (!currentUserId.equals(order.getUserId())) {
                return Result.fail("无权查看该订单");
            }
            Map<String, Object> data = new HashMap<>();
            data.put("orderId", orderId);
            data.put("status", order.getStatus());
            data.put("stage", "DATABASE_CREATED");
            return Result.ok(data);
        }

        // 数据库尚未创建时，从 Redis 资格预占状态告诉调用方正在发布、重试还是已补偿。
        SeckillReservation reservation = reservationService.find(orderId);
        if (reservation == null || !currentUserId.equals(reservation.getUserId())) {
            return Result.fail("订单不存在");
        }
        Map<String, Object> data = new HashMap<>();
        data.put("orderId", orderId);
        data.put("stage", reservation.getStatus());
        data.put("attempts", reservation.getAttempts());
        data.put("reason", reservation.getReason());
        return Result.ok(data);
    }

    @Override
    @Transactional
    public Result payOrder(Long orderId) {
        Long currentUserId = UserHolder.getUser().getId();
        VoucherOrder order = getById(orderId);
        if (order == null || !currentUserId.equals(order.getUserId())) {
            return Result.fail("订单不存在");
        }
        if (Integer.valueOf(2).equals(order.getStatus())) {
            return Result.ok();
        }
        if (!Integer.valueOf(1).equals(order.getStatus())) {
            return Result.fail("订单已关闭，无法支付");
        }

        // 与关单逻辑使用相反的 CAS：两者都要求旧状态为 1，因此并发时只会成功一个。
        boolean paid = update()
                .set("status", 2)
                .set("pay_time", LocalDateTime.now())
                .eq("id", orderId)
                .eq("user_id", currentUserId)
                .eq("status", 1)
                .update();
        return paid ? Result.ok() : Result.fail("订单状态已变化，请刷新后重试");
    }
}
