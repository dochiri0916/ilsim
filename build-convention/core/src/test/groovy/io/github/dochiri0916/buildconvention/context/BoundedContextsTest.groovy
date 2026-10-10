package io.github.dochiri0916.buildconvention.context

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class BoundedContextsTest {
    @Test
    @DisplayName('루트를 모르면 첫 계층 세그먼트 바로 앞 세그먼트를 컨텍스트로 본다')
    void resolves_context_before_first_layer_segment() {
        // given
        String type = 'com.example.order.domain.Order'

        // when
        String context = BoundedContexts.contextOf(type)

        // then
        assert context == 'order'
        assert BoundedContexts.contextOf('com.example.order.adapter.out.persistence.OrderEntity', 'adapter.out') == 'order'
        assert BoundedContexts.contextOf('com.example.order.domain.Order', 'application') == null
    }

    @Test
    @DisplayName('이름이 비었거나 계층 세그먼트 앞에 패키지가 없으면 컨텍스트가 없다')
    void returns_no_context_without_a_layer_under_a_package() {
        // given
        List<String> names = [null, '', '  ', 'com.example.order.Order', 'domain.Order']

        // when
        List<String> contexts = names.collect { String name -> BoundedContexts.contextOf(name) }

        // then
        assert contexts.every { it == null }
    }

    @Test
    @DisplayName('루트 아래 묶음 컨텍스트의 모듈들은 같은 컨텍스트에 속한다')
    void groups_modules_under_the_application_root() {
        // given
        Set<String> roots = ['com.example', 'com', null, ' '] as Set

        // when
        List<String> contexts = BoundedContexts.withRoots(roots) {
            [BoundedContexts.contextOf('com.example.catalog.category.domain.Category'),
             BoundedContexts.contextOf('com.example.catalog.product.domain.Product'),
             BoundedContexts.contextOf('org.other.billing.domain.Invoice'),
             BoundedContexts.contextOf('com.example.domain.Money')]
        }

        // then
        assert contexts == ['catalog', 'catalog', 'billing', 'example']
    }

    @Test
    @DisplayName('루트 지정은 블록이 끝나거나 실패하면 이전 값으로 돌아간다')
    void restores_previous_roots_after_the_block() {
        // given
        String type = 'com.example.catalog.product.domain.Product'

        // when
        try {
            BoundedContexts.withRoots(['com.example'] as Set) { throw new IllegalStateException('failed') }
        } catch (IllegalStateException ignored) {
            // The failure only ends the block.
        }
        String inNullRoots = BoundedContexts.withRoots(null) { BoundedContexts.contextOf(type) }

        // then
        assert inNullRoots == 'product'
        assert BoundedContexts.contextOf(type) == 'product'
    }
}
