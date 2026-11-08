local TP = 'music:auth:password-reset-ticket:'
local ticket_names = {'schema','digest','userId','email','emailDigest','rawEmail','role','tokenVersion','issuedAt','expiresAt'}
local function ticket_fields_valid(h, now)
    local first,last = millis(h.issuedAt),millis(h.expiresAt)
    return h.schema == '1' and digest(h.digest) and canonical(h.userId,'2147483647') and h.userId ~= '0'
        and address(h.email) and digest(h.emailDigest) and address(h.rawEmail)
        and (h.role == 'USER' or h.role == 'ADMIN') and canonical(h.tokenVersion,'9223372036854775807')
        and first and last and first <= now and last-first == 300000
end
local function ticket_record(key, now)
    local kind = redis.call('TYPE',key).ok
    if kind == 'none' then return nil end
    if kind ~= 'hash' or redis.call('HLEN',key) ~= 10 then return false end
    local values = redis.call('HMGET',key,unpack(ticket_names))
    local h = {}
    for i,name in ipairs(ticket_names) do
        if not values[i] then return false end
        h[name] = values[i]
    end
    if not ticket_fields_valid(h,now) or tonumber(h.expiresAt) <= now
        or redis.call('PEXPIRETIME',key) ~= tonumber(h.expiresAt) or key ~= TP..'ticket:'..h.digest then return false end
    return h
end
local function ticket_current(key, userId, now)
    if key ~= TP..'active:'..userId then return false end
    local kind = redis.call('TYPE',key).ok
    if kind == 'none' then return nil end
    if kind ~= 'string' then return false end
    local value = redis.call('GET',key)
    local ending = redis.call('PEXPIRETIME',key)
    if not digest(value) or ending <= now or ending > now+300000 then return false end
    local h = ticket_record(TP..'ticket:'..value,now)
    if not h or h.userId ~= userId or tonumber(h.expiresAt) ~= ending then return false end
    return h
end
