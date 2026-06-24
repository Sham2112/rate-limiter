local key = KEYS[1]
local capacity = tonumber(ARGV[1])
local leak_interval_ms = tonumber(ARGV[2])
local leak_amount = tonumber(ARGV[3]) 
local ttl_ms = tonumber(ARGV[4])

local data = redis.call('HMGET', key, 'tokens', 'last_leak')
local tokens = tonumber(data[1])
local last_leak = tonumber(data[2])

local redis_time = redis.call('TIME')
local now_ms = (tonumber(redis_time[1]) * 1000) + math.floor(tonumber(redis_time[2]) / 1000)

if not tokens or not last_leak then
    tokens = 0
    last_leak = now_ms
else
    local elapsed_ms = now_ms - last_leak
    if elapsed_ms >= leak_interval_ms then
        local intervals_passed = math.floor(elapsed_ms / leak_interval_ms)
        local total_leak = intervals_passed * leak_amount
        
        tokens = math.max(0, tokens - total_leak)
        
        last_leak = last_leak + (intervals_passed * leak_interval_ms)
    end
end

local allowed = 0
if tokens < capacity then
    tokens = tokens + 1
    allowed = 1
end

redis.call('HSET', key, 'tokens', tokens, 'last_leak', last_leak)
redis.call('PEXPIRE', key, ttl_ms)
return allowed
