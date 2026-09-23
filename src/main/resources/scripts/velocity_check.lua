-- Atomic sliding window velocity check by monetary sum (REQ-060, REQ-061)
-- KEYS[1]: velocity key (e.g. velocity:account:<accountId>)
-- ARGV[1]: current timestamp in milliseconds (nowMs)
-- ARGV[2]: window size in milliseconds (windowSizeMs)
-- ARGV[3]: transfer amount in paise (amountPaise)
-- ARGV[4]: velocity limit in paise (limitPaise)
-- ARGV[5]: unique operation id (e.g. transactionId / requestId)

local key = KEYS[1]
local nowMs = tonumber(ARGV[1])
local windowSizeMs = tonumber(ARGV[2])
local amountPaise = tonumber(ARGV[3])
local limitPaise = tonumber(ARGV[4])
local opId = ARGV[5]

local windowStart = nowMs - windowSizeMs

-- Step 1: Evict entries outside the sliding window
redis.call('ZREMRANGEBYSCORE', key, '-inf', windowStart)

-- Step 2: Sum all remaining active transaction amounts in the window (REQ-060)
local entries = redis.call('ZRANGEBYSCORE', key, windowStart, '+inf')
local currentSum = 0

for _, entry in ipairs(entries) do
    local amountStr = string.match(entry, '^([%d]+):')
    if amountStr then
        currentSum = currentSum + tonumber(amountStr)
    end
end

-- Step 3: Check if adding new amount exceeds the limit
if (currentSum + amountPaise) > limitPaise then
    return {0, currentSum, limitPaise}
end

-- Step 4: Record new transaction atomically in the sorted set
local member = tostring(amountPaise) .. ':' .. opId
redis.call('ZADD', key, nowMs, member)

-- Step 5: Set TTL slightly larger than window size for auto-cleanup
local ttlSeconds = math.ceil(windowSizeMs / 1000) + 60
redis.call('EXPIRE', key, ttlSeconds)

return {1, currentSum + amountPaise, limitPaise}
