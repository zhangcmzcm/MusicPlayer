if #ARGV~=1 or not canonical(ARGV[1],'1000') or ARGV[1]=='0' or not queue_types() then return {2,0} end
local result=reap(now_ms(),tonumber(ARGV[1]));if result==false then return {2,0} end;return {0,result}
