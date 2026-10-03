package com.example.risksample;

import jakarta.persistence.*;

@Entity
@Table(name = "safe_record")
public class SafeRecord {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, unique = true)
    private String businessKey;

    protected SafeRecord() {}
    public SafeRecord(String businessKey) { this.businessKey = businessKey; }
}
