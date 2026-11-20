-- One atomic fixed window per key, beginning with the first admitted request.
local ttl = redis.call('PTTL', KEYS[1])
if ttl < 0 then
    redis.call('SET', KEYS[1], '1', 'PX', ARGV[2])
    return 0
end
local current = tonumber(redis.call('GET', KEYS[1]) or '0')
if current >= tonumber(ARGV[1]) then
    return math.max(1, math.ceil(ttl / 1000))
end
redis.call('INCR', KEYS[1])
return 0
