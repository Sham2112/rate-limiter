local curr_window_key = KEYS[1]
local prev_window_key = KEYS[2]
local limit = tonumber(ARGV[1])
local window = tonumber(ARGV[2])
local redis_time = redis.call('TIME')
local now = (tonumber(redis_time[1]) * 1000) + math.floor(tonumber(redis_time[2]) / 1000)

local curr_window_count = tonumber(redis.call('GET', curr_window_key)) or 0
local prev_window_count = tonumber(redis.call('GET', prev_window_key)) or 0

local time_elapsed_in_current_window = now % window
local overlap_fraction = (window - time_elapsed_in_current_window) / window
local estimate = curr_window_count + (prev_window_count * (overlap_fraction))

if estimate < limit then
    local curr_val = redis.call('INCRBY', curr_window_key, 1)
    if curr_val == 1 then
        redis.call('PEXPIRE', curr_window_key, window * 2)
    end
    return 1
else
    return 0
end