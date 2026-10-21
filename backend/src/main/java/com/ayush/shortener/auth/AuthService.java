package com.ayush.shortener.auth;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Locale;

import com.ayush.shortener.auth.GoogleIdTokenVerifier.GoogleProfile;
import com.ayush.shortener.common.ApiException;
import com.ayush.shortener.user.User;
import com.ayush.shortener.user.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    /** bcrypt silently ignores everything past 72 bytes, so longer passwords are refused outright. */
    private static final int MAX_PASSWORD_BYTES = 72;
    private static final int MIN_PASSWORD_CHARS = 10;
    private static final String BAD_CREDENTIALS = "Invalid email or password";

    /** A signed-in user plus the two credentials to hand to the browser. */
    public record Session(User user, String accessToken, String refreshToken) {
    }

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokens;
    private final GoogleIdTokenVerifier googleVerifier;
    private final Clock clock;
    /** Compared against when the email is unknown, so "no such user" costs the same time as "wrong password". */
    private final String dummyHash;

    AuthService(UserRepository users, PasswordEncoder passwordEncoder, JwtService jwtService,
                RefreshTokenService refreshTokens, GoogleIdTokenVerifier googleVerifier, Clock clock) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.refreshTokens = refreshTokens;
        this.googleVerifier = googleVerifier;
        this.clock = clock;
        this.dummyHash = passwordEncoder.encode("not-a-real-password");
    }

    public Session register(String email, String password, String name) {
        String normalized = normalizeEmail(email);
        validateNewPassword(password);
        if (users.existsByEmail(normalized)) {
            throw ApiException.conflict("An account with this email already exists");
        }
        String displayName = (name == null || name.isBlank()) ? normalized.substring(0, normalized.indexOf('@')) : name.trim();
        User user;
        try {
            user = users.save(User.local(normalized, displayName, passwordEncoder.encode(password), clock.instant()));
        } catch (DataIntegrityViolationException e) {
            throw ApiException.conflict("An account with this email already exists");
        }
        return startSession(user);
    }

    public Session login(String email, String password) {
        User user = users.findByEmail(normalizeEmail(email)).orElse(null);
        String hash = (user == null || user.getPasswordHash() == null) ? dummyHash : user.getPasswordHash();
        boolean passwordOk = password.getBytes(StandardCharsets.UTF_8).length <= MAX_PASSWORD_BYTES
                && passwordEncoder.matches(password, hash);
        if (user == null || user.getPasswordHash() == null || !passwordOk) {
            // Same message for unknown email, Google-only account and wrong password.
            throw ApiException.unauthorized(BAD_CREDENTIALS);
        }
        user.recordLogin(clock.instant());
        users.save(user);
        return startSession(user);
    }

    public Session loginWithGoogle(String idToken) {
        GoogleProfile profile = googleVerifier.verify(idToken);
        String email = normalizeEmail(profile.email());
        var now = clock.instant();

        User user = users.findByGoogleSub(profile.sub()).orElse(null);
        if (user != null) {
            user.recordLogin(email, profile.name(), profile.picture(), now);
        } else {
            User existing = users.findByEmail(email).orElse(null);
            if (existing != null) {
                if (existing.getGoogleSub() != null) {
                    // Same email already tied to a different Google identity: don't merge.
                    throw ApiException.conflict("This email is already linked to another Google account");
                }
                // Password account, same email: Google proves ownership, so adopt it and drop the password.
                existing.linkGoogle(profile.sub(), profile.name(), profile.picture(), now);
                refreshTokens.revokeAllForUser(existing.getId());
                user = existing;
            } else {
                user = new User(profile.sub(), email, profile.name(), profile.picture(), now);
            }
        }
        try {
            user = users.save(user);
        } catch (DataIntegrityViolationException e) {
            throw ApiException.conflict("Could not complete sign-in, please try again");
        }
        return startSession(user);
    }

    public Session refresh(String refreshToken) {
        RefreshTokenService.Issued issued = refreshTokens.rotate(refreshToken);
        User user = users.findById(issued.userId())
                .orElseThrow(() -> ApiException.unauthorized("Invalid session"));
        return new Session(user, jwtService.issueAccessToken(user.getId()), issued.rawToken());
    }

    public void logout(String refreshToken) {
        if (refreshToken != null && !refreshToken.isBlank()) {
            refreshTokens.revoke(refreshToken);
        }
    }

    public User currentUser(long userId) {
        return users.findById(userId).orElseThrow(() -> ApiException.unauthorized("Invalid session"));
    }

    private Session startSession(User user) {
        return new Session(user, jwtService.issueAccessToken(user.getId()), refreshTokens.start(user.getId()));
    }

    private static String normalizeEmail(String email) {
        String normalized = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        int at = normalized.indexOf('@');
        if (at < 1 || at == normalized.length() - 1) {
            throw ApiException.badRequest("Email is not valid");
        }
        return normalized;
    }

    private static void validateNewPassword(String password) {
        if (password == null || password.strip().length() < MIN_PASSWORD_CHARS) {
            throw ApiException.badRequest("Password must be at least " + MIN_PASSWORD_CHARS + " characters");
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
            throw ApiException.badRequest("Password must be at most " + MAX_PASSWORD_BYTES + " bytes");
        }
    }
}
