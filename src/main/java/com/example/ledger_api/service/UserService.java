package com.example.ledger_api.service;

import com.example.ledger_api.domain.AppUser;
import com.example.ledger_api.exception.UnauthorizedException;
import com.example.ledger_api.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class UserService {

    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public AppUser requireByEmail(String email) {
        String normalizedEmail = email.toLowerCase(Locale.ROOT);

        return userRepository.findByEmail(normalizedEmail)
                .orElseThrow(() -> new UnauthorizedException("Authenticated user was not found"));
    }

    public UUID requireIdByEmail(String email) {
        return requireByEmail(email).getId();
    }
}