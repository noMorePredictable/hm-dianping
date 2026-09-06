-- 秒杀入口的分布式滑动窗口限流。
-- 两个 ZSET 分别限制某张券的总流量和单个用户流量，并在一个 Lua 中原子完成。
-- KEYS[1] globalKey, KEYS[2] userKey
-- ARGV[1] windowMillis, ARGV[2] globalLimit, ARGV[3] userLimit, ARGV[4] requestId

local redisTime = redis.call('time')
local now = redisTime[1] * 1000 + math.floor(redisTime[2] / 1000)
local minScore = now - tonumber(ARGV[1])

redis.call('zremrangebyscore', KEYS[1], 0, minScore)
redis.call('zremrangebyscore', KEYS[2], 0, minScore)

if redis.call('zcard', KEYS[1]) >= tonumber(ARGV[2]) then
    return 1
end
if redis.call('zcard', KEYS[2]) >= tonumber(ARGV[3]) then
    return 2
end

redis.call('zadd', KEYS[1], now, ARGV[4])
redis.call('zadd', KEYS[2], now, ARGV[4])
redis.call('pexpire', KEYS[1], tonumber(ARGV[1]) * 2)
redis.call('pexpire', KEYS[2], tonumber(ARGV[1]) * 2)
return 0
