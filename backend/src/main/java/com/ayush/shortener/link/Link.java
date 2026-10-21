package com.ayush.shortener.link;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

/**
 * The id is assigned up front from links_id_seq (the generated short code is derived from it),
 * so the entity implements Persistable to stop Spring Data issuing a pointless SELECT before each INSERT.
 */
@Entity
@Table(name = "links")
public class Link implements Persistable<Long> {

    @Id
    private Long id;

    @Column(nullable = false, updatable = false)
    private String code;

    @Column(name = "long_url", nullable = false, updatable = false)
    private String longUrl;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(nullable = false, updatable = false)
    private boolean custom;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Transient
    private boolean isNew = true;

    protected Link() {
    }

    public Link(long id, String code, String longUrl, long userId, boolean custom, Instant expiresAt, Instant now) {
        this.id = id;
        this.code = code;
        this.longUrl = longUrl;
        this.userId = userId;
        this.custom = custom;
        this.active = true;
        this.expiresAt = expiresAt;
        this.createdAt = now;
    }

    public boolean isUsableAt(Instant now) {
        return active && (expiresAt == null || expiresAt.isAfter(now));
    }

    public void deactivate() {
        this.active = false;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        this.isNew = false;
    }

    @Override
    public Long getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    public String getCode() {
        return code;
    }

    public String getLongUrl() {
        return longUrl;
    }

    public Long getUserId() {
        return userId;
    }

    public boolean isCustom() {
        return custom;
    }

    public boolean isActive() {
        return active;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
