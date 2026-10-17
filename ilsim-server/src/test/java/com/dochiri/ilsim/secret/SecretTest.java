package com.dochiri.ilsim.secret;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dochiri.ilsim.jev.JevClient;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SecretTest {

    private static byte[] key(int seed) {
        byte[] k = new byte[32];
        for (int i = 0; i < k.length; i++) k[i] = (byte) (i + seed);
        return k;
    }

    private static class MemoryRepository implements SecretRepository {
        final Map<String, Stored> rows = new HashMap<>();
        boolean down;

        @Override
        public Optional<Stored> find(String name) {
            if (down) throw new IllegalStateException("db down");
            return Optional.ofNullable(rows.get(name));
        }

        @Override
        public void save(String name, String cipherText, Instant updatedAt) {
            if (down) throw new IllegalStateException("db down");
            rows.put(name, new Stored(cipherText, updatedAt));
        }

        @Override
        public void delete(String name) {
            rows.remove(name);
        }
    }

    @Test
    void 암호화한_값은_평문이_드러나지_않고_같은_이름과_키로만_복호화된다() {
        var cipher = new SecretCipher(key(1));
        String stored = cipher.encrypt("jev-api-key", "tsk_live_abcdef123456");

        assertThat(stored).doesNotContain("tsk_live").doesNotContain("abcdef123456");
        assertThat(cipher.decrypt("jev-api-key", stored)).isEqualTo("tsk_live_abcdef123456");
        assertThat(cipher.encrypt("jev-api-key", "tsk_live_abcdef123456")).isNotEqualTo(stored); // iv 가 매번 다르다

        assertThatThrownBy(() -> cipher.decrypt("other-name", stored)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new SecretCipher(key(2)).decrypt("jev-api-key", stored)).isInstanceOf(IllegalStateException.class);
        char[] tampered = stored.toCharArray();
        tampered[stored.length() / 2] = tampered[stored.length() / 2] == 'A' ? 'B' : 'A';
        assertThatThrownBy(() -> cipher.decrypt("jev-api-key", new String(tampered))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 저장소에는_암호문만_가고_상태에는_마지막_네_자리만_나온다() {
        var repo = new MemoryRepository();
        var service = new SecretService(repo, new SecretCipher(key(1)));
        assertThat(service.status(SecretService.JEV_API_KEY).configured()).isFalse();

        service.put(SecretService.JEV_API_KEY, "tsk_live_abcdef123456");

        assertThat(repo.rows.get(SecretService.JEV_API_KEY).cipherText()).doesNotContain("abcdef");
        var status = service.status(SecretService.JEV_API_KEY);
        assertThat(status.configured()).isTrue();
        assertThat(status.last4()).isEqualTo("3456");
        assertThat(status.updatedAt()).isNotNull();
        assertThat(service.get(SecretService.JEV_API_KEY)).contains("tsk_live_abcdef123456");

        service.delete(SecretService.JEV_API_KEY);
        assertThat(service.get(SecretService.JEV_API_KEY)).isEmpty();
    }

    @Test
    void 잘못된_키_형식은_거절하고_DB_가_없으면_사용_불가로_알린다() {
        var repo = new MemoryRepository();
        var service = new SecretService(repo, new SecretCipher(key(1)));
        assertThatThrownBy(() -> service.put("jev-api-key", " ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.put("jev-api-key", "has space")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.put("jev-api-key", "x".repeat(600))).isInstanceOf(IllegalArgumentException.class);

        repo.down = true;
        assertThatThrownBy(() -> service.put("jev-api-key", "valid-key")).isInstanceOf(SecretService.Unavailable.class);
        assertThatThrownBy(() -> service.status("jev-api-key")).isInstanceOf(SecretService.Unavailable.class);
    }

    @Test
    void 설정_응답에는_키_값이_없고_Runner_용_조회만_값을_돌려준다() {
        var service = new SecretService(new MemoryRepository(), new SecretCipher(key(1)));
        var controller = new SettingsController(service);
        assertThat(controller.runnerKey().getStatusCode().value()).isEqualTo(204);

        var response = controller.put(new SettingsController.KeyRequest("  tsk_live_abcdef123456 "));
        assertThat(response.toString()).contains("3456").doesNotContain("tsk_live");
        assertThat(controller.runnerKey().getBody()).containsEntry("key", "tsk_live_abcdef123456");

        controller.delete();
        assertThat(controller.settings().toString()).contains("configured=false");
    }

    @Test
    void 저장한_키가_있으면_JEV_가_켜지고_지우면_꺼진다() {
        var service = new SecretService(new MemoryRepository(), new SecretCipher(key(1)));
        var jev = new JevClient(service);
        boolean envKey = System.getenv("TYPESAFE_API_KEY") != null || System.getenv("JEV_API_KEY") != null;
        if (!envKey) assertThat(jev.enabled()).isFalse();
        service.put(SecretService.JEV_API_KEY, "tsk_live_abcdef123456");
        assertThat(jev.enabled()).isTrue();
        service.delete(SecretService.JEV_API_KEY);
        if (!envKey) assertThat(jev.enabled()).isFalse();
    }
}
