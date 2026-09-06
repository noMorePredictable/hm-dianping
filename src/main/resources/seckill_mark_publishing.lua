-- 每次真正发送 RabbitMQ 前增加 attempts，并把下一次恢复时间放入 ZSET。
-- final 状态不允许重新发布，避免已创建或已取消的订单再次进入队列。
-- KEYS[1] reservationKey, KEYS[2] pendingPublishKey
-- ARGV[1] orderId, ARGV[2] nextCheckMillis, ARGV[3] reservationTtlSeconds
local status = redis.call('hget', KEYS[1], 'status')
if not status then
    return -1
end
if status == 'PUBLISHED' or status == 'CREATED' or
   status == 'COMPENSATED' or status == 'CANCELLED' then
    return -1
end
local attempts = redis.call('hincrby', KEYS[1], 'attempts', 1)
redis.call('hset', KEYS[1], 'status', 'PUBLISHING')
redis.call('expire', KEYS[1], ARGV[3])
redis.call('zadd', KEYS[2], ARGV[2], ARGV[1])
return attempts
