-- 未支付订单关闭后，同时归还 Redis 库存和用户购买资格。
-- reservation 的状态机保证重复超时消息不会重复增加库存。
-- KEYS[1] reservationKey, KEYS[2] stockKey, KEYS[3] orderSetKey,
-- KEYS[4] pendingPublishKey
-- ARGV[1] orderId, ARGV[2] userId, ARGV[3] reservationTtlSeconds

local status = redis.call('hget', KEYS[1], 'status')
if status == 'CANCELLED' then
    return 0
end
if status ~= 'CREATED' then
    return -1
end

redis.call('incrby', KEYS[2], 1)
redis.call('srem', KEYS[3], ARGV[2])
redis.call('hset', KEYS[1], 'status', 'CANCELLED', 'reason', 'ORDER_TIMEOUT')
redis.call('expire', KEYS[1], ARGV[3])
redis.call('zrem', KEYS[4], ARGV[1])
return 1
