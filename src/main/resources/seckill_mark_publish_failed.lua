-- Return/NACK/本地发送异常统一进入待恢复集合。
-- final 状态不会被异步回调覆盖。
-- KEYS[1] reservationKey, KEYS[2] pendingPublishKey
-- ARGV[1] orderId, ARGV[2] nextRetryMillis, ARGV[3] reason,
-- ARGV[4] reservationTtlSeconds
local status = redis.call('hget', KEYS[1], 'status')
if not status then
    return 0
end
if status == 'CREATED' or status == 'COMPENSATED' or status == 'CANCELLED' then
    return 0
end
redis.call('hset', KEYS[1], 'status', 'PUBLISH_FAILED', 'reason', ARGV[3])
redis.call('expire', KEYS[1], ARGV[4])
redis.call('zadd', KEYS[2], ARGV[2], ARGV[1])
return 1
