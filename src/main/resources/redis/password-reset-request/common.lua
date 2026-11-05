local function now_ms()
  local t = redis.call('TIME')
  return tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
end
local function decimal(n) return string.format('%.0f', n) end
local function canonical(s, max)
  return type(s) == 'string' and (s == '0' or string.match(s, '^[1-9][0-9]*$'))
    and (#s < #max or (#s == #max and s <= max))
end
local function millis(s)
  if canonical(s, '9007199254740991') then return tonumber(s) end
  return nil
end
local function digest(s)
  return type(s) == 'string' and #s == 43 and string.match(s, '^[A-Za-z0-9_%-]+$') and string.match(string.sub(s,43), '^[AEIMQUYcgkosw048]$')
end
local function bcrypt(s)
  if type(s) ~= 'string' or #s ~= 60 or not string.match(s, '^%$2[aby]%$[0-9][0-9]%$[A-Za-z0-9./]+$') then return false end
  local cost = tonumber(string.sub(s, 5, 6))
  return cost >= 4 and cost <= 31
end
local function utc_day_end(t) return (math.floor(t / 86400000) + 1) * 86400000 end
local function hash(key, allowed_sizes)
  local kind = redis.call('TYPE', key).ok
  if kind == 'none' then return nil end
  if kind ~= 'hash' then return false end
  local size = redis.call('HLEN', key)
  if not allowed_sizes[size] then return false end
  local raw = redis.call('HGETALL', key)
  local result = {}
  for i = 1, #raw, 2 do result[raw[i]] = raw[i + 1] end
  return result
end
local function exact(h, names)
  local count = 0
  for _, name in ipairs(names) do
    if h[name] == nil then return false end
    count = count + 1
  end
  local actual = 0
  for _ in pairs(h) do actual = actual + 1 end
  return count == actual
end
local function counter(key, limit, now, daily)
  local h = hash(key, {[2]=true})
  if not h then return h end
  if not exact(h, {'count','resetAtMs'}) or not canonical(h.count, decimal(limit)) or h.count == '0' then return false end
  local expiry = millis(h.resetAtMs)
  if not expiry or expiry <= now or (daily and expiry ~= utc_day_end(now))
    or (not daily and expiry > now + 60000) or redis.call('PEXPIRETIME', key) ~= expiry then return false end
  return h
end

local P='music:auth:password-reset:'
local task_names={'schema','digest','emailDigest','email','admittedAt','expiresAt','accountPresent','userId','rawEmail','role','tokenVersion','traceId','phase','ownerNonce','codeHash'}
local function address(s,empty)
 return type(s)=='string' and #s<=480 and (empty or #s>0) and not string.find(s,'[%z\r\n]')
end
local function queue_types()
 for _,k in ipairs({P..'ready',P..'outstanding'}) do local t=redis.call('TYPE',k).ok;if t~='none' and t~='zset' then return false end end
 return true
end
local function pointer(key,now)
 local t=redis.call('TYPE',key).ok;if t=='none' then return nil end
 if t~='string' then return false end
 local value=redis.call('GET',key);local ending=redis.call('PEXPIRETIME',key)
 if not digest(value) or ending<=now or ending>now+300000 then return false end
 return value
end
local function task(key,now)
 local h=hash(key,{[15]=true});if not h then return h end
 if not exact(h,task_names) or h.schema~='1' or not digest(h.digest) or not digest(h.emailDigest) or not address(h.email,false)
  or #h.traceId<1 or #h.traceId>64 or not string.match(h.traceId,'^[A-Za-z0-9._-]+$') then return false end
 local first,last=millis(h.admittedAt),millis(h.expiresAt)
 if not first or not last or first>now or last-first~=300000 or last<=now or redis.call('PEXPIRETIME',key)~=last then return false end
 if h.accountPresent=='1' then
  if not canonical(h.userId,'2147483647') or h.userId=='0' or not address(h.rawEmail,false) or (h.role~='USER' and h.role~='ADMIN') or not canonical(h.tokenVersion,'9223372036854775807') then return false end
 elseif h.accountPresent~='0' or h.userId~='' or h.rawEmail~='' or h.role~='' or h.tokenVersion~='' then return false end
 if h.phase=='QUEUED' then if h.ownerNonce~='' or h.codeHash~='' then return false end
 elseif h.phase=='CLAIMED' then if not digest(h.ownerNonce) or h.codeHash~='' then return false end
 elseif h.phase=='PREPARED' then if not digest(h.ownerNonce) or not bcrypt(h.codeHash) then return false end
 else return false end
 return h
end
local function latest(key,now)
 local h=hash(key,{[2]=true});if not h then return h end
 if not exact(h,{'admittedAt','expiresAt'}) then return false end
 local first,last=millis(h.admittedAt),millis(h.expiresAt)
 if not first or not last or first>now or last-first~=300000 or last<=now or redis.call('PEXPIRETIME',key)~=last then return false end
 return h
end
local function arguments(now)
 if #ARGV<11 or not digest(ARGV[1]) or not digest(ARGV[2]) or not address(ARGV[3],false) then return false end
 local first,last=millis(ARGV[4]),millis(ARGV[5])
 if not first or not last or first>now or last-first~=300000 or #ARGV[11]<1 or #ARGV[11]>64 or not string.match(ARGV[11],'^[A-Za-z0-9._-]+$') then return false end
 if ARGV[6]=='1' then return canonical(ARGV[7],'2147483647') and ARGV[7]~='0' and address(ARGV[8],false) and (ARGV[9]=='USER' or ARGV[9]=='ADMIN') and canonical(ARGV[10],'9223372036854775807') end
 return ARGV[6]=='0' and ARGV[7]=='' and ARGV[8]=='' and ARGV[9]=='' and ARGV[10]==''
end
local arg_names={'digest','emailDigest','email','admittedAt','expiresAt','accountPresent','userId','rawEmail','role','tokenVersion','traceId'}
local function bound(h)
 if not h then return false end
 for i,n in ipairs(arg_names) do if h[n]~=ARGV[i] then return false end end
 return h.ownerNonce==ARGV[12]
end
local function remove_task(h)
 redis.call('ZREM',P..'ready',h.digest);redis.call('ZREM',P..'outstanding',h.digest);redis.call('DEL',P..'task:'..h.digest)
 if redis.call('GET',P..'pending:'..h.emailDigest)==h.digest then redis.call('DEL',P..'pending:'..h.emailDigest) end
end
local function reap(now,batch)
 local members=redis.call('ZRANGEBYSCORE',P..'outstanding','-inf',now,'LIMIT',0,batch)
 -- Validate the complete batch before removing any entry; Lua errors do not roll back writes.
 for _,d in ipairs(members) do
  if not digest(d) or redis.call('EXISTS',P..'task:'..d)~=0 then return false end
 end
 for _,d in ipairs(members) do redis.call('ZREM',P..'ready',d);redis.call('ZREM',P..'outstanding',d) end
 return #members
end
local function current(h,now)
 local p=pointer(P..'pending:'..h.emailDigest,now);local l=latest(P..'latest:'..h.emailDigest,now)
 if p==false or l==false then return nil end
 return p==h.digest and l and l.admittedAt==h.admittedAt and l.expiresAt==h.expiresAt
end
