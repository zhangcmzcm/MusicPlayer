if #KEYS ~= 1 or #ARGV ~= 1 or not canonical(ARGV[1],'2147483647') or ARGV[1] == '0' then return {2} end
local h = ticket_current(KEYS[1],ARGV[1],now_ms())
if h == nil then return {1} end
if h == false then return {2} end
local result = {0}
for _,name in ipairs(ticket_names) do table.insert(result,h[name]) end
return result
