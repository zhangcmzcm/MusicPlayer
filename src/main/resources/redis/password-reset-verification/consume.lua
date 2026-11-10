if #KEYS ~= 2 or #ARGV ~= 10 then return {2} end
local expected = {}
for i,name in ipairs(ticket_names) do expected[name] = ARGV[i] end
local now = now_ms()
if not ticket_fields_valid(expected,now) or KEYS[1] ~= TP..'ticket:'..expected.digest
    or KEYS[2] ~= TP..'active:'..expected.userId then return {2} end
local h = ticket_record(KEYS[1],now)
if h == false then return {2} end
local current = ticket_current(KEYS[2],expected.userId,now)
if current == false then return {2} end
if h == nil then return {1} end
for _,name in ipairs(ticket_names) do
    if h[name] ~= expected[name] then return {2} end
end
if current == nil then return {2} end
if current.digest ~= h.digest then return {1} end
redis.call('DEL',KEYS[1],KEYS[2])
return {0}
