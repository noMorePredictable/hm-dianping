package com.hmdp.service;

import com.hmdp.entity.SeckillVoucher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.hmdp.utils.RedisConstants.SECKILL_BEGIN_TIME_KEY;
import static com.hmdp.utils.RedisConstants.SECKILL_END_TIME_KEY;
import static com.hmdp.utils.RedisConstants.SECKILL_STOCK_KEY;

/** 启动时补齐旧数据的秒杀时间窗口，但不覆盖 Redis 中已经发生预扣的库存。 */
@Component
public class SeckillVoucherInitializer {

    private static final Logger log = LoggerFactory.getLogger(SeckillVoucherInitializer.class);

    private final ISeckillVoucherService voucherService;
    private final StringRedisTemplate stringRedisTemplate;
    private final AtomicBoolean initialized = new AtomicBoolean(false);

    public SeckillVoucherInitializer(
            ISeckillVoucherService voucherService,
            StringRedisTemplate stringRedisTemplate) {
        this.voucherService = voucherService;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Scheduled(
            initialDelayString = "${hmdp.seckill.initializer-retry-millis:30000}",
            fixedDelayString = "${hmdp.seckill.initializer-retry-millis:30000}")
    public void initialize() {
        if (initialized.get()) {
            return;
        }
        try {
            List<SeckillVoucher> vouchers = voucherService.list();
            for (SeckillVoucher voucher : vouchers) {
                if (voucher.getBeginTime() == null || voucher.getEndTime() == null) {
                    continue;
                }
                // setIfAbsent 很关键：应用重启不能用数据库初始值覆盖 Redis 的实时预扣库存。
                stringRedisTemplate.opsForValue().setIfAbsent(
                        SECKILL_STOCK_KEY + voucher.getVoucherId(), voucher.getStock().toString());
                stringRedisTemplate.opsForValue().set(
                        SECKILL_BEGIN_TIME_KEY + voucher.getVoucherId(), toMillis(voucher.getBeginTime()));
                stringRedisTemplate.opsForValue().set(
                        SECKILL_END_TIME_KEY + voucher.getVoucherId(), toMillis(voucher.getEndTime()));
            }
            initialized.set(true);
            log.info("秒杀券 Redis 时间窗口初始化完成，voucherCount={}", vouchers.size());
        } catch (RuntimeException e) {
            // 就绪事件中抛异常会导致整个应用退出；让定时任务在依赖恢复后自动补初始化。
            log.error("秒杀券 Redis 初始化失败，30 秒后重试", e);
        }
    }

    private String toMillis(java.time.LocalDateTime time) {
        return Long.toString(time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());
    }
}
