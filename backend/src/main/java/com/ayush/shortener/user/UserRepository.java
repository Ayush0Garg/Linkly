package com.ayush.shortener.user;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByGoogleSub(String googleSub);

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);
}
