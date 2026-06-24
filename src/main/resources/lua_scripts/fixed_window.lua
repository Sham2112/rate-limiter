local key = KEYS[1]
local limit = tonumber(ARGV[1])
local ttl_ms = tonumber(ARGV[2])

local counter = redis.call('GET', key)
if not counter then
    redis.call('PSETEX', key, ttl_ms, 0)
    counter = 0
else 
    counter = tonumber(counter)
end

if (counter < limit) then
    redis.call('INCRBY', key, 1)
    return 1
else 
    return 0
end