package com.hmdp.service.impl;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Voucher;
import com.hmdp.mapper.VoucherMapper;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.Resource;
import java.util.List;
import java.time.ZoneId;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static com.hmdp.utils.RedisConstants.SECKILL_BEGIN_TIME_KEY;
import static com.hmdp.utils.RedisConstants.SECKILL_END_TIME_KEY;
import static com.hmdp.utils.RedisConstants.SECKILL_STOCK_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherServiceImpl extends ServiceImpl<VoucherMapper, Voucher> implements IVoucherService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public Result queryVoucherOfShop(Long shopId) {
        // 查询优惠券信息
        List<Voucher> vouchers = getBaseMapper().queryVoucherOfShop(shopId);
        // 返回结果
        return Result.ok(vouchers);
    }

    @Override
    @Transactional
    public void addSeckillVoucher(Voucher voucher) {
        // 保存优惠券
        save(voucher);
        // 保存秒杀信息
        SeckillVoucher seckillVoucher = new SeckillVoucher();
        seckillVoucher.setVoucherId(voucher.getId());
        seckillVoucher.setStock(voucher.getStock());
        seckillVoucher.setBeginTime(voucher.getBeginTime());
        seckillVoucher.setEndTime(voucher.getEndTime());
        seckillVoucherService.save(seckillVoucher);
        // 数据库事务真正提交后再预热 Redis，避免 DB 回滚但 Redis 中出现一张不存在的券。
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                // 秒杀请求只访问 Redis：库存和活动时间都要预热，Lua 使用 Redis TIME 校验窗口。
                stringRedisTemplate.opsForValue().set(
                        SECKILL_STOCK_KEY + voucher.getId(), voucher.getStock().toString());
                stringRedisTemplate.opsForValue().set(
                        SECKILL_BEGIN_TIME_KEY + voucher.getId(),
                        Long.toString(voucher.getBeginTime().atZone(ZoneId.systemDefault())
                                .toInstant().toEpochMilli()));
                stringRedisTemplate.opsForValue().set(
                        SECKILL_END_TIME_KEY + voucher.getId(),
                        Long.toString(voucher.getEndTime().atZone(ZoneId.systemDefault())
                                .toInstant().toEpochMilli()));
            }
        });
    }
}
