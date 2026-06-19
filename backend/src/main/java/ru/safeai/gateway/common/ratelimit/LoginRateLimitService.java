package ru.safeai.gateway.common.ratelimit;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import ru.safeai.gateway.common.exception.RateLimitExceededException;
import ru.safeai.gateway.common.exception.RateLimitUnavailableException;

import java.time.Duration;

@Service
@RequiredArgsConstructor
public class LoginRateLimitService {

    private final StringRedisTemplate redisTemplate;

    public void checkAllowed(String email, String ipAddress) {
        String normalizedEmail = email == null ? "unknown" : email.trim().toLowerCase();
        String normalizedIp = ipAddress == null || ipAddress.isBlank() ? "unknown" : ipAddress.trim();

        checkKey("rate-limit:login:email:" + normalizedEmail, 10, Duration.ofMinutes(10));
        checkKey("rate-limit:login:ip:" + normalizedIp, 30, Duration.ofMinutes(10));
    }

    private void checkKey(String key, int limit, Duration ttl) {
        try {
            Long count = redisTemplate.opsForValue().increment(key);

            if (count != null && count == 1) {
                redisTemplate.expire(key, ttl);
            }

            if (count != null && count > limit) {
                throw new RateLimitExceededException(
                        "Слишком много попыток входа. Попробуйте позже"
                );
            }
        } catch (RateLimitExceededException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new RateLimitUnavailableException(
                    "Redis login rate limit недоступен",
                    exception
            );
        }
    }
}