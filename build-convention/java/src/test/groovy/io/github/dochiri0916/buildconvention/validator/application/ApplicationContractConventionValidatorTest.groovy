package io.github.dochiri0916.buildconvention.validator.application

import io.github.dochiri0916.buildconvention.source.java.JavaSourceAstInspector
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ApplicationContractConventionValidatorTest {
    @TempDir
    File tempDir

    @Test
    @DisplayName('컬렉션 한 개를 방어 복사하는 class와 record 계약을 허용한다')
    void accepts_immutable_first_class_collections() {
        String recordSource = '''
            public record CartResultItems(java.util.List<CartItem> values) {
                public CartResultItems { values = java.util.List.copyOf(values); }
            }
        '''
        String classSource = '''
            public final class CartResultItems {
                private final java.util.List<CartItem> values;
                private CartResultItems(final java.util.List<CartItem> values) {
                    this.values = java.util.List.copyOf(values);
                }
                public static CartResultItems of(final java.util.List<CartItem> values) { return new CartResultItems(values); }
                public java.util.List<CartItem> values() { return values; }
            }
        '''

        List<String> recordViolations = validate('CartResultItems', recordSource, 'port.in.result')
        List<String> classViolations = validate('CartResultItems', classSource, 'port.out')

        assert recordViolations.isEmpty()
        assert classViolations.isEmpty()
    }

    @Test
    @DisplayName('DTO 필드와 Port 인자 및 반환의 원시 컬렉션과 Optional 우회를 거부한다')
    void rejects_raw_collection_contracts() {
        String result = 'public record CartResult(String id, java.util.List<CartItem> items) {}'
        String port = 'public interface CartPort { java.util.Optional<java.util.List<CartItem>> find(); void save(java.util.Collection<CartItem> items); }'

        List<String> resultViolations = validate('CartResult', result, 'port.in.result')
        List<String> portViolations = validate('CartPort', port, 'port.out')

        assert resultViolations.any { it.contains('wrap each collection') }
        assert portViolations.count { it.contains('must use first-class collections') } == 2
    }

    @Test
    @DisplayName('별도 Query를 통한 조회와 조건 없는 조회는 허용하고 원시 조회 인자와 빈 Query는 거부한다')
    void validates_query_contracts() {
        String validQuery = 'public record GetCartQuery(String memberId) {}'
        String validUseCase = '@FunctionalInterface public interface GetCartUseCase { CartResult get(final GetCartQuery query); }'

        List<String> queryViolations = validate('GetCartQuery', validQuery, 'port.in.query')
        List<String> useCaseViolations = validate('GetCartUseCase', validUseCase, 'port.in.usecase')

        assert queryViolations.isEmpty()
        assert useCaseViolations.isEmpty()
        assert validate('GetCartUseCase', validUseCase.replace('final GetCartQuery query', ''), 'port.in.usecase').isEmpty()
        assert validate('GetCartUseCase', validUseCase.replace('GetCartQuery', 'String'), 'port.in.usecase')
                .any { it.contains('one Query contract') }
        assert validate('GetCartQuery', 'public record GetCartQuery() {}', 'port.in.query')
                .any { it.contains('instead of an empty contract') }
        assert validate('GetCartUseCase', validUseCase.replace('@FunctionalInterface', ''), 'port.in.usecase')
                .any { it.contains('@FunctionalInterface') }
    }

    @Test
    @DisplayName('저장소는 값 객체와 메타데이터만 받고 검색 조건은 port.out record다')
    void validates_repository_values_and_search_conditions() {
        String port = 'public interface CartRepositoryPort { Cart find(final CartId id, final int page, final long sequence); }'

        List<String> violations = validate('CartRepositoryPort', port, 'port.out')

        assert violations.findAll { !it.contains('@FunctionalInterface') }.isEmpty()
        assert validate('CartRepositoryPort', port.replace('CartId id', 'String id'), 'port.out')
                .any { it.contains("parameter 'id' must use a domain Value Object") }
        assert validate('CartSearchCondition', 'public record CartSearchCondition(Status status) {}', 'port.out').isEmpty()
        assert validate('CartSearchCondition', 'public record CartSearchCondition(int page, int size) {}', 'port.out')
                .any { it.contains('pagination-only queries must not create a SearchCondition') }
        assert validate('CartSearchCondition', 'public record CartSearchCondition(Status status) {}', 'port.in.query')
                .any { it.contains('record in application.port.out') }
    }

    @Test
    @DisplayName('서비스는 ResultMapper를 쓰고 restore 업무 우회와 직접 Result 조립을 거부한다')
    void validates_service_orchestration() {
        String valid = 'public final class GetCartService { public CartResult get(final GetCartQuery query) { return CartResultMapper.toResult(cart); } }'

        List<String> violations = validate('GetCartService', valid, 'service')

        assert violations.isEmpty()
        assert validate('GetCartService', valid.replace('CartResultMapper.toResult(cart)', 'new CartResult(cart)'), 'service')
                .any { it.contains('application ResultMapper') }
        assert validate('GetCartService', valid.replace('CartResultMapper.toResult(cart)', 'Cart.restore(id)'), 'service')
                .any { it.contains('state transitions with restore') }
        assert validate('GetCartService', valid.replace('return CartResultMapper', 'String hint = "new CartResult() Cart.restore(id)"; /* new CartResult() */ return CartResultMapper'), 'service').isEmpty()
    }

    @Test
    @DisplayName('공개 통합 이벤트의 복사된 외부 목록을 허용하고 모델 노출은 거부한다')
    void validates_published_event_exception() {
        String source = '''
            public record CartChanged(String id, java.util.List<ItemDto> items) {
                public CartChanged { items = java.util.List.copyOf(items); }
            }
        '''

        List<String> violations = validate('CartChanged', source, 'port.out.event')

        assert violations.isEmpty()
        assert validate('CartChanged', source.replace('String id', 'com.example.cart.domain.vo.CartId id'), 'port.out.event')
                .any { it.contains('not domain/application models') }
        assert validate('CartChanged', source.replace('items = java.util.List.copyOf(items);', ''), 'port.out.event')
                .any { it.contains('defensively copy') }
    }

    private List<String> validate(String name, String source, String child) {
        def project = ProjectBuilder.builder().withProjectDir(tempDir).build()
        File file = new File(tempDir, "${name}.java")
        file.text = "package com.example.cart.application.${child};\n${source}"
        def inspection = JavaSourceAstInspector.inspect(file)
        assert inspection.valid
        ApplicationContractConventionValidator.validate(project, file, inspection.packageName, inspection.primaryType())
    }
}
