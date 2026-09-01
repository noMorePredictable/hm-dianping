package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    public Result queryById(Long id){
        //缓存穿透
        //Shop shop = queryWithPassThrough(id);
        //互斥锁解决缓存击穿
        Shop shop = queryWithMutex(id);
        //返回
        return Result.ok(shop);
    }
    public Shop queryWithMutex(Long id){
        String key = "cache:shop:" + id;
        //1.从redis查找
        String shopJson = stringRedisTemplate.opsForValue().get("cache:shop:" + id);
        //2.判断存在与否
        if (StrUtil.isNotBlank(shopJson)) {
            //3.存在返回
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        if (shopJson!=null) {
            return null;
        }
        //4.实现缓存重建
        // 4.1获取互斥锁
        String lockkey = "lock:shop:" + id;
        Shop shop = null;
        try {
            boolean islock = trylock(lockkey);
            //4.2判断是否获取成功
            if (islock) {
                //4.2.1失败，休眠，重试
                Thread.sleep(50);
                return queryWithMutex(id);
            }


            // 不存在，查数据库
            shop = getById(id);
            //5.不存在，返回错误
            if (shop == null) {
                //将空值写入redis
                stringRedisTemplate.opsForValue().set("cache:shop:" + id,"",2L, TimeUnit.MINUTES);
                return null;
            }
            //6.存在，写入redis
            stringRedisTemplate.opsForValue().set("cache:shop:" + id, JSONUtil.toJsonStr(shop),30L, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }finally {
            //释放互斥锁
            unlock(lockkey);
        }
        //7.返回
        return shop;
    }
    public Shop queryWithPassThrough(Long id){
        String key = "cache:shop:" + id;
        //1.从redis查找
        String shopJson = stringRedisTemplate.opsForValue().get("cache:shop:" + id);
        //2.判断存在与否
        if (StrUtil.isNotBlank(shopJson)) {
            //3.存在返回
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        if (shopJson!=null) {
            return null;
        }
        //4.不存在，查数据库
        Shop shop = getById(id);
        //5.不存在，返回错误
        if (shop == null) {
            //将空值写入redis
            stringRedisTemplate.opsForValue().set("cache:shop:" + id,"",2L, TimeUnit.MINUTES);
            return null;
        }
        //6.存在，写入redis
        stringRedisTemplate.opsForValue().set("cache:shop:" + id, JSONUtil.toJsonStr(shop),30L, TimeUnit.MINUTES);
        //7.返回
        return shop;
    }
    private boolean trylock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }
    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }
    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if(id==null){
            return Result.fail("店铺id不能为空");
        }
        updateById(shop);
        stringRedisTemplate.delete( "cache:shop:" + id );
        return Result.ok();
    }
}
