package com.dochiri.ilsim.secret;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 비밀 값을 AES-256-GCM 으로 암호화한다. 암호문은 `base64(iv ‖ 암호문 ‖ 태그)` 이고, 이름을 추가 인증 데이터로 묶어
 * 다른 이름의 행으로 옮겨 붙이면 복호화가 실패한다. 마스터 키는 DB 에 두지 않는다:
 * 환경 변수 ILSIM_SECRET_KEY(아무 문자열, SHA-256 으로 32바이트화)가 있으면 그것을, 없으면 `~/.ilsim/server-secret.key` 를 쓰고 처음에 만든다.
 */
@Component
public class SecretCipher {

    private static final Logger log = LoggerFactory.getLogger(SecretCipher.class);
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKeySpec key;

    public SecretCipher() {
        this(loadMasterKey());
    }

    public SecretCipher(byte[] masterKey) {
        if (masterKey.length != 32) throw new IllegalArgumentException("마스터 키는 32바이트여야 합니다");
        this.key = new SecretKeySpec(masterKey, "AES");
    }

    public String encrypt(String name, String plain) {
        try {
            byte[] iv = new byte[IV_BYTES];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(name.getBytes(StandardCharsets.UTF_8));
            byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(encrypted, 0, out, iv.length, encrypted.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("암호화에 실패했습니다", e);
        }
    }

    public String decrypt(String name, String stored) {
        try {
            byte[] in = Base64.getDecoder().decode(stored);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, in, 0, IV_BYTES));
            cipher.updateAAD(name.getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(in, IV_BYTES, in.length - IV_BYTES), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("복호화에 실패했습니다. 마스터 키가 바뀌었거나 데이터가 손상되었습니다", e);
        }
    }

    private static byte[] loadMasterKey() {
        String env = System.getenv("ILSIM_SECRET_KEY");
        try {
            if (env != null && !env.isBlank()) return MessageDigest.getInstance("SHA-256").digest(env.getBytes(StandardCharsets.UTF_8));
            Path file = Path.of(System.getProperty("user.home"), ".ilsim", "server-secret.key");
            if (Files.exists(file)) return Base64.getDecoder().decode(Files.readString(file).trim());
            byte[] generated = new byte[32];
            RANDOM.nextBytes(generated);
            Files.createDirectories(file.getParent());
            Files.writeString(file, Base64.getEncoder().encodeToString(generated));
            try {
                Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException ignored) {
                // POSIX 가 아닌 파일 시스템
            }
            log.info("암호화 마스터 키를 새로 만들었습니다: {} (잃어버리면 저장된 키를 복호화할 수 없으니 백업하세요)", file);
            return generated;
        } catch (IOException | GeneralSecurityException e) {
            throw new IllegalStateException("마스터 키를 준비하지 못했습니다", e);
        }
    }
}
