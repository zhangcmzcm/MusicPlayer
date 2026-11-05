local now=now_ms()
if #ARGV~=12 or not queue_types() or not digest(ARGV[1]) or not digest(ARGV[12]) then return {2} end
local h=task(P..'task:'..ARGV[1],now);if h==false then return {2} end
if not h then return {0} end
if not bound(h) then return {0} end
if pointer(P..'pending:'..h.emailDigest,now)==false then return {2} end
remove_task(h);return {0}
