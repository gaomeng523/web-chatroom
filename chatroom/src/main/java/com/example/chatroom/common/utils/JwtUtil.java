package com.example.chatroom.common.utils;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.security.Key;
import java.util.Date;
import java.util.Map;

@Component
@Slf4j
public class JwtUtil {
    public static final long EXPIRATION_TIME = 7 * 24 * 60 * 60 * 1000L;

    private final Key key;

    // @Value 注解是Spring提供的，不要导入错误的包
    public JwtUtil(@Value("${jwt.secret}") String secretString) {
        this.key = Keys.hmacShaKeyFor(Decoders.BASE64.decode(secretString));
    }

    public String genJwt(Map<String, Object> claims) {
        return Jwts.builder()
                .setClaims(claims)
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + EXPIRATION_TIME))
                .signWith(key)
                .compact();
    }

    public Claims parseJwt(String token) {
        JwtParser jwtParser = Jwts.parserBuilder().setSigningKey(key).build();
        try {
            return jwtParser.parseClaimsJws(token).getBody();
        } catch (Exception e) {
            log.warn("token 解析失败：{}", e.getMessage());
            return null;
        }
    }
}
