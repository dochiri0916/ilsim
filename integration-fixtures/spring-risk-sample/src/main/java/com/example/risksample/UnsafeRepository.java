package com.example.risksample;

import org.springframework.data.jpa.repository.JpaRepository;

public interface UnsafeRepository extends JpaRepository<UnsafeRecord, Long> {
    boolean existsByBusinessKey(String businessKey);
    long countByBusinessKey(String businessKey);
}
