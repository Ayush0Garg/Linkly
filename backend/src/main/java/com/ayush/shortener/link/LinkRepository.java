package com.ayush.shortener.link;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface LinkRepository extends JpaRepository<Link, Long> {

    Optional<Link> findByCode(String code);

    boolean existsByCode(String code);

    Page<Link> findByUserId(Long userId, Pageable pageable);

    @Query(value = "select nextval('links_id_seq')", nativeQuery = true)
    long nextId();
}
