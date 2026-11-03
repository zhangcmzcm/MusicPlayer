if #KEYS ~= 1 or #ARGV ~= 4 then return {2} end
local h = state(KEYS[1], true, now_ms())
if h == false then return {2} end
if owner(h) then redis.call('DEL', KEYS[1]) end
return {0}
