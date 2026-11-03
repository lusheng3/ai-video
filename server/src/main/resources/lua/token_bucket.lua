-- =========================================================================
-- 基于 Redis Hash 结构的惰性计算令牌桶限流脚本（100%对齐《项目拷打》PDF第29~31页）
-- Key 格式：KEYS[1] = rate_limiter:{api_path}:{userId}
-- Hash 字段：
--   tokens    : 桶里当前剩下的令牌数量
--   last_time : 上次更新令牌的时间戳（毫秒）
-- 参数说明：
--   ARGV[1] = rate           (令牌生成速率，个/秒)
--   ARGV[2] = capacity       (桶最大容量)
--   ARGV[3] = now            (当前时间戳，毫秒)
--   ARGV[4] = expire_seconds (Key空闲过期时间，秒，默认60s自动回收冷数据)
-- 返回值：
--   1 = 获取令牌成功（放行）
--   0 = 令牌不足（触发HTTP 429限流）
-- =========================================================================

local key = KEYS[1]
local rate = tonumber(ARGV[1])
local capacity = tonumber(ARGV[2])
local now = tonumber(ARGV[3])
local expire_seconds = tonumber(ARGV[4])

-- 第一步：取出桶状态，通过惰性计算算出这段时间应该补充多少令牌
local bucket = redis.call('HMGET', key, 'tokens', 'last_time')
local tokens = tonumber(bucket[1])
local last_time = tonumber(bucket[2])

if tokens == nil or last_time == nil then
    -- 首次访问或Key过期重建：初始化为满桶状态
    tokens = capacity
    last_time = now
else
    -- 计算当前时间与上次时间的差值（毫秒转秒），乘以生成速率得到新生成的令牌数
    local delta_ms = math.max(0, now - last_time)
    local generated_tokens = (delta_ms / 1000.0) * rate
    tokens = math.min(capacity, tokens + generated_tokens)
end

-- 第二步 & 第三步：判断令牌是否大于等于 1，并执行原子扣减
if tokens >= 1.0 then
    tokens = tokens - 1.0
    redis.call('HMSET', key, 'tokens', tostring(tokens), 'last_time', tostring(now))
    redis.call('EXPIRE', key, expire_seconds)
    return 1
else
    -- 令牌不够：不扣减，直接返回 0（限流），同时刷新过期时间
    redis.call('HMSET', key, 'tokens', tostring(tokens), 'last_time', tostring(now))
    redis.call('EXPIRE', key, expire_seconds)
    return 0
end
