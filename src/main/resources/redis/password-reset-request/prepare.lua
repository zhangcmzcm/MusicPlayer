local now=now_ms()
if #ARGV~=13 or not queue_types() or not arguments(now) or not digest(ARGV[12]) or not bcrypt(ARGV[13]) then return {2} end
if tonumber(ARGV[5])<=now then return {1} end
local h=task(P..'task:'..ARGV[1],now);if h==false then return {2} end
if not bound(h) or h.phase~='CLAIMED' then return {1} end
local c=current(h,now);if c==nil then return {2} elseif not c then return {1} end
redis.call('HSET',P..'task:'..h.digest,'phase','PREPARED','codeHash',ARGV[13]);return {0}
