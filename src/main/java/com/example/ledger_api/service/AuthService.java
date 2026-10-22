package com.example.ledger_api.service;

import com.example.ledger_api.domain.AppUser;
import com.example.ledger_api.dto.auth.AuthResponse;
import com.example.ledger_api.dto.auth.LoginRequest;
import com.example.ledger_api.dto.auth.RegisterRequest;
import com.example.ledger_api.dto.auth.UserResponse;
import com.example.ledger_api.exception.ConflictException;
import com.example.ledger_api.exception.UnauthorizedException;
import com.example.ledger_api.repository.UserRepository;
import com.example.ledger_api.security.JwtService;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            JwtService jwtService
    ) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    @Transactional
    public UserResponse register(RegisterRequest request) {
        String email = normalizeEmail(request.email());

        if (userRepository.existsByEmail(email)) {
            throw new ConflictException("Email is already registered");
        }

        AppUser user = new AppUser(
                UUID.randomUUID(),
                email,
                passwordEncoder.encode(request.password()),
                Instant.now());

        try {
            userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException ex) {
            // Handles the race where two registrations for the same email happen concurrently.
            throw new ConflictException("Email is already registered");
        }

        return toUserResponse(user);
    }

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {
        String email = normalizeEmail(request.email());

        AppUser user = userRepository.findByEmail(email)
                .orElseThrow(() -> new UnauthorizedException("Invalid email or password"));

        boolean passwordMatches;
        try {
            passwordMatches = passwordEncoder.matches(request.password(), user.getPasswordHash());
        } catch (IllegalArgumentException ex) {
            // System/internal users may have a non-BCrypt placeholder hash.
            passwordMatches = false;
        }

        if (!passwordMatches) {
            throw new UnauthorizedException("Invalid email or password");
        }

        String token = jwtService.generateToken(user);

        return new AuthResponse(
                token,
                "Bearer",
                jwtService.expirationSeconds(),
                toUserResponse(user));
    }

    private String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private UserResponse toUserResponse(AppUser user) {
        return new UserResponse(user.getId(), user.getEmail(), user.getCreatedAt());
    }
}