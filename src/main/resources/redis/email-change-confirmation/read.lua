if #KEYS ~= 1 or #ARGV ~= 2 then return {2} end
local h = confirmation_state(KEYS[1], ARGV[1], now_ms())
if h == false then return {2} end
if not h or h.challengeDigest ~= ARGV[2] then return {1} end
return {0,h.state,h.challengeDigest,h.userId,h.currentEmail,h.newEmail,h.tokenVersion,h.reservedAtMs,h.expiresAtMs,h.codeHash,h.attempts}
