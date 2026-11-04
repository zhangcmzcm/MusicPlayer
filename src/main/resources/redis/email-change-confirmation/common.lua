local function confirmation_state(key, user, now)
  local h = state(key, false, now)
  if h == false then return false end
  if h and (h.userId ~= user or not string.match(h.attempts, '^[0-4]$')
    or not string.match(h.challengeDigest, '[AEIMQUYcgkosw048]$')) then return false end
  return h
end
local function confirmation_owner(h)
  if not h or h.challengeDigest ~= ARGV[5] then return 1 end
  if not owner(h) or h.codeHash ~= ARGV[6] or h.reservedAtMs ~= ARGV[7] or h.expiresAtMs ~= ARGV[8] then return 2 end
  return 0
end
