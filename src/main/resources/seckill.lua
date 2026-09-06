-- 秒杀资格预占脚本。
-- 除了扣库存和记录“一人一单”，还把待投递信息写入 Redis。
-- 即使应用在发送 RabbitMQ 消息前宕机，恢复任务也能找到这笔预占并重投。

-- 1. 获取参数
local voucherId = ARGV[1]
local userId = ARGV[2]
local orderId = ARGV[3]
local confirmTimeoutMillis = tonumber(ARGV[4])
local reservationTtlSeconds = tonumber(ARGV[5])

-- 2. 定义 Redis Key
-- 2.1. 库存 Key
local stockKey = 'seckill:stock:' .. voucherId
-- 2.2. 订单 Key
local orderKey = 'seckill:order:' .. voucherId
local beginTimeKey = 'seckill:begin:' .. voucherId
local endTimeKey = 'seckill:end:' .. voucherId
-- 2.3. 资格预占详情，供 Confirm 回调、重投任务和补偿逻辑共同使用
local reservationKey = 'seckill:reservation:' .. orderId
-- 2.4. 等待发布确认的有序集合，score 表示下一次恢复检查时间
local pendingKey = 'seckill:publish:pending'

-- 3. 判断秒杀资格
-- 3.1. 使用 Redis 服务器时间校验活动窗口，避免多台应用服务器时钟不一致。
local redisTime = redis.call('time')
local nowMillis = redisTime[1] * 1000 + math.floor(redisTime[2] / 1000)
local beginTime = tonumber(redis.call('get', beginTimeKey))
local endTime = tonumber(redis.call('get', endTimeKey))
if (not beginTime) or (not endTime) then
    return 5
end
if nowMillis < beginTime then
    return 3
end
if nowMillis > endTime then
    return 4
end

-- 3.2. 判断库存是否充足
local stock = tonumber(redis.call('get', stockKey))
if (not stock) or stock <= 0 then
    -- 库存不存在或库存不足
    return 1
end

-- 3.3. 判断用户是否已经下单
if redis.call('sismember', orderKey, userId) == 1 then
    -- 用户已经下过单
    return 2
end

-- 3.4. 扣减 Redis 库存
redis.call('incrby', stockKey, -1)

-- 3.5. 将用户 ID 记录到当前优惠券的下单集合
redis.call('sadd', orderKey, userId)

-- 3.6. 原子记录这次资格预占。这里与扣库存处于同一个 Lua，避免只扣库存却没有恢复依据。
redis.call('hset', reservationKey,
    'orderId', orderId,
    'voucherId', voucherId,
    'userId', userId,
    'status', 'PENDING',
    'attempts', '0')
redis.call('expire', reservationKey, reservationTtlSeconds)
redis.call('zadd', pendingKey, nowMillis + confirmTimeoutMillis, orderId)

-- 秒杀资格校验通过
return 0
