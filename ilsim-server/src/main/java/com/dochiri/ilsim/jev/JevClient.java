package com.dochiri.ilsim.jev;

import io.github.gudcks0305.jev.ChoiceAnswer;
import io.github.gudcks0305.jev.ChoiceQuestion;
import io.github.gudcks0305.jev.JevException;
import io.github.gudcks0305.jev.NoulAnswer;
import io.github.gudcks0305.jev.NoulQuestion;
import io.github.gudcks0305.jev.ScoreAnswer;
import io.github.gudcks0305.jev.ScoreQuestion;
import io.github.gudcks0305.jev.typesafe.TypeSafeJevClient;
import com.dochiri.ilsim.secret.SecretService;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * TypeSafe JEV(결정 모델) 어댑터. code-risk-analyzer 가 쓰는 jev-typesafe 라이브러리 위에서 질문 3종(boolean, choice, score)을
 * 한 번에 보내고 확률·선택·점수를 돌려받는다. JEV 는 텍스트를 만들지 않고 판단만 하므로 결과는 판단 근거로만 쓰고 최종 결정은 정책이 한다.
 * 키가 없거나 호출이 실패하면 빈 값을 돌려주어 호출한 쪽이 규칙 기반 동작으로 되돌아가게 한다. 키는 환경 변수에서만 읽는다.
 */
@Component
public class JevClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(JevClient.class);

    public record Question(String type, String instructions, Object criteria) {
        public static Question score(String instructions, List<String> levels) {
            return new Question("score", instructions, levels);
        }

        public static Question choice(String instructions, Map<String, String> options) {
            return new Question("choice", instructions, options);
        }

        public static Question bool(String instructions) {
            return new Question("boolean", instructions, null);
        }
    }

    /** 질문 종류에 따라 일부 필드만 채워진다: boolean=probability, choice=choice+probabilities, score=score+probabilities. */
    public record Answer(String type, Double probability, String choice, Map<String, Double> probabilities, Double score) {}

    private final io.github.gudcks0305.jev.JevClient fixed;
    private final SecretService secrets;
    private volatile String currentKey;
    private volatile io.github.gudcks0305.jev.JevClient current;

    /** 웹에서 등록해 MySQL 에 암호화 저장한 키를 쓴다. 없으면 환경 변수 TYPESAFE_API_KEY(또는 JEV_API_KEY)로 되돌아간다. */
    @Autowired
    public JevClient(SecretService secrets) {
        this.fixed = null;
        this.secrets = secrets;
    }

    /** 테스트용: 이미 만든 라이브러리 클라이언트(또는 null=비활성)를 그대로 쓴다. */
    public JevClient(io.github.gudcks0305.jev.JevClient lib) {
        this.fixed = lib;
        this.secrets = null;
    }

    private String resolveKey() {
        String stored = null;
        try {
            stored = secrets.get(SecretService.JEV_API_KEY).orElse(null);
        } catch (RuntimeException e) {
            log.warn("JEV 키 저장소를 읽지 못했습니다: {}", e.getMessage());
        }
        return firstNonBlank(stored, System.getenv("TYPESAFE_API_KEY"), System.getenv("JEV_API_KEY"));
    }

    /** 키가 바뀌면 클라이언트를 다시 만든다. */
    private synchronized io.github.gudcks0305.jev.JevClient lib() {
        if (secrets == null) return fixed;
        String key = resolveKey();
        if (key == null) {
            current = null;
            currentKey = null;
            return null;
        }
        if (!key.equals(currentKey)) {
            if (current != null) current.close();
            current = TypeSafeJevClient.builder().apiKey(key).timeout(Duration.ofSeconds(10)).maxRetries(1).build();
            currentKey = key;
        }
        return current;
    }

    public boolean enabled() {
        return lib() != null;
    }

    /** 질문 이름 → 답. 키가 없거나 호출·해석에 실패하면 비어 있다. */
    public Optional<Map<String, Answer>> evaluate(Object state, Map<String, Question> questions) {
        io.github.gudcks0305.jev.JevClient lib = lib();
        if (lib == null || questions.isEmpty()) return Optional.empty();
        try {
            Map<String, io.github.gudcks0305.jev.Question<?>> asked = new LinkedHashMap<>();
            questions.forEach((id, q) -> asked.put(id, convert(id, q)));
            var evaluation = lib.evaluate(state, new ArrayList<>(asked.values()).toArray(io.github.gudcks0305.jev.Question<?>[]::new));
            Map<String, Answer> out = new LinkedHashMap<>();
            asked.forEach((id, q) -> out.put(id, convert(evaluation.answers().get(id))));
            return Optional.of(out);
        } catch (JevException e) {
            log.warn("JEV 호출 실패: {} (HTTP {})", e.kind(), e.statusCode());
            return Optional.empty();
        } catch (RuntimeException e) {
            log.warn("JEV 호출 실패: {}", e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private static io.github.gudcks0305.jev.Question<?> convert(String id, Question q) {
        return switch (q.type()) {
            case "boolean" -> NoulQuestion.of(id, q.instructions());
            case "score" -> ScoreQuestion.of(id, q.instructions(), (List<?>) q.criteria());
            case "choice" -> ChoiceQuestion.of(id, q.instructions(), (Map<String, ?>) q.criteria());
            default -> throw new IllegalArgumentException("지원하지 않는 질문 종류: " + q.type());
        };
    }

    private static Answer convert(io.github.gudcks0305.jev.Answer a) {
        if (a instanceof NoulAnswer n) return new Answer("boolean", n.probability(), null, Map.of(), null);
        if (a instanceof ScoreAnswer s) {
            Map<String, Double> p = new LinkedHashMap<>();
            s.probabilities().forEach((k, v) -> p.put(String.valueOf(k), v));
            return new Answer("score", null, null, p, s.score());
        }
        if (a instanceof ChoiceAnswer<?> c) {
            Map<String, Double> p = new LinkedHashMap<>();
            c.probabilities().forEach((k, v) -> p.put(String.valueOf(k), v));
            return new Answer("choice", null, String.valueOf(c.choice()), p, null);
        }
        throw new IllegalStateException("알 수 없는 답 종류");
    }

    @Override
    public void close() {
        if (current != null) current.close();
        if (fixed != null) fixed.close();
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) if (v != null && !v.isBlank()) return v.trim();
        return null;
    }
}
