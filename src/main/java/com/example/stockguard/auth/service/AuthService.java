package com.example.stockguard.auth.service;

import com.example.stockguard.auth.dto.*;
import com.example.stockguard.user.entity.UserAccount;
import com.example.stockguard.user.repository.UserRepository;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AuthService {
    private final UserRepository users;
    private final PasswordEncoder passwords;
    private final JwtEncoder encoder;
    private final String issuer;
    private final String dummyHash;

    public AuthService(UserRepository users, PasswordEncoder passwords, JwtEncoder encoder,
                       @Value("${stockguard.jwt.issuer}") String issuer) {
        this.users = users;
        this.passwords = passwords;
        this.encoder = encoder;
        this.issuer = issuer;
        this.dummyHash = passwords.encode(java.util.UUID.randomUUID().toString());
    }

    @Transactional
    public UserResponse register(RegisterRequest request) {
        var user = users.saveAndFlush(new UserAccount(normalize(request.email()), passwords.encode(request.password())));
        return new UserResponse(user.getId(), user.getEmail(), user.getRole());
    }

    @Transactional(readOnly = true)
    public TokenResponse login(LoginRequest request) {
        var account = users.findByEmail(normalize(request.email()));
        String hash = account.map(UserAccount::getPasswordHash).orElse(dummyHash);
        boolean matches = passwords.matches(request.password(), hash);
        if (!matches || account.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid email or password.");
        }
        var user = account.orElseThrow();
        Instant now = Instant.now();
        var claims = JwtClaimsSet.builder().issuer(issuer).subject(user.getId().toString())
                .audience(List.of("stockguard-api")).issuedAt(now).expiresAt(now.plusSeconds(900))
                .claim("role", user.getRole()).build();
        var token = encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims));
        return new TokenResponse(token.getTokenValue(), "Bearer", 900);
    }

    private static String normalize(String email) {
        return email.strip().toLowerCase(Locale.ROOT);
    }
}
