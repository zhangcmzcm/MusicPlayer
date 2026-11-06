local h,status = mutation()
if not h then return {status} end
redis.call('DEL',KEYS[1],KEYS[2])
return {0}
