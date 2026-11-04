if #KEYS ~= 7 or #ARGV ~= 5 then return {2,0,0,0} end
if not canonical(ARGV[1], '2147483647') or ARGV[1] == '0' or #ARGV[2] > 480
  or #ARGV[3] < 1 or #ARGV[3] > 480 or string.find(ARGV[2], '[%z\r\n]') or string.find(ARGV[3], '[%z\r\n]')
  or not canonical(ARGV[4], '9223372036854775807') or not digest(ARGV[5]) then return {2,0,0,0} end
local now = now_ms()
local function cooldown(key)
  local h = hash(key, {[1]=true})
  if not h then return h end
  local expiry = millis(h.resetAtMs)
  if not exact(h, {'resetAtMs'}) or not expiry or expiry <= now or expiry > now + 60000
    or redis.call('PEXPIRETIME', key) ~= expiry then return false end
  return h
end
local uc, ud = cooldown(KEYS[1]), counter(KEYS[2], 10, now, true)
local tc, td = cooldown(KEYS[3]), counter(KEYS[4], 10, now, true)
local ip = counter(KEYS[5], 5, now, false)
local pending, active = state(KEYS[6], true, now), state(KEYS[7], false, now)
if uc == false or ud == false or tc == false or td == false or ip == false or pending == false or active == false
  or (pending and pending.userId ~= ARGV[1]) or (active and active.userId ~= ARGV[1]) then return {2,0,0,0} end
local wait = 0
if uc then wait = math.max(wait, tonumber(uc.resetAtMs) - now) end
if tc then wait = math.max(wait, tonumber(tc.resetAtMs) - now) end
if ud and tonumber(ud.count) == 10 then wait = math.max(wait, tonumber(ud.resetAtMs) - now) end
if td and tonumber(td.count) == 10 then wait = math.max(wait, tonumber(td.resetAtMs) - now) end
if ip and tonumber(ip.count) == 5 then wait = math.max(wait, tonumber(ip.resetAtMs) - now) end
if wait > 0 then return {1,math.ceil(wait / 1000),0,0} end
if pending then return {3,0,0,0} end
local lease, expires, day_end = now + 60000, now + 300000, utc_day_end(now)
for _, key in ipairs({KEYS[1], KEYS[3]}) do
  redis.call('HSET', key, 'resetAtMs', decimal(lease))
  redis.call('PEXPIREAT', key, lease)
end
local function increment(key, h, ending)
  redis.call('HSET', key, 'count', decimal(h and tonumber(h.count) + 1 or 1), 'resetAtMs', decimal(ending))
  redis.call('PEXPIREAT', key, ending)
end
increment(KEYS[2], ud, day_end)
increment(KEYS[4], td, day_end)
increment(KEYS[5], ip, ip and tonumber(ip.resetAtMs) or lease)
redis.call('HSET', KEYS[6], 'state','PREPARING','challengeDigest',ARGV[5],'userId',ARGV[1],
  'currentEmail',ARGV[2],'newEmail',ARGV[3],'tokenVersion',ARGV[4],
  'reservedAtMs',decimal(now),'expiresAtMs',decimal(expires),'leaseUntilMs',decimal(lease))
redis.call('PEXPIREAT', KEYS[6], lease)
return {0,now,expires,lease}
