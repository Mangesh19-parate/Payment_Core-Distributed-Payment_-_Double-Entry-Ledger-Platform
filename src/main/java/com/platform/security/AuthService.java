package com.platform.security;

import com.platform.common.error.BusinessException;
import com.platform.common.error.ErrorCode;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder, JwtTokenProvider jwtTokenProvider) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenProvider = jwtTokenProvider;
    }

    public AuthResponse login(String email, String password) {
        AuthenticatedUser user = userRepository.findByEmail(email)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED, "Invalid email or password"));

        if (!user.active()) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "User account is disabled");
        }

        if (!passwordEncoder.matches(password, user.passwordHash())) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "Invalid email or password");
        }

        String token = jwtTokenProvider.generateToken(user);
        return new AuthResponse(token, user.id(), user.email(), user.role().name());
    }

    public AuthResponse register(String email, String password, UserRole role) {
        if (userRepository.findByEmail(email).isPresent()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "Email already registered");
        }

        UUID userId = UUID.randomUUID();
        String hash = passwordEncoder.encode(password);
        AuthenticatedUser user = userRepository.createUser(userId, email, hash, role != null ? role : UserRole.ROLE_CUSTOMER);

        String token = jwtTokenProvider.generateToken(user);
        return new AuthResponse(token, user.id(), user.email(), user.role().name());
    }

    public record AuthResponse(String token, UUID userId, String email, String role) {}
}
