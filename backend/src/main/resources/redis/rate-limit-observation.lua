#!lua flags=no-writes
-- One read-only snapshot, no KEYS/SCAN, no pruning, no repair or allocation.
local function field(info, name)
    return string.match(info, '\n' .. name .. ':([^\r\n]+)')
end
local function number(info, name)
    local raw = field(info, name)
    if not raw or not string.match(raw, '^%d+$') then error('OBSERVATION_INVALID') end
    return raw
end
local memory = redis.call('INFO', 'memory')
local stats = redis.call('INFO', 'stats')
local persistence = redis.call('INFO', 'persistence')
local server = redis.call('INFO', 'server')
local replication = redis.call('INFO', 'replication')
local now = redis.call('TIME')
local ms = tonumber(now[1]) * 1000 + math.floor(tonumber(now[2]) / 1000)
local active = 'disabled'
if ARGV[2] == '1' then
    local state = redis.call('GET', KEYS[1])
    if not state then
        active = 'missing'
    elseif redis.call('PTTL', KEYS[1]) ~= -1 then
        active = 'invalid'
    elseif string.match(state, '^PAUSED:') then
        local fp, run, repl, deadline = string.match(state, '^PAUSED:([0-9a-f]+):([0-9a-f]+):([0-9a-f]+):(%d+)$')
        if fp and #fp == 64 and #run == 40 and #repl == 40 and #deadline <= 13 then
            active = 'paused'
        else
            active = 'invalid'
        end
    else
        local fingerprint, run, repl = string.match(state, '^ACTIVE:([0-9a-f]+):([0-9a-f]+):([0-9a-f]+)$')
        if not fingerprint or #fingerprint ~= 64 or #run ~= 40 or #repl ~= 40 then
            active = 'invalid'
        elseif fingerprint ~= ARGV[1] then
            active = 'mismatch'
        elseif field(replication, 'role') ~= 'master' or run ~= field(server, 'run_id')
            or repl ~= field(replication, 'master_replid') then
            active = 'primary_changed'
        else
            active = 'active'
        end
    end
end
return table.concat({
    number(memory, 'used_memory'), number(memory, 'mem_not_counted_for_evict'),
    number(memory, 'maxmemory'), number(memory, 'used_memory_rss'),
    field(memory, 'maxmemory_policy') == 'noeviction' and '1' or '0',
    number(stats, 'evicted_keys'),
    field(persistence, 'aof_enabled') == '1' and field(persistence, 'loading') == '0'
        and field(persistence, 'aof_last_write_status') == 'ok'
        and field(persistence, 'aof_last_bgrewrite_status') == 'ok' and '1' or '0',
    field(replication, 'role') == 'master' and '1' or '0',
    tostring(redis.call('ZCOUNT', KEYS[2], '(' .. string.format('%.0f', ms), '+inf')),
    tostring(redis.call('ZCOUNT', KEYS[3], '(' .. string.format('%.0f', ms), '+inf')),
    active
}, ':')
