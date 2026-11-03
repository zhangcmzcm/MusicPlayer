if #KEYS ~= 5 or #ARGV ~= 4 then return {2,0,0,0} end
if not canonical(ARGV[1], '2147483647') or ARGV[1] == '0' or #ARGV[2] < 1 or #ARGV[2] > 480
  or string.find(ARGV[2], '[%z\r\n]') or not canonical(ARGV[3], '9223372036854775807') or not digest(ARGV[4]) then return {2,0,0,0} end
local now = now_ms()
local cooldown = hash(KEYS[1], {[1]=true})
local daily = counter(KEYS[2], 10, now, true)
local ip = counter(KEYS[3], 5, now, false)
local pending = state(KEYS[4], true, now)
local active = state(KEYS[5], false, now)
if cooldown == false or daily == false or ip == false or pending == false or active == false then return {2,0,0,0} end
if cooldown then
  local expiry = millis(cooldown.resetAtMs)
  if not exact(cooldown, {'resetAtMs'}) or not expiry or expiry <= now or expiry > now + 60000
    or redis.call('PEXPIRETIME', KEYS[1]) ~= expiry then return {2,0,0,0} end
end
if (pending and (pending.userId ~= ARGV[1] or not cooldown or pending.leaseUntilMs ~= cooldown.resetAtMs))
  or (active and active.userId ~= ARGV[1]) then return {2,0,0,0} end
local wait = cooldown and tonumber(cooldown.resetAtMs) - now or 0
if daily and tonumber(daily.count) == 10 then wait = math.max(wait, tonumber(daily.resetAtMs) - now) end
if ip and tonumber(ip.count) == 5 then wait = math.max(wait, tonumber(ip.resetAtMs) - now) end
if wait > 0 then return {1,math.ceil(wait / 1000),0,0} end
local lease, expires, day_end = now + 60000, now + 300000, utc_day_end(now)
redis.call('HSET', KEYS[1], 'resetAtMs', decimal(lease))
redis.call('PEXPIREAT', KEYS[1], lease)
redis.call('HSET', KEYS[2], 'count', decimal(daily and tonumber(daily.count) + 1 or 1), 'resetAtMs', decimal(day_end))
redis.call('PEXPIREAT', KEYS[2], day_end)
local ip_end = ip and tonumber(ip.resetAtMs) or lease
redis.call('HSET', KEYS[3], 'count', decimal(ip and tonumber(ip.count) + 1 or 1), 'resetAtMs', decimal(ip_end))
redis.call('PEXPIREAT', KEYS[3], ip_end)
redis.call('HSET', KEYS[4], 'state','PREPARING','challengeDigest',ARGV[4],'userId',ARGV[1],
  'email',ARGV[2],'tokenVersion',ARGV[3],'reservedAtMs',decimal(now),'expiresAtMs',decimal(expires),'leaseUntilMs',decimal(lease))
redis.call('PEXPIREAT', KEYS[4], lease)
return {0,now,expires,lease}
