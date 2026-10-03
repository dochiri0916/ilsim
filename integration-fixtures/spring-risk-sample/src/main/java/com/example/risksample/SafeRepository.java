package com.example.risksample;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SafeRepository extends JpaRepository<SafeRecord, Long> {
    boolean existsByBusinessKey(String businessKey);
    long countByBusinessKey(String businessKey);
}
