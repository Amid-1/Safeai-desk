package ru.safeai.gateway.common.ratelimit;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import ru.safeai.gateway.common.exception.RateLimitExceededException;

import java.time.Duration;

@Service
@RequiredArgsConstructor
public class LoginRateLimitService {

    private final StringRedisTemplate redisTemplate;

    public void checkAllowed(String email) {
        String normalizedEmail = email == null ? "unknown" : email.trim().toLowerCase();

        String key = "rate-limit:login:" + normalizedEmail;

        Long count = redisTemplate.opsForValue().increment(key);

        if (count != null && count == 1) {
            redisTemplate.expire(key, Duration.ofMinutes(10));
        }

        if (count != null && count > 10) {
            throw new RateLimitExceededException(
                    "Слишком много попыток входа. Попробуйте позже"
            );
        }
    }
}