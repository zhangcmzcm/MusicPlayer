if #KEYS ~= 1 or #ARGV ~= 6 or not bcrypt(ARGV[6]) then return {2} end
local h = state(KEYS[1], true, now_ms())
if h == false then return {2} end
if not owner(h) or h.state ~= 'PREPARING' then return {1} end
redis.call('HSET', KEYS[1], 'state','PREPARED','codeHash',ARGV[6])
return {0}
