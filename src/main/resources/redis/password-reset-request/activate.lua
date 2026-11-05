local now=now_ms()
if #ARGV~=12 or not queue_types() or not arguments(now) or not digest(ARGV[12]) then return {2} end
if tonumber(ARGV[5])<=now then return {1} end
local h=task(P..'task:'..ARGV[1],now);if h==false then return {2} end
if not bound(h) or h.phase~='PREPARED' or h.accountPresent~='1' then return {1} end
local c=current(h,now);if c==nil then return {2} elseif not c then return {1} end
local old=pointer(P..'active:'..h.emailDigest,now);if old==false then return {2} end
if old then
 local a=hash(P..'challenge:'..old,{[12]=true})
 if not a or not exact(a,{'schema','digest','emailDigest','email','admittedAt','expiresAt','userId','rawEmail','role','tokenVersion','codeHash','attempts'}) or a.schema~='1' or a.digest~=old or a.emailDigest~=h.emailDigest or not bcrypt(a.codeHash) or not canonical(a.attempts,'4') or not canonical(a.tokenVersion,'9223372036854775807') or not millis(a.expiresAt) or redis.call('PEXPIRETIME',P..'challenge:'..old)~=tonumber(a.expiresAt) then return {2} end
end
local key=P..'challenge:'..h.digest
if redis.call('EXISTS',key)~=0 then return {2} end
for _,n in ipairs({'schema','digest','emailDigest','email','admittedAt','expiresAt','userId','rawEmail','role','tokenVersion','codeHash'}) do redis.call('HSET',key,n,h[n]) end
redis.call('HSET',key,'attempts','0');redis.call('PEXPIREAT',key,tonumber(h.expiresAt))
redis.call('SET',P..'active:'..h.emailDigest,h.digest,'PXAT',h.expiresAt)
if old then redis.call('DEL',P..'challenge:'..old) end
remove_task(h);return {0}
