-- Confirm ACK 与 Return 回调可能先后到达。
-- 如果 Return 已把状态改成 PUBLISH_FAILED，稍后的 ACK 不能覆盖失败状态。
-- KEYS[1] reservationKey, KEYS[2] pendingPublishKey; ARGV[1] orderId
local status = redis.call('hget', KEYS[1], 'status')
if not status then
    return 0
end
if status == 'PUBLISH_FAILED' or status == 'COMPENSATED' or
   status == 'CREATED' or status == 'CANCELLED' then
    return 0
end
redis.call('hset', KEYS[1], 'status', 'PUBLISHED')
redis.call('zrem', KEYS[2], ARGV[1])
return 1
