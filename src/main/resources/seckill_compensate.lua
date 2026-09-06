-- RabbitMQ 最终无法处理订单时，归还 Lua 预扣的 Redis 资格。
-- KEYS[1] reservationKey, KEYS[2] stockKey, KEYS[3] orderSetKey,
-- KEYS[4] pendingPublishKey
-- ARGV[1] orderId, ARGV[2] userId, ARGV[3] reason, ARGV[4] reservationTtlSeconds,
-- ARGV[5] removeUser(1/0)

local status = redis.call('hget', KEYS[1], 'status')
if not status then
    return 0
end

-- 补偿必须幂等；已创建的数据库订单也绝不能被发布失败回调误补偿。
if status == 'COMPENSATED' or status == 'CANCELLED' or status == 'CREATED' then
    return 0
end

redis.call('incrby', KEYS[2], 1)
-- 数据库已经存在同一用户同一券的另一订单时，要归还本次预扣库存，
-- 但不能移除已有订单对应的“一人一单”标记。
if ARGV[5] == '1' then
    redis.call('srem', KEYS[3], ARGV[2])
end
redis.call('hset', KEYS[1], 'status', 'COMPENSATED', 'reason', ARGV[3])
redis.call('expire', KEYS[1], ARGV[4])
redis.call('zrem', KEYS[4], ARGV[1])
return 1
