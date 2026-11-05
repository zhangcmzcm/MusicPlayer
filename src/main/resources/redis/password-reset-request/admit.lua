if #KEYS ~= 3 or #ARGV ~= 0 then return {2,0,0} end
local now=now_ms()
local c=hash(KEYS[1],{[1]=true})
if c and (not exact(c,{'resetAtMs'}) or not millis(c.resetAtMs) or tonumber(c.resetAtMs)<=now or tonumber(c.resetAtMs)>now+60000 or redis.call('PEXPIRETIME',KEYS[1])~=tonumber(c.resetAtMs)) then return {2,0,0} end
local d,i=counter(KEYS[2],10,now,true),counter(KEYS[3],5,now,false)
if c==false or d==false or i==false then return {2,0,0} end
local wait=0
if c then wait=tonumber(c.resetAtMs)-now end
if d and tonumber(d.count)==10 then wait=math.max(wait,tonumber(d.resetAtMs)-now) end
if i and tonumber(i.count)==5 then wait=math.max(wait,tonumber(i.resetAtMs)-now) end
if wait>0 then return {1,math.ceil(wait/1000),0} end
redis.call('HSET',KEYS[1],'resetAtMs',decimal(now+60000));redis.call('PEXPIREAT',KEYS[1],now+60000)
local function increment(key,h,ending)
 redis.call('HSET',key,'count',decimal(h and tonumber(h.count)+1 or 1),'resetAtMs',decimal(ending));redis.call('PEXPIREAT',key,ending)
end
increment(KEYS[2],d,utc_day_end(now));increment(KEYS[3],i,i and tonumber(i.resetAtMs) or now+60000)
return {0,now,now+300000}
