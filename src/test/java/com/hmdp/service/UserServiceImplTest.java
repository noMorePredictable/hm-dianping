package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.service.impl.UserServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import static com.hmdp.utils.RedisConstants.LOGIN_USER_KEY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class UserServiceImplTest {

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    private final UserServiceImpl userService = createService();

    @Test
    void logoutShouldDeleteRedisSession() {
        Result result = userService.logout("token-1");

        assertThat(result.getSuccess()).isTrue();
        verify(redisTemplate).delete(LOGIN_USER_KEY + "token-1");
    }

    @Test
    void logoutWithoutTokenShouldRemainIdempotent() {
        Result result = userService.logout("  ");

        assertThat(result.getSuccess()).isTrue();
        verify(redisTemplate, never()).delete(org.mockito.ArgumentMatchers.anyString());
    }

    private UserServiceImpl createService() {
        UserServiceImpl service = new UserServiceImpl();
        ReflectionTestUtils.setField(service, "stringRedisTemplate", redisTemplate);
        return service;
    }
}
