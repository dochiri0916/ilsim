package io.github.dochiri0916.buildconvention.validator.adapter.web

import io.github.dochiri0916.buildconvention.source.java.JavaSourceAstInspector
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class WebExceptionConventionValidatorTest {
    @TempDir File directory

    private static final String IMPORTS = '''
        import org.springframework.web.bind.annotation.RestControllerAdvice;
        import org.springframework.web.bind.annotation.ExceptionHandler;
        import org.springframework.core.Ordered;
        import org.springframework.core.annotation.Order;
        import org.springframework.http.HttpStatus;
        import org.springframework.http.ProblemDetail;
        import com.example.global.exception.ProblemDetails;
    '''
    private static final String FALLBACK = '''
        @RestControllerAdvice @Order(Ordered.LOWEST_PRECEDENCE)
        public final class GlobalAdvice {
            private static final String CODE = "INTERNAL_ERROR";
            private static final String DETAIL = "일시적인 오류가 발생했습니다";
            private final ProblemDetails problems = null;
            @ExceptionHandler(Exception.class)
            public ProblemDetail handle(final Exception exception, final Request request) {
                log.error("실패 uri={} method={}", request.getRequestURI(), request.getMethod(), exception);
                return problems.create(HttpStatus.INTERNAL_SERVER_ERROR, CODE, DETAIL);
            }
        }
    '''

    @Test
    @DisplayName('전역 처리기는 500 공통 메시지와 URI·메서드·원인 예외를 기록한다')
    void accepts_safe_fallback() {
        def violations = validate(FALLBACK)
        assert violations.empty
    }

    @Test
    @DisplayName('잘못된 우선순위와 가짜 Ordered 상수 및 스택 없는 로그를 거부한다')
    void rejects_precedence_and_stack_bypasses() {
        def precedence = validate(FALLBACK.replace('LOWEST_PRECEDENCE', 'HIGHEST_PRECEDENCE'))
        def fake = validate(FALLBACK, IMPORTS.replace('org.springframework.core.Ordered', 'fake.Ordered'))
        def stack = validate(FALLBACK.replace('request.getMethod(), exception', 'request.getMethod(), request'))
        assert precedence.any { it.contains('LOWEST_PRECEDENCE') }
        assert fake.any { it.contains('must declare @Order') }
        assert stack.any { it.contains('original exception') }
    }

    @Test
    @DisplayName('저장소·미처리 예외는 내부 메시지와 다른 상태·오류 코드를 노출하지 않는다')
    void rejects_technical_detail_leaks() {
        [['HttpStatus.INTERNAL_SERVER_ERROR', 'HttpStatus.BAD_REQUEST'],
         ['CODE, DETAIL', '"SECRET", DETAIL'],
         ['CODE, DETAIL', 'CODE, exception.getMessage()']].each { replacement ->
            assert validate(FALLBACK.replace(replacement[0], replacement[1])).any { it.contains('constant Korean message') }
        }
        assert validate(FALLBACK.replace('"INTERNAL_ERROR"', '"DATABASE_FAILURE"')).any { it.contains('INTERNAL_ERROR') }
    }

    @Test
    @DisplayName('업무 처리기는 높은 우선순위와 공통 응답 팩터리·info 또는 warn 로그를 쓴다')
    void accepts_business_helper_and_rejects_direct_problem_detail() {
        String source = '''
            @RestControllerAdvice @Order(Ordered.HIGHEST_PRECEDENCE)
            public final class BookAdvice {
                private final ProblemDetails problems = null;
                @ExceptionHandler(BookException.class)
                public ProblemDetail handle(final BookException exception) { return respond(exception); }
                private ProblemDetail respond(final BookException exception) {
                    log.info("업무 오류");
                    return problems.create(HttpStatus.BAD_REQUEST, "INVALID_INPUT", exception.getMessage());
                }
            }
        '''
        def valid = validate(source)
        def invalid = validate(source.replace('problems.create(HttpStatus.BAD_REQUEST, "INVALID_INPUT", exception.getMessage())',
                'ProblemDetail.forStatusAndDetail(400, exception.getMessage())').replace('log.info', 'log.error'))
        assert valid.empty
        assert invalid.any { it.contains('created by ProblemDetails.create') }
        assert invalid.any { it.contains('must not construct ProblemDetail directly') }
        assert invalid.any { it.contains('info or warn') }
    }

    @Test
    @DisplayName('공통 오류 팩터리는 문자열 속 가짜 호출로 code·timestamp를 대체할 수 없다')
    void requires_actual_problem_properties() {
        String source = '''
            public final class ProblemDetails {
                public ProblemDetail create() {
                    ProblemDetail detail = ProblemDetail.forStatus(400);
                    detail.setProperty("code", "INVALID_INPUT");
                    detail.setProperty("timestamp", Instant.now(clock));
                    return detail;
                }
            }
        '''
        def valid = validate(source, '', 'com.example.global.exception')
        def invalid = validate(source.replace('detail.setProperty("timestamp", Instant.now(clock));',
                'String hint = "setProperty timestamp";'), '', 'com.example.global.exception')
        assert valid.empty
        assert invalid.any { it.contains('code and timestamp') }
    }

    private List<String> validate(String source, String imports = IMPORTS,
                                  String packageName = 'com.example.book.adapter.in.web') {
        File file = new File(directory, 'Advice.java')
        file.text = 'package ' + packageName + ';\n' + imports + source
        def inspection = JavaSourceAstInspector.inspect(file)
        assert inspection.valid: inspection.errors
        return WebExceptionConventionValidator.validate('Advice.java', packageName,
                inspection.primaryType(), inspection.allImports)
    }
}
