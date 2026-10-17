package com.dochiri.ilsim.secret;

import java.time.Instant;
import java.util.Optional;

/** 암호문만 저장한다. 평문과 마스터 키는 이 계층에 오지 않는다. */
public interface SecretRepository {

    record Stored(String cipherText, Instant updatedAt) {}

    Optional<Stored> find(String name);

    void save(String name, String cipherText, Instant updatedAt);

    void delete(String name);
}
