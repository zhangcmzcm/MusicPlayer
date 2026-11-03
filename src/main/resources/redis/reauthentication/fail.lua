if #KEYS ~= 1 or #ARGV ~= 7 then return {2,0} end
local h = state(KEYS[1], false, now_ms())
if h == false or (h and (h.userId ~= ARGV[1] or tonumber(h.attempts) > 5)) then return {2,0} end
if not owner(h) or h.attempts == '5' or h.codeHash ~= ARGV[5] or h.reservedAtMs ~= ARGV[6] or h.expiresAtMs ~= ARGV[7] then return {1,0} end
local count = tonumber(h.attempts) + 1
if count == 5 then redis.call('DEL', KEYS[1]) else redis.call('HINCRBY', KEYS[1], 'attempts', 1) end
return {0,count}
