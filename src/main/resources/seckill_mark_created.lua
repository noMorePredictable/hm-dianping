-- 数据库订单提交成功后，把预占推进到 CREATED 并清除发布恢复索引。
-- KEYS[1] reservationKey, KEYS[2] pendingPublishKey
-- ARGV[1] orderId, ARGV[2] reservationTtlSeconds
local status = redis.call('hget', KEYS[1], 'status')
if not status then
    return 0
end
-- 最终补偿和取消不能被迟到的消息回调反向覆盖。
if status == 'COMPENSATED' or status == 'CANCELLED' then
    return -1
end
redis.call('hset', KEYS[1], 'status', 'CREATED')
redis.call('expire', KEYS[1], ARGV[2])
redis.call('zrem', KEYS[2], ARGV[1])
return 1
