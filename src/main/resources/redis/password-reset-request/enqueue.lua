local now=now_ms()
if #ARGV~=12 or not arguments(now) or tonumber(ARGV[5])<=now or not canonical(ARGV[12],'2147483647') or ARGV[12]=='0' or not queue_types() then return {2} end
if reap(now,100)==false then return {2} end
local d,e=ARGV[1],ARGV[2];local p=pointer(P..'pending:'..e,now);local l=latest(P..'latest:'..e,now)
if p==false or l==false or (l and tonumber(l.admittedAt)>=tonumber(ARGV[4])) or redis.call('EXISTS',P..'task:'..d)~=0 or redis.call('EXISTS',P..'challenge:'..d)~=0 then return {2} end
local old=nil
if p then old=task(P..'task:'..p,now);if not old or old.digest~=p or old.emailDigest~=e then return {2} end end
local count=redis.call('ZCARD',P..'outstanding');local credit=old and old.phase=='QUEUED' and 1 or 0
if count-credit>=tonumber(ARGV[12]) then return {2} end
if old and old.phase=='QUEUED' then remove_task(old) end
local key=P..'task:'..d
redis.call('HSET',key,'schema','1','phase','QUEUED','ownerNonce','','codeHash','')
for i,n in ipairs(arg_names) do redis.call('HSET',key,n,ARGV[i]) end
redis.call('PEXPIREAT',key,tonumber(ARGV[5]))
redis.call('SET',P..'pending:'..e,d,'PXAT',ARGV[5]);redis.call('HSET',P..'latest:'..e,'admittedAt',ARGV[4],'expiresAt',ARGV[5]);redis.call('PEXPIREAT',P..'latest:'..e,tonumber(ARGV[5]))
redis.call('ZADD',P..'ready',tonumber(ARGV[4]),d);redis.call('ZADD',P..'outstanding',tonumber(ARGV[5]),d)
return {0}
