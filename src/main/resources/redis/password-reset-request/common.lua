local function now_ms()
  local t = redis.call('TIME')
  return tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
end
local function decimal(n) return string.format('%.0f', n) end
local function canonical(s, max)
  return type(s) == 'string' and (s == '0' or string.match(s, '^[1-9][0-9]*$'))
    and (#s < #max or (#s == #max and s <= max))
end
local function millis(s)
  if canonical(s, '9007199254740991') then return tonumber(s) end
  return nil
end
local function digest(s)
  return type(s) == 'string' and #s == 43 and string.match(s, '^[A-Za-z0-9_%-]+$') and string.match(string.sub(s,43), '^[AEIMQUYcgkosw048]$')
end
local function bcrypt(s)
  if type(s) ~= 'string' or #s ~= 60 or not string.match(s, '^%$2[aby]%$[0-9][0-9]%$[A-Za-z0-9./]+$') then return false end
  local cost = tonumber(string.sub(s, 5, 6))
  return cost >= 4 and cost <= 31
end
local function utc_day_end(t) return (math.floor(t / 86400000) + 1) * 86400000 end
local function hash(key, allowed_sizes)
  local kind = redis.call('TYPE', key).ok
  if kind == 'none' then return nil end
  if kind ~= 'hash' then return false end
  local size = redis.call('HLEN', key)
  if not allowed_sizes[size] then return false end
  local raw = redis.call('HGETALL', key)
  local result = {}
  for i = 1, #raw, 2 do result[raw[i]] = raw[i + 1] end
  return result
end
local function exact(h, names)
  local count = 0
  for _, name in ipairs(names) do
    if h[name] == nil then return false end
    count = count + 1
  end
  local actual = 0
  for _ in pairs(h) do actual = actual + 1 end
  return count == actual
end
local function counter(key, limit, now, daily)
  local h = hash(key, {[2]=true})
  if not h then return h end
  if not exact(h, {'count','resetAtMs'}) or not canonical(h.count, decimal(limit)) or h.count == '0' then return false end
  local expiry = millis(h.resetAtMs)
  if not expiry or expiry <= now or (daily and expiry ~= utc_day_end(now))
    or (not daily and expiry > now + 60000) or redis.call('PEXPIRETIME', key) ~= expiry then return false end
  return h
end
