local P = 'music:auth:password-reset:'
local names = {'schema','digest','emailDigest','email','admittedAt','expiresAt','userId','rawEmail','role','tokenVersion','codeHash','attempts'}
local function now_ms()
    local t = redis.call('TIME')
    return tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
end
local function canonical(s, maximum)
    return type(s) == 'string' and (s == '0' or string.match(s, '^[1-9][0-9]*$'))
        and (#s < #maximum or (#s == #maximum and s <= maximum))
end
local function millis(s)
    if canonical(s, '9007199254740991') then return tonumber(s) end
    return nil
end
local function digest(s)
    return type(s) == 'string' and #s == 43 and string.match(s, '^[A-Za-z0-9_%-]+$')
        and string.match(string.sub(s,43), '^[AEIMQUYcgkosw048]$')
end
local function bcrypt(s)
    if type(s) ~= 'string' or #s ~= 60 or not string.match(s, '^%$2[aby]%$[0-9][0-9]%$[A-Za-z0-9./]+$') then return false end
    local cost = tonumber(string.sub(s,5,6))
    return cost >= 4 and cost <= 31
end
local function address(s)
    return type(s) == 'string' and #s > 0 and #s <= 480 and not string.find(s, '[%z\r\n]')
end
local function challenge(key, now)
    local kind = redis.call('TYPE',key).ok
    if kind == 'none' then return nil end
    if kind ~= 'hash' or redis.call('HLEN',key) ~= 12 then return false end
    local values = redis.call('HMGET',key,unpack(names))
    local h = {}
    for i,name in ipairs(names) do
        if not values[i] then return false end
        h[name] = values[i]
    end
    local first,last = millis(h.admittedAt),millis(h.expiresAt)
    if h.schema ~= '1' or not digest(h.digest) or not digest(h.emailDigest)
        or not address(h.email) or not address(h.rawEmail)
        or not first or not last or first > now or last-first ~= 300000 or last <= now
        or redis.call('PEXPIRETIME',key) ~= last
        or not canonical(h.userId,'2147483647') or h.userId == '0'
        or (h.role ~= 'USER' and h.role ~= 'ADMIN')
        or not canonical(h.tokenVersion,'9223372036854775807')
        or not bcrypt(h.codeHash) or not canonical(h.attempts,'4') then return false end
    return h
end
local function active(h, now)
    local key = P..'active:'..h.emailDigest
    local kind = redis.call('TYPE',key).ok
    if kind == 'none' then return false end
    if kind ~= 'string' then return false end
    local current = redis.call('GET',key)
    local ending = redis.call('PEXPIRETIME',key)
    if not digest(current) or ending <= now or ending > now+300000 then return false end
    if current ~= h.digest then return nil end
    if ending ~= tonumber(h.expiresAt) then return false end
    return true
end
local function quota(window)
    local now = now_ms()
    local kind = redis.call('TYPE',KEYS[1]).ok
    if kind == 'none' then
        local ending = now+window
        redis.call('HSET',KEYS[1],'count','1','resetAtMs',string.format('%.0f',ending))
        redis.call('PEXPIREAT',KEYS[1],ending)
        return {0,0}
    end
    if kind ~= 'hash' or redis.call('HLEN',KEYS[1]) ~= 2 then return {2,0} end
    local h = redis.call('HMGET',KEYS[1],'count','resetAtMs')
    local ending = millis(h[2])
    if not canonical(h[1],'10') or h[1] == '0' or not ending or ending <= now or ending > now+window
        or redis.call('PEXPIRETIME',KEYS[1]) ~= ending then return {2,0} end
    if tonumber(h[1]) >= 10 then return {1,math.max(1,math.ceil((ending-now)/1000))} end
    redis.call('HINCRBY',KEYS[1],'count',1)
    return {0,0}
end
local bound_names = {'digest','emailDigest','email','admittedAt','expiresAt','userId','rawEmail','role','tokenVersion','codeHash'}
local function mutation()
    if #ARGV ~= 10 or #KEYS ~= 2 then return nil,2 end
    local now = now_ms()
    local h = challenge(KEYS[1],now)
    if h == nil then return nil,1 end
    if h == false then return nil,2 end
    if KEYS[1] ~= P..'challenge:'..h.digest or KEYS[2] ~= P..'active:'..h.emailDigest then return nil,2 end
    local is_active = active(h,now)
    if is_active == false then return nil,2 end
    if is_active == nil then return nil,1 end
    for i,name in ipairs(bound_names) do
        if h[name] ~= ARGV[i] then return nil,1 end
    end
    return h,0
end
