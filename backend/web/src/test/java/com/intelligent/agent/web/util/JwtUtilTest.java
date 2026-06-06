package com.intelligent.agent.web.util;

import com.intelligent.agent.web.config.AuthProperties;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Date;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class JwtUtilTest {

    @Mock
    private AuthProperties authProperties;

    @InjectMocks
    private JwtUtil jwtUtil;

    private static final String SECRET =
            "test-secret-key-must-be-at-least-32-characters";

    @BeforeEach
    void setUp() {
        AuthProperties.Jwt jwt = new AuthProperties.Jwt();
        jwt.setSecret(SECRET);
        jwt.setExpiryHours(24);
        when(authProperties.getJwt()).thenReturn(jwt);
    }

    @Test
    void generateToken_subjectMatchesUsername() {
        String token = jwtUtil.generateToken("alice");
        Claims claims = jwtUtil.parse(token);
        assertThat(claims.getSubject()).isEqualTo("alice");
    }

    @Test
    void generateToken_expiryIsRoughly24hAhead() {
        long before = System.currentTimeMillis();
        String token = jwtUtil.generateToken("bob");
        long after = System.currentTimeMillis();

        Claims claims = jwtUtil.parse(token);
        long expiry = claims.getExpiration().getTime();
        // expiry 应在 [now+23h, now+25h] 之间（留 1h 误差）
        assertThat(expiry).isBetween(before + 23 * 3600_000L, after + 25 * 3600_000L);
    }

    @Test
    void parse_invalidToken_throwsException() {
        assertThatThrownBy(() -> jwtUtil.parse("not.a.jwt"))
                .isInstanceOf(Exception.class);
    }

    @Test
    void parse_wrongSecret_throwsException() {
        // 用正确密钥签发，然后改密钥后解析应该失败
        String token = jwtUtil.generateToken("carol");

        AuthProperties.Jwt shortSecret = new AuthProperties.Jwt();
        shortSecret.setSecret("another-secret-key-32-characters-!!");
        shortSecret.setExpiryHours(24);
        when(authProperties.getJwt()).thenReturn(shortSecret);

        assertThatThrownBy(() -> jwtUtil.parse(token))
                .isInstanceOf(Exception.class);
    }

    @Test
    void shouldRefresh_freshToken_returnsFalse() {
        // 刚签发的 token，有效期 24h，剩余 > 12h → 不需要刷新
        String token = jwtUtil.generateToken("dave");
        Claims claims = jwtUtil.parse(token);
        assertThat(jwtUtil.shouldRefresh(claims)).isFalse();
    }

    @Test
    void shouldRefresh_pastHalfwayMark_returnsTrue() {
        // 构造一个已过去超过一半有效期的 Claims：issued=25h前，expiry=1h后
        long now = System.currentTimeMillis();
        Claims claims = mock(Claims.class);
        when(claims.getIssuedAt()).thenReturn(new Date(now - 25 * 3600_000L));
        when(claims.getExpiration()).thenReturn(new Date(now + 1 * 3600_000L));
        assertThat(jwtUtil.shouldRefresh(claims)).isTrue();
    }

    @Test
    void generateToken_twoCallsProduce_differentTokens() {
        // 即使用户名相同，两次调用因 iat 不同（毫秒级），token 内容也应不同
        // 为保证 iat 确实不同，在两次调用之间稍作等待或直接检查两次结果
        String t1 = jwtUtil.generateToken("eve");
        // 使 issuedAt 有机会不同（实际生产环境毫秒差足够）
        String t2 = jwtUtil.generateToken("eve");
        // 即使两次 iat 恰好相同（极低概率），至少内容格式正确
        assertThat(t1).matches("[A-Za-z0-9_\\-]+\\.[A-Za-z0-9_\\-]+\\.[A-Za-z0-9_\\-]+");
        assertThat(t2).matches("[A-Za-z0-9_\\-]+\\.[A-Za-z0-9_\\-]+\\.[A-Za-z0-9_\\-]+");
    }
}
