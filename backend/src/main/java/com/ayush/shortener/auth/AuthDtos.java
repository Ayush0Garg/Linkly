package com.ayush.shortener.auth;

import com.ayush.shortener.user.User;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Request/response shapes for /api/auth. */
final class AuthDtos {

    private AuthDtos() {
    }

    record RegisterRequest(
            @NotBlank @Email @Size(max = 320) String email,
            @NotBlank @Size(min = 10, max = 128) String password,
            @Size(max = 100) String name) {
    }

    record LoginRequest(
            @NotBlank @Size(max = 320) String email,
            @NotBlank @Size(max = 128) String password) {
    }

    record GoogleLoginRequest(@NotBlank @Size(max = 4096) String idToken) {
    }

    record UserResponse(long id, String email, String name, String avatarUrl) {
        static UserResponse from(User user) {
            return new UserResponse(user.getId(), user.getEmail(), user.getName(), user.getAvatarUrl());
        }
    }
}
