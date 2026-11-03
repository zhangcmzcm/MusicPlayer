if #KEYS ~= 1 or #ARGV ~= 4 then return {2} end
local h = state(KEYS[1], false, now_ms())
if h == false or (h and (h.userId ~= ARGV[1] or tonumber(h.attempts) > 5)) then return {2} end
if not owner(h) or h.attempts == '5' then return {1} end
return {0,h.state,h.challengeDigest,h.userId,h.email,h.tokenVersion,h.reservedAtMs,h.expiresAtMs,h.codeHash,h.attempts}
