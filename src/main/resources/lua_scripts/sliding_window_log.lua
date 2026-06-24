local key = KEYS[1]
local limit = tonumber(ARGV[1])
local window = tonumber(ARGV[2])
local request_token = ARGV[3]
local ttl_ms = tonumber(ARGV[4])

local redis_time = redis.call('TIME')
local now_ms = (tonumber(redis_time[1]) * 1000) + math.floor(tonumber(redis_time[2]) / 1000)

local clear_before = now_ms - window

redis.call('ZREMRANGEBYSCORE', key, 0, clear_before)

local count = tonumber(redis.call('ZCARD', key))


if count < limit then
    redis.call('ZADD', key, now_ms, request_token)
    redis.call('PEXPIRE', key, ttl_ms)
    return 1
else
    return 0
end