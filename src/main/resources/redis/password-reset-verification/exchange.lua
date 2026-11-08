if #KEYS ~= 4 or #ARGV ~= 22 or not digest(ARGV[11]) or (ARGV[12] ~= '0' and ARGV[12] ~= '1') then return {2,0,0} end
local now = now_ms()
if now < 0 or now > 9007199254440991 then return {2,0,0} end
local h = challenge(KEYS[1],now)
if h == nil then return {1,0,0} end
if h == false or KEYS[1] ~= P..'challenge:'..h.digest or KEYS[2] ~= P..'active:'..h.emailDigest
    or KEYS[3] ~= TP..'active:'..h.userId or KEYS[4] ~= TP..'ticket:'..ARGV[11] then return {2,0,0} end
local current = active(h,now)
if current == false then return {2,0,0} end
if current == nil then return {1,0,0} end
for i,name in ipairs(bound_names) do
    if h[name] ~= ARGV[i] then return {1,0,0} end
end
local previous = nil
if ARGV[12] == '1' then
    previous = {}
    for i,name in ipairs(ticket_names) do previous[name] = ARGV[12+i] end
    if not ticket_fields_valid(previous,now) or previous.userId ~= h.userId then return {2,0,0} end
else
    for i=13,22 do if ARGV[i] ~= '' then return {2,0,0} end end
end
local old = ticket_current(KEYS[3],h.userId,now)
if old == false then return {2,0,0} end
if old then
    if not previous or old.digest ~= previous.digest then return {1,0,0} end
    -- A ticket is immutable. A same-digest field change is damage, not a new issuance.
    for _,name in ipairs(ticket_names) do
        if old[name] ~= previous[name] then return {2,0,0} end
    end
    local older,newer = h.tokenVersion,old.tokenVersion
    if #newer > #older or (#newer == #older and newer > older) then return {1,0,0} end
elseif previous and redis.call('TYPE',TP..'ticket:'..previous.digest).ok ~= 'none' then
    return {2,0,0}
end
if redis.call('EXISTS',KEYS[4]) ~= 0 then return {2,0,0} end
local ending = now+300000
local firstText,lastText = string.format('%.0f',now),string.format('%.0f',ending)
redis.call('HSET',KEYS[4],'schema','1','digest',ARGV[11],'userId',h.userId,'email',h.email,'emailDigest',h.emailDigest,
    'rawEmail',h.rawEmail,'role',h.role,'tokenVersion',h.tokenVersion,'issuedAt',firstText,'expiresAt',lastText)
redis.call('PEXPIREAT',KEYS[4],ending)
redis.call('SET',KEYS[3],ARGV[11],'PXAT',ending)
if old then redis.call('DEL',TP..'ticket:'..old.digest) end
redis.call('DEL',KEYS[1],KEYS[2])
return {0,now,ending}
