package io.github.dochiri0916.buildconvention.validator.adapter.web

import io.github.dochiri0916.buildconvention.source.java.JavaSourceAstInspector
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class WebAdapterConventionValidatorTest {
    @TempDir File directory

    @Test
    @DisplayName('한국어 Jakarta 필드 검증과 방어적 복사를 허용한다')
    void accepts_validated_immutable_request() {
        String source = '''
            import jakarta.validation.constraints.NotBlank;
            import jakarta.validation.constraints.NotEmpty;
            import jakarta.validation.constraints.Min;
            import java.util.List;
            public record CreateRequest(@NotBlank(message="이름은 필수입니다") String name,
                @NotEmpty(message="항목은 필수입니다") List<String> items,
                @Min(value=1, message="수량은 양수여야 합니다") int quantity) {
                public CreateRequest { items = List.copyOf(items); }
                public CreateCommand toCommand() { return new CreateCommand(name, items, quantity); }
            }
        '''
        def violations = validate(source)
        assert violations.empty
    }

    @Test
    @DisplayName('가짜 어노테이션과 주석 및 영어 기본 메시지로는 검증을 우회하지 못한다')
    void rejects_constraint_bypasses() {
        String source = '''
            import fake.NotBlank;
            import jakarta.validation.constraints.Min;
            public record CreateRequest(/* @NotBlank(message="필수") */ String name,
                @NotBlank(message="필수") String alias, @Min(1) int quantity) {}
        '''
        def violations = validate(source)
        assert violations.count { it.contains('must declare Jakarta') } == 2
        assert violations.any { it.contains('explicitly explain') }
    }

    @Test
    @DisplayName('요청 본문마다 실제 Valid 어노테이션을 요구한다')
    void requires_valid_on_each_request_body() {
        String source = '''
            import org.springframework.web.bind.annotation.*;
            import jakarta.validation.Valid;
            public final class Controller {
                public void valid(@Valid @RequestBody final CreateRequest request) {}
                public void invalid(/* @Valid */ @RequestBody final CreateRequest request) {}
            }
        '''
        def violations = validate(source)
        assert violations.size() == 1
        assert violations.first().contains('@Valid @RequestBody')
    }

    @Test
    @DisplayName('DTO 변환의 정적 방향과 복잡한 업무 분기를 거부한다')
    void rejects_conversion_direction_and_branches() {
        def request = validate('''
            public record CreateRequest() {
                public static CreateCommand toCommand() {
                    if (authorized()) return new CreateCommand();
                    return null;
                }
            }
        ''')
        def response = validate('''
            public record CreateResponse() { public CreateResponse from(CreateResult result) { return this; } }
        ''')
        assert request.any { it.contains('public instance') }
        assert request.any { it.contains('separate Mapper') }
        assert response.any { it.contains('public static from') }
    }

    @Test
    @DisplayName('port.in.result 패키지의 조회 결과 DTO도 Response 변환 입력으로 인정한다')
    void accepts_result_package_dto_as_response_conversion_input() {
        String source = '''
            import example.book.application.port.in.result.CategorySummary;
            public record CategoryListResponse(String publicId) {
                public static CategoryListResponse from(final CategorySummary result) { return new CategoryListResponse(result.publicId()); }
            }
        '''

        def violations = validate(source)

        assert !violations.any { it.contains('Response conversion') }
    }

    @Test
    @DisplayName('port.in.result 안에 중첩된 결과 타입도 Response 변환 입력으로 인정한다')
    void accepts_nested_result_type_as_response_conversion_input() {
        String source = '''
            import example.book.application.port.in.result.ListOrdersResult;
            public record OrderSummaryResponse(String publicId) {
                public static OrderSummaryResponse from(final ListOrdersResult.OrderSummary result) { return new OrderSummaryResponse(result.publicId()); }
            }
        '''

        def violations = validate(source)

        assert !violations.any { it.contains('Response conversion') }
    }

    private List<String> validate(String source) {
        File file = new File(directory, 'Contract.java')
        file.text = 'package example.book.adapter.in.web;\n' + source
        def inspection = JavaSourceAstInspector.inspect(file)
        assert inspection.valid: inspection.errors
        return WebAdapterConventionValidator.validate('Contract.java', inspection.packageName,
                inspection.primaryType(), inspection.imports)
    }
}
