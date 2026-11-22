#!lua
-- Operator-only state machine. The application never executes this script.
-- ARGV: action, expected old fingerprint, new fingerprint.
local key = KEYS[1]
if key ~= 'safeai:rate-limit:guard:{login}:epoch' then
    return redis.error_reply('INVALID_EPOCH_KEY')
end
local action, old, new = ARGV[1], ARGV[2], ARGV[3]
local function fingerprint(value)
    return value and #value == 64 and string.match(value, '^[0-9a-f]+$') ~= nil
end
local server = redis.call('INFO', 'server')
local replication = redis.call('INFO', 'replication')
local run = string.match(server, '\nrun_id:([0-9a-f]+)\r?\n')
local repl = string.match(replication, '\nmaster_replid:([0-9a-f]+)\r?\n')
local role = string.match(replication, '\nrole:([^\r\n]+)')
if not run or #run ~= 40 or not repl or #repl ~= 40 or role ~= 'master' then
    return redis.error_reply('EPOCH_PRIMARY_UNAVAILABLE')
end
local state = redis.call('GET', key)
local mode, stored, storedRun, storedRepl, deadline
if state then
    if redis.call('PTTL', key) ~= -1 then return redis.error_reply('INVALID_EPOCH_STATE') end
    mode, stored, storedRun, storedRepl = string.match(state,
        '^(ACTIVE):([0-9a-f]+):([0-9a-f]+):([0-9a-f]+)$')
    if not mode then
        mode, stored, storedRun, storedRepl, deadline = string.match(state,
            '^(PAUSED):([0-9a-f]+):([0-9a-f]+):([0-9a-f]+):(%d+)$')
    end
    if not mode or not fingerprint(stored) or #storedRun ~= 40 or #storedRepl ~= 40
        or (mode == 'PAUSED' and (#deadline > 13 or not tonumber(deadline))) then
        return redis.error_reply('INVALID_EPOCH_STATE')
    end
end
local samePrimary = storedRun == run and storedRepl == repl
local time = redis.call('TIME')
local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
-- Maximum supported counter lifetime (24h) plus F1 refresh dispatch margin (10s).
local drain = 86410000
local function active(fp)
    redis.call('SET', key, 'ACTIVE:' .. fp .. ':' .. run .. ':' .. repl)
    return 'ACTIVE'
end
local function paused(fp)
    redis.call('SET', key, 'PAUSED:' .. fp .. ':' .. run .. ':' .. repl .. ':'
        .. string.format('%.0f', now + drain))
    return 'PAUSED'
end
if action == 'status' then
    if not state then return 'MISSING' end
    if not samePrimary then return 'PRIMARY_CHANGED' end
    if mode == 'PAUSED' then
        return 'PAUSED:' .. string.format('%.0f', math.max(0, tonumber(deadline) - now))
    end
    return 'ACTIVE'
end
if not fingerprint(old) or not fingerprint(new) then return redis.error_reply('INVALID_EPOCH_FINGERPRINT') end
if action == 'initialize-empty' then
    -- Only for a confirmed first installation. Missing state after an incident
    -- is NOT a new installation, even if the database is empty.
    if state or redis.call('DBSIZE') ~= 0 or old ~= new then
        return redis.error_reply('EPOCH_INITIALIZATION_DENIED')
    end
    return active(new)
end
if action == 'begin-missing' then
    if state or old ~= new then return redis.error_reply('EPOCH_INITIALIZATION_DENIED') end
    return paused(old)
end
if not state or stored ~= old then return redis.error_reply('EPOCH_COMPARE_AND_SET_FAILED') end
if action == 'pause' then
    if old ~= new then return redis.error_reply('EPOCH_COMPARE_AND_SET_FAILED') end
    -- Repeating pause (including after promotion) starts a full new drain.
    return paused(old)
end
if action == 'activate' then
    if mode ~= 'PAUSED' or not samePrimary or now < tonumber(deadline) then
        return redis.error_reply('EPOCH_DRAIN_NOT_COMPLETE')
    end
    return active(new)
end
if action == 'rebind-preserved' then
    -- Explicit operator recovery after fencing the former primary and proving
    -- durable counter continuity. Never changes the HMAC namespace.
    if mode ~= 'ACTIVE' or old ~= new or samePrimary then
        return redis.error_reply('EPOCH_REBIND_DENIED')
    end
    return active(old)
end
return redis.error_reply('UNKNOWN_EPOCH_ACTION')
