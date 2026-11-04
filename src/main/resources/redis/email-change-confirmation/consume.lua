if #KEYS ~= 1 or #ARGV ~= 8 then return {2} end
local h = confirmation_state(KEYS[1], ARGV[1], now_ms())
if h == false then return {2} end
local result = confirmation_owner(h)
if result ~= 0 then return {result} end
if redis.call('DEL',KEYS[1]) ~= 1 then return {1} end
return {0}
