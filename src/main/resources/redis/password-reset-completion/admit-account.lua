local prefix = 'music:auth:password-reset-completion:user:'
if #KEYS ~= 1 or #ARGV ~= 0 or string.sub(KEYS[1],1,#prefix) ~= prefix then return {2,0} end
local userId = string.sub(KEYS[1],#prefix+1)
if not canonical(userId,'2147483647') or userId == '0' then return {2,0} end
return quota(900000)
