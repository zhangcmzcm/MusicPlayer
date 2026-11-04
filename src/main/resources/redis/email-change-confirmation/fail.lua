if #KEYS ~= 1 or #ARGV ~= 8 then return {2,0} end
local h = confirmation_state(KEYS[1], ARGV[1], now_ms())
if h == false then return {2,0} end
local result = confirmation_owner(h)
if result ~= 0 then return {result,0} end
local count = tonumber(h.attempts) + 1
if count == 5 then redis.call('DEL',KEYS[1]) else redis.call('HINCRBY',KEYS[1],'attempts',1) end
return {0,count}
