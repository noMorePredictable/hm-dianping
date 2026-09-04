-- 1. 获取参数
-- 1.1. 优惠券 ID
local voucherId = ARGV[1]
-- 1.2. 用户 ID
local userId = ARGV[2]

-- 2. 定义 Redis Key
-- 2.1. 库存 Key
local stockKey = 'seckill:stock:' .. voucherId
-- 2.2. 订单 Key
local orderKey = 'seckill:order:' .. voucherId

-- 3. 判断秒杀资格
-- 3.1. 判断库存是否充足
local stock = tonumber(redis.call('get', stockKey))
if (not stock) or stock <= 0 then
    -- 库存不存在或库存不足
    return 1
end

-- 3.2. 判断用户是否已经下单
if redis.call('sismember', orderKey, userId) == 1 then
    -- 用户已经下过单
    return 2
end

-- 3.3. 扣减 Redis 库存
redis.call('incrby', stockKey, -1)

-- 3.4. 将用户 ID 记录到当前优惠券的下单集合
redis.call('sadd', orderKey, userId)

-- 秒杀资格校验通过
return 0
