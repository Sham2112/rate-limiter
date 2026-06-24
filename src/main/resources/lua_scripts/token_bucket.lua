local key = KEYS[1]
local capacity = tonumber(ARGV[1])
local refill_rate = tonumber(ARGV[2])   -- tokens per millisecond
local ttl_ms = tonumber(ARGV[3])

local redis_time = redis.call('TIME')
local now_ms = (tonumber(redis_time[1]) * 1000) + math.floor(tonumber(redis_time[2]) / 1000)

local tokens = tonumber(redis.call('HGET', key, 'tokens'))
local last_refill = tonumber(redis.call('HGET', key, 'last_refill'))

if not tokens then
    tokens = capacity
    last_refill = now_ms
else
    local refill = math.floor((now_ms - last_refill) * refill_rate)
    if refill > 0 then
        tokens = math.min(capacity, tokens + refill)
        last_refill = last_refill + math.floor(refill / refill_rate)
    end
end

local allowed = 0
if tokens >= 1 then
    tokens = tokens - 1
    allowed = 1
end

redis.call('HSET', key, 'tokens', tokens, 'last_refill', last_refill)
redis.call('PEXPIRE', key, ttl_ms)
return allowed
