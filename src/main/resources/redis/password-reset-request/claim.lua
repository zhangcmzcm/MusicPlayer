local now=now_ms()
if #ARGV~=1 or not digest(ARGV[1]) or not queue_types() or reap(now,100)==false then return {2} end
local first=redis.call('ZRANGE',P..'ready',0,0)
if #first==0 then return {1} end
local h=task(P..'task:'..first[1],now)
if not h or h.phase~='QUEUED' or h.digest~=first[1] or current(h,now)~=true or redis.call('ZSCORE',P..'outstanding',h.digest)~=h.expiresAt then return {2} end
redis.call('HSET',P..'task:'..h.digest,'phase','CLAIMED','ownerNonce',ARGV[1]);redis.call('ZREM',P..'ready',h.digest)
local result={0};for _,n in ipairs(arg_names) do table.insert(result,h[n]) end;table.insert(result,ARGV[1]);return result
