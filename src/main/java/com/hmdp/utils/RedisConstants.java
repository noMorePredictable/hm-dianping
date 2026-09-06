package com.hmdp.utils;

public class RedisConstants {
    public static final String LOGIN_CODE_KEY = "login:code:";
    public static final Long LOGIN_CODE_TTL = 2L;
    public static final String LOGIN_USER_KEY = "login:token:";
    public static final Long LOGIN_USER_TTL = 36000L;

    public static final Long CACHE_NULL_TTL = 2L;

    public static final Long CACHE_SHOP_TTL = 30L;
    public static final String CACHE_SHOP_KEY = "cache:shop:";
    public static final String CACHE_SHOP_TYPE_KEY = "cache:shopType:list";
    public static final Long CACHE_SHOP_TYPE_TTL = 30L;

    public static final String LOCK_SHOP_KEY = "lock:shop:";
    public static final Long LOCK_SHOP_TTL = 10L;

    public static final String SECKILL_STOCK_KEY = "seckill:stock:";
    public static final String SECKILL_BEGIN_TIME_KEY = "seckill:begin:";
    public static final String SECKILL_END_TIME_KEY = "seckill:end:";
    /** 秒杀成功用户集合：seckill:order:{voucherId}。 */
    public static final String SECKILL_ORDER_KEY = "seckill:order:";
    /** 秒杀资格预占记录：seckill:reservation:{orderId}。 */
    public static final String SECKILL_RESERVATION_KEY = "seckill:reservation:";
    /** 等待 RabbitMQ 确认或等待重投的订单，score 是下一次检查时间。 */
    public static final String SECKILL_PUBLISH_PENDING_KEY = "seckill:publish:pending";
    /** 同一订单的“消费建单 / 死信补偿 / 发布恢复”必须串行，避免提交与补偿并发。 */
    public static final String SECKILL_LIFECYCLE_LOCK_KEY = "lock:seckill:lifecycle:";
    public static final String BLOG_LIKED_KEY = "blog:liked:";
    public static final String FEED_KEY = "feed:";
    public static final String SHOP_GEO_KEY = "shop:geo:";
    public static final String USER_SIGN_KEY = "sign:";
}
