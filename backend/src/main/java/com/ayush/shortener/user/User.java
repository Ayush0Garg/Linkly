package com.ayush.shortener.user;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Null for accounts that only have a password. */
    @Column(name = "google_sub")
    private String googleSub;

    /** Null for Google-only accounts. */
    @Column(name = "password_hash")
    private String passwordHash;

    @Column(nullable = false)
    private String email;

    private String name;

    @Column(name = "avatar_url")
    private String avatarUrl;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    protected User() {
    }

    public User(String googleSub, String email, String name, String avatarUrl, Instant now) {
        this.googleSub = googleSub;
        this.email = email;
        this.name = name;
        this.avatarUrl = avatarUrl;
        this.createdAt = now;
        this.lastLoginAt = now;
    }

    public static User local(String email, String name, String passwordHash, Instant now) {
        User user = new User(null, email, name, null, now);
        user.passwordHash = passwordHash;
        return user;
    }

    /**
     * Attach a Google identity to an existing password account. Google has proven control of the email,
     * so any password set before that is discarded: otherwise whoever registered the address first (without
     * verifying it) would keep access to the real owner's account.
     */
    public void linkGoogle(String googleSub, String name, String avatarUrl, Instant now) {
        this.googleSub = googleSub;
        this.passwordHash = null;
        recordLogin(this.email, name, avatarUrl, now);
    }

    public void recordLogin(Instant now) {
        this.lastLoginAt = now;
    }

    /** Called on each Google login so the profile stays in sync. */
    public void recordLogin(String email, String name, String avatarUrl, Instant now) {
        this.email = email;
        this.name = name;
        this.avatarUrl = avatarUrl;
        this.lastLoginAt = now;
    }

    public Long getId() {
        return id;
    }

    public String getGoogleSub() {
        return googleSub;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getEmail() {
        return email;
    }

    public String getName() {
        return name;
    }

    public String getAvatarUrl() {
        return avatarUrl;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLastLoginAt() {
        return lastLoginAt;
    }
}
