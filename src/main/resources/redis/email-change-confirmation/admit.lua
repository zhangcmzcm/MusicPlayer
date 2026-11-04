if #KEYS ~= 1 or #ARGV ~= 1 or not canonical(ARGV[1], '2147483647') or ARGV[1] == '0' then return {2,0} end
local now = now_ms()
local quota = hash(KEYS[1], {[2]=true})
if quota == false then return {2,0} end
if quota then
  local expiry = millis(quota.resetAtMs)
  if not exact(quota, {'count','resetAtMs'}) or not canonical(quota.count, '10') or quota.count == '0'
    or not expiry or expiry <= now or expiry > now + 900000 or redis.call('PEXPIRETIME', KEYS[1]) ~= expiry then return {2,0} end
  if quota.count == '10' then return {1,math.ceil((expiry - now)/1000)} end
  redis.call('HINCRBY', KEYS[1], 'count', 1)
else
  redis.call('HSET', KEYS[1], 'count','1','resetAtMs',decimal(now + 900000))
  redis.call('PEXPIREAT', KEYS[1], now + 900000)
end
return {0,0}
