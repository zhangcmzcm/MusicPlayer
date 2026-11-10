if #KEYS ~= 1 or #ARGV ~= 1 or not digest(ARGV[1]) or KEYS[1] ~= TP..'ticket:'..ARGV[1] then return {2} end
local now = now_ms()
local h = ticket_record(KEYS[1],now)
if h == nil then return {1} end
if h == false then return {2} end
local current = ticket_current(TP..'active:'..h.userId,h.userId,now)
if not current then return {2} end
if current.digest ~= h.digest then return {1} end
local result = {0}
for _,name in ipairs(ticket_names) do table.insert(result,h[name]) end
return result
