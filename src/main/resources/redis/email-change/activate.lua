if #KEYS ~= 2 or #ARGV ~= 5 then return {2} end
local now = now_ms()
local h, active = state(KEYS[1], true, now), state(KEYS[2], false, now)
if h == false or active == false or (active and active.userId ~= ARGV[1]) then return {2} end
if not owner(h) or h.state ~= 'PREPARED' then return {1} end
redis.call('HSET', KEYS[2], 'state','ACTIVE','challengeDigest',h.challengeDigest,'userId',h.userId,
  'currentEmail',h.currentEmail,'newEmail',h.newEmail,'tokenVersion',h.tokenVersion,'reservedAtMs',h.reservedAtMs,'expiresAtMs',h.expiresAtMs,
  'codeHash',h.codeHash,'attempts','0')
redis.call('PEXPIREAT', KEYS[2], tonumber(h.expiresAtMs))
redis.call('DEL', KEYS[1])
return {0}
