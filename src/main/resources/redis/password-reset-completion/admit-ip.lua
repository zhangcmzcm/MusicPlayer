local prefix = 'music:auth:password-reset-completion:ip:'
if #KEYS ~= 1 or #ARGV ~= 0 or string.sub(KEYS[1],1,#prefix) ~= prefix
    or not digest(string.sub(KEYS[1],#prefix+1)) then return {2,0} end
return quota(60000)
