local now = now_ms()
local h = challenge(KEYS[1],now)
if h == nil then return {1} end
if h == false or KEYS[1] ~= P..'challenge:'..h.digest then return {2} end
local current = active(h,now)
if current == false then return {2} end
if current == nil then return {1} end
local result = {0}
for _,name in ipairs(names) do table.insert(result,h[name]) end
return result
