package com.example.risksample;

import jakarta.persistence.*;

/** Intentionally has no business-key uniqueness constraint. */
@Entity
@Table(name = "unsafe_record")
public class UnsafeRecord {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false)
    private String businessKey;

    protected UnsafeRecord() {}
    public UnsafeRecord(String businessKey) { this.businessKey = businessKey; }
}
