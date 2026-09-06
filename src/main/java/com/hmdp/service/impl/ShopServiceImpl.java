package com.hmdp.service.impl;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.hmdp.service.ShopCacheService;
import org.springframework.stereotype.Service;
import org.redisson.api.RLock;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 店铺业务：数据库负责事实数据，ShopCacheService 负责 L1/L2 查询与失效。 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    private final ShopCacheService shopCacheService;

    public ShopServiceImpl(ShopCacheService shopCacheService) {
        this.shopCacheService = shopCacheService;
    }

    @Override
    public Result queryById(Long id) {
        Shop shop = shopCacheService.queryById(id);
        return shop == null ? Result.fail("店铺不存在") : Result.ok(shop);
    }

    @Override
    @Transactional
    public Result saveShop(Shop shop) {
        if (!save(shop)) {
            return Result.fail("新增店铺失败");
        }
        // 新 ID 必须在事务提交后加入 Bloom，避免数据库回滚却让过滤器认为它存在。
        afterCommit(() -> shopCacheService.evictAndBroadcast(shop.getId()));
        return Result.ok(shop.getId());
    }

    @Override
    @Transactional
    public Result update(Shop shop) {
        if (shop.getId() == null) {
            return Result.fail("店铺id不能为空");
        }
        RLock writeLock = shopCacheService.getWriteLock(shop.getId());
        writeLock.lock();
        boolean unlockRegistered = false;
        try {
            if (!updateById(shop)) {
                return Result.fail("店铺不存在或更新失败");
            }

            // 锁一直持有到事务真正结束。提交成功才删缓存；回滚只释放锁。
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    shopCacheService.evictAndBroadcast(shop.getId());
                }

                @Override
                public void afterCompletion(int status) {
                    if (writeLock.isHeldByCurrentThread()) {
                        writeLock.unlock();
                    }
                }
            });
            unlockRegistered = true;
            return Result.ok();
        } finally {
            // 在注册事务回调前发生异常时也不能遗留分布式锁。
            if (!unlockRegistered && writeLock.isHeldByCurrentThread()) {
                writeLock.unlock();
            }
        }
    }

    private void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }
}
