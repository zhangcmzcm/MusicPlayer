local h,status = mutation()
if not h then return {status} end
if tonumber(h.attempts) == 4 then
    redis.call('DEL',KEYS[1],KEYS[2])
else
    redis.call('HINCRBY',KEYS[1],'attempts',1)
end
return {0}
