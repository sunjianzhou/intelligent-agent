package com.intelligent.agent.web.util;

import com.intelligent.agent.web.config.AuthProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * 描述：
 *
 * @author lin miao
 * @date 2026/5/11
 */
@Component
@RequiredArgsConstructor
public class JwtUtil {
    private final AuthProperties authProperties;

    private SecretKey getKey() {
        return Keys.hmacShaKeyFor(
                authProperties.getJwt().getSecret().getBytes(StandardCharsets.UTF_8)
        );
    }

    public String generateToken(String username) {
        long now    = System.currentTimeMillis();
        long expiry = now + authProperties.getJwt().getExpiryHours() * 3600_000L;
        return Jwts.builder()
                .setSubject(username)
                .setIssuedAt(new Date(now))
                .setExpiration(new Date(expiry))
                .signWith(getKey(), SignatureAlgorithm.HS256)
                .compact();
    }

    public Claims parse(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(getKey())
                .build()
                .parseClaimsJws(token)
                .getBody();
    }

    public boolean shouldRefresh(Claims claims) {
        long now    = System.currentTimeMillis();
        long expiry = claims.getExpiration().getTime();
        long issued = claims.getIssuedAt().getTime();
        long half   = (expiry - issued) / 2;
        return (expiry - now) < half;
    }
}
