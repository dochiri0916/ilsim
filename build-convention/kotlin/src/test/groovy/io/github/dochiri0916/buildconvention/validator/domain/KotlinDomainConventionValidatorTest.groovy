package io.github.dochiri0916.buildconvention.validator.domain

import io.github.dochiri0916.buildconvention.extension.HexagonalConventionExtension
import io.github.dochiri0916.buildconvention.source.kotlin.KotlinSourceInspector
import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class KotlinDomainConventionValidatorTest {
    @TempDir
    File tempDir

    @Test
    @DisplayName('주문 도메인 18개 파일과 ID 경계 예제를 실제 분석하고 허용한다')
    void acceptsReferenceDomain() {
        Project project = fixture()
        Map result = analyze(project)
        assert result.files == 23
        assert result.domainFiles == 18
        assert result.violations.isEmpty(): result.violations.join('\n')
    }

    @Test
    @DisplayName('패키지와 도메인 이름이 바뀌어도 동일한 규칙으로 검사한다')
    void acceptsRenamedPackagesAndTypes() {
        Project project = fixture()
        KotlinSourceInspector.collect(project).each { File file ->
            String source = file.text.replace('com.example.saga', 'net.demo.shop').replaceAll(/Order(?=[A-Z]|\b)/, 'Purchase')
            file.text = source
        }
        assert analyze(project).violations.isEmpty()
    }

    @Test
    @DisplayName('도메인 불변성 식별자 동등성 팩터리 및 상태 전이 위반을 검출한다')
    void rejectsDomainMutations() {
        List<List<String>> mutations = [
            ['Order.kt', 'private constructor(', 'constructor(', 'private constructors'],
            ['Order.kt', 'val status: OrderStatus,', 'var status: OrderStatus,', 'immutable val'],
            ['Order.kt', 'return id == other.id', 'return status == other.status', 'equals must use id only'],
            ['Order.kt', 'return id == other.id', 'return id == other.id || true', 'compare the actual identity'],
            ['Order.kt', 'return id == other.id', 'id == other.id; return true', 'equals must use id only'],
            ['Order.kt', 'override fun hashCode(): Int = id.hashCode()', 'override fun hashCode(): Int = items.hashCode()', 'hashCode must use id only'],
            ['Order.kt', '        if (!status.canTransitionTo(target)) throw InvalidOrderStatusTransitionException(status, target)', '', 'enum state changes'],
            ['Order.kt', '!status.canTransitionTo(target)', 'status.canTransitionTo(target)', 'rejects disallowed transitions'],
            ['Order.kt', 'status.canTransitionTo(target)', 'status.canTransitionTo(OrderStatus.PENDING)', 'rejects disallowed transitions'],
            ['Order.kt', 'Order = Order(id, items, status)', 'Order = Order(id, items, OrderStatus.PENDING)', 'preserve every supplied'],
            ['Order.kt', 'return Order(id, items, target)', 'return Order(status = target, items = items, id = id)', null],
            ['Order.kt', 'return Order(id, items, target)', 'return Order(OrderId.of(java.util.UUID.randomUUID()), items, target)', 'preserve id'],
            ['Order.kt', 'fun restore(', 'fun rehydrate(', 'create/register and restore'],
            ['Order.kt', 'fun register(', 'fun create(', null],
            ['OrderItem.kt', 'class OrderItem', 'data class OrderItem', 'data class copy'],
            ['OrderItem.kt', 'quantity == other.quantity && ', '', 'equals must use all primary'],
            ['OrderItem.kt', 'private constructor(', 'constructor(', 'private constructors'],
            ['vo/OrderStatus.kt', 'this == PENDING && target != PENDING', 'true', 'enum transition policy'],
            ['vo/OrderStatus.kt', 'fun canTransitionTo', 'var mutable: Int = 0\n\n    fun canTransitionTo', 'immutable val'],
            ['vo/OrderStatus.kt', 'fun canTransitionTo', 'fun failure(): Nothing = throw IllegalArgumentException()\n\n    fun canTransitionTo', 'context\'s domain exception'],
            ['vo/OrderStatus.kt', 'package com.example.saga.order.domain.vo', 'package com.example.saga.order.domain.model', 'state enums must live'],
        ]
        verifyMutations(mutations)
    }

    @Test
    @DisplayName('ID 래퍼 수량 단가 생성자 및 도메인 예외 위반을 검출한다')
    void rejectsValueObjectMutations() {
        List<List<String>> mutations = [
            ['vo/OrderId.kt', 'private constructor(', 'constructor(', 'identifier VO must be exactly'],
            ['vo/OrderId.kt', 'val value: UUID', 'var value: UUID', 'identifier VO must be exactly'],
            ['vo/OrderId.kt', 'val value: UUID', 'val value: String', 'identifier VO must be exactly'],
            ['vo/OrderId.kt', 'val value: UUID', 'val value: UUID?', 'identifier VO must be exactly'],
            ['vo/OrderId.kt', '@JvmInline', '', 'identifier VO must be exactly'],
            ['vo/OrderId.kt', 'fun of(', 'private fun of(', 'identifier VO must be exactly'],
            ['vo/OrderId.kt', 'fun of(', 'fun create(', 'identifier VO must be exactly'],
            ['vo/StockReferenceId.kt', 'val value: UUID', 'val value: UUID?', 'identifier VO must be exactly'],
            ['vo/OrderQuantity.kt', 'if (value < 1) throw InvalidOrderQuantityException(value)', '', 'validate'],
            ['vo/OrderQuantity.kt', 'value < 1', 'value < 0', 'quantity must be positive'],
            ['vo/OrderQuantity.kt', 'value < 1', 'value == -1 || value == 0', 'quantity must be positive'],
            ['vo/OrderQuantity.kt', 'value < 1', 'value <= 0', null],
            ['vo/UnitPrice.kt', 'if (value < 0) throw NegativeUnitPriceException(value)', '', 'validate'],
            ['vo/UnitPrice.kt', 'value < 0', 'value < -1', 'price must be non-negative'],
            ['vo/UnitPrice.kt', 'value < 0', 'value == -1', 'price must be non-negative'],
            ['vo/UnitPrice.kt', 'value < 0', 'value < 1', 'price must be non-negative'],
            ['vo/UnitPrice.kt', 'throw NegativeUnitPriceException(value)', 'throw IllegalArgumentException()', 'context\'s domain exception'],
            ['exception/OrderDomainException.kt', 'sealed class', 'open class', 'exception base must be sealed'],
            ['exception/DuplicateOrderItemException.kt', 'class DuplicateOrderItemException', 'open class DuplicateOrderItemException', 'exception subclasses must be final'],
            ['exception/OrderDomainException.kt', 'OrderDomainException(message:', 'OrderDomainException public constructor(message:', 'protected constructor'],
        ]
        verifyMutations(mutations)
    }

    @Test
    @DisplayName('일급 컬렉션 방어 복사 빈 입력 중복 식별자와 금액 오버플로 우회를 검출한다')
    void rejectsCollectionMutations() {
        verifyMutations([
            ['OrderItems.kt', 'OrderItems(java.util.List.copyOf(values))', 'OrderItems(values)', 'immutable snapshot'],
            ['OrderItems.kt', 'java.util.List.copyOf(values)', 'values.toList()', 'immutable snapshot'],
            ['OrderItems.kt', '        if (values.isEmpty()) throw OrderItemsRequiredException()', '', 'reject empty input'],
            ['OrderItems.kt', 'values.isEmpty()', '!values.isEmpty()', 'reject empty input'],
            ['OrderItems.kt', '        values.forEach { if (!seen.add(it.stockId)) throw DuplicateOrderItemException(it.stockId.toString()) }', '', 'duplicate identifiers'],
            ['OrderItems.kt', '!seen.add(it.stockId)', 'seen.add(it.stockId)', 'duplicate identifiers'],
            ['OrderItems.kt', 'values.forEach { if (!seen.add(it.stockId)) throw DuplicateOrderItemException(it.stockId.toString()) }', 'values.forEach { if (false) { if (!seen.add(it.stockId)) throw DuplicateOrderItemException(it.stockId.toString()) } }', 'duplicate identifiers'],
            ['OrderItems.kt', 'Math.addExact(total, item.amount)', 'total + item.amount', 'checked addition'],
            ['OrderItems.kt', 'private val values: List<OrderItem>', 'private val values: MutableList<OrderItem>', 'mutable collection type'],
            ['OrderItems.kt', 'fun asList(): List<OrderItem> = values', 'fun asList(): List<OrderItem> { values.clear(); return values }', 'must not be mutated'],
        ])
    }

    @Test
    @DisplayName('ID의 import 별칭 완전한 타입 이름과 수량의 동치 조건을 정확히 분석한다')
    void acceptsEquivalentSyntax() {
        Project project = fixture()
        File id = domainFile(project, 'vo/OrderId.kt')
        id.text = id.text.replace('import java.util.UUID', 'import java.util.UUID as StableUuid\nimport kotlin.jvm.JvmInline as InlineValue')
                .replace('@JvmInline', '@InlineValue').replace(': UUID', ': StableUuid')
        File quantity = domainFile(project, 'vo/OrderQuantity.kt')
        quantity.text = quantity.text.replace('value < 1', '(value <= 0)')
        assert analyze(project).violations.isEmpty()
        id.text = id.text.replace(': StableUuid', ': java.util.UUID')
        assert analyze(project).violations.isEmpty()
    }

    @Test
    @DisplayName('구문 오류 중복 타입 프레임워크 및 외부 계층 의존을 실패로 보고한다')
    void failsMalformedAndForbiddenSources() {
        verifyMutations([
            ['vo/OrderId.kt', 'fun of(value: UUID)', 'fun of(value: UUID', 'cannot be parsed as Kotlin'],
            ['Order.kt', 'import com.example.saga.order.domain.vo.OrderId', 'import org.springframework.stereotype.Component\nimport com.example.saga.order.domain.vo.OrderId', 'domain must not depend'],
            ['Order.kt', 'import com.example.saga.order.domain.vo.OrderId', 'import com.example.saga.order.application.port.out.OrderIdGenerator\nimport com.example.saga.order.domain.vo.OrderId', 'domain must not depend'],
            ['Order.kt', 'import com.example.saga.order.domain.vo.OrderId', 'import com.example.saga.inventory.domain.vo.StockId\nimport com.example.saga.order.domain.vo.OrderId', 'another context'],
            ['vo/UnitPrice.kt', 'package com.example.saga.order.domain.vo', 'package other.model', 'domain source must declare'],
        ])
        Project project = fixture()
        domainFile(project, 'Duplicate.kt').text = domainFile(project, 'Order.kt').text
        assert analyze(project).violations.any { it.contains('duplicate Kotlin type') }
        Map unreadable = KotlinDomainConventionValidator.analyze(project, [project.file('missing.kt')], new HexagonalConventionExtension())
        assert unreadable.violations.any { it.contains('cannot be parsed as Kotlin') }
    }

    @Test
    @DisplayName('when으로 쓴 전이 정책을 평가해 허용하고 결과가 고정된 when은 거부한다')
    void evaluatesWhenTransitionPolicies() {
        String policy = '= this == PENDING && target != PENDING'
        verifyMutations([
            ['vo/OrderStatus.kt', policy, '= when (this) {\n        PENDING -> target == COMPLETED || target == CANCELLED\n        COMPLETED, CANCELLED -> false\n    }', null],
            ['vo/OrderStatus.kt', policy, '= when {\n        this == PENDING -> target != PENDING\n        else -> false\n    }', null],
            ['vo/OrderStatus.kt', policy, '= when (this) {\n        PENDING -> true\n        else -> true\n    }', 'transition policy'],
            ['vo/OrderStatus.kt', policy, '= when (this) {\n        PENDING -> false\n        COMPLETED, CANCELLED -> false\n    }', 'transition policy'],
        ])
    }

    @Test
    @DisplayName('넘침을 검사하는 값 객체의 연산자로 합산하는 fold를 허용하고 검사를 벗어난 합산은 거부한다')
    void acceptsCheckedValueOperatorTotals() {
        Project project = amountFixture()
        assert analyze(project).violations.isEmpty()

        project = amountFixture()
        File amount = domainFile(project, 'vo/OrderAmount.kt')
        amount.text = replaceOnce(amount.text, 'Math.addExact(value, other.value)', 'value + other.value')
        assert analyze(project).violations.any { it.contains('checked addition') }

        project = amountFixture()
        File items = domainFile(project, 'OrderItems.kt')
        items.text = replaceOnce(items.text, 'total + OrderAmount.of(item.amount)', 'OrderAmount.of(total.value + item.amount)')
        assert analyze(project).violations.any { it.contains('checked addition') }

        project = amountFixture()
        items = domainFile(project, 'OrderItems.kt')
        items.text = replaceOnce(items.text, 'values.fold(OrderAmount.ZERO) { total, item -> total + OrderAmount.of(item.amount) }',
                'OrderAmount.of(values.fold(0L) { total, item -> total + item.amount })')
        assert analyze(project).violations.any { it.contains('checked addition') }
    }

    @Test
    @DisplayName('도메인 이벤트는 domain.event 타입과 Change 결과만 담을 수 있고 애그리거트는 보관할 수 없다')
    void restrictsDomainEventCarriers() {
        Project project = eventFixture()
        assert analyze(project).violations.isEmpty()

        project = eventFixture()
        File order = domainFile(project, 'Order.kt')
        order.text = replaceOnce(order.text, '    val status: OrderStatus,\n',
                '    val status: OrderStatus,\n    val events: List<com.example.saga.order.domain.event.OrderEvent>,\n')
        assert analyze(project).violations.any { it.contains("aggregates must not retain domain events in 'events'") }

        project = eventFixture()
        File change = domainFile(project, 'OrderChange.kt')
        change.text = change.text.replace('OrderChange', 'OrderOutcome')
        assert analyze(project).violations.any { it.contains("may be carried only by domain.event types and {Aggregate}Change results, not 'events'") }
    }

    private Project amountFixture() {
        Project project = fixture()
        domainFile(project, 'vo/OrderAmount.kt').text = '''\
            package com.example.saga.order.domain.vo

            import com.example.saga.order.domain.exception.OrderAmountOverflowException

            @JvmInline
            value class OrderAmount private constructor(
                val value: Long
            ) {

                init {
                    if (value < 0) throw OrderAmountOverflowException()
                }

                operator fun plus(other: OrderAmount): OrderAmount {
                    val sum = try {
                        Math.addExact(value, other.value)
                    } catch (e: ArithmeticException) {
                        throw OrderAmountOverflowException()
                    }
                    return OrderAmount(sum)
                }

                override fun toString(): String = value.toString()

                companion object {
                    val ZERO = OrderAmount(0L)

                    fun of(value: Long): OrderAmount = OrderAmount(value)
                }
            }
            '''.stripIndent()
        File items = domainFile(project, 'OrderItems.kt')
        String folded = (items.text =~ /(?s)totalAmount = values\.fold\(0L\) \{.*?\n        \}\n/)[0]
        items.text = replaceOnce(replaceOnce(replaceOnce(items.text,
                folded, 'totalAmount = values.fold(OrderAmount.ZERO) { total, item -> total + OrderAmount.of(item.amount) }\n'),
                'val totalAmount: Long', 'val totalAmount: OrderAmount'),
                'package com.example.saga.order.domain\n', 'package com.example.saga.order.domain\n\nimport com.example.saga.order.domain.vo.OrderAmount\n')
        File order = domainFile(project, 'Order.kt')
        order.text = replaceOnce(order.text, 'get() = items.totalAmount', 'get() = items.totalAmount.value')
        return project
    }

    private Project eventFixture() {
        Project project = fixture()
        domainFile(project, 'event').mkdirs()
        domainFile(project, 'event/OrderEvent.kt').text = '''\
            package com.example.saga.order.domain.event

            import com.example.saga.order.domain.vo.OrderId

            sealed interface OrderEvent {
                val orderId: OrderId
            }

            data class OrderCompleted(override val orderId: OrderId) : OrderEvent
            '''.stripIndent()
        domainFile(project, 'event/OrderEvents.kt').text = '''\
            package com.example.saga.order.domain.event

            class OrderEvents private constructor(
                private val values: List<OrderEvent>
            ) {

                fun asList(): List<OrderEvent> = values

                override fun equals(other: Any?): Boolean {
                    if (this === other) return true
                    if (other !is OrderEvents) return false
                    return values == other.values
                }

                override fun hashCode(): Int = values.hashCode()

                companion object {
                    fun of(values: List<OrderEvent>): OrderEvents = OrderEvents(java.util.List.copyOf(values))

                    fun empty(): OrderEvents = of(emptyList())
                }
            }
            '''.stripIndent()
        domainFile(project, 'OrderChange.kt').text = '''\
            package com.example.saga.order.domain

            import com.example.saga.order.domain.event.OrderEvents

            class OrderChange private constructor(
                val order: Order,
                val events: OrderEvents,
            ) {

                override fun equals(other: Any?): Boolean {
                    if (this === other) return true
                    if (other !is OrderChange) return false
                    return order == other.order && events == other.events
                }

                override fun hashCode(): Int = 31 * order.hashCode() + events.hashCode()

                companion object {
                    fun of(order: Order, events: OrderEvents): OrderChange = OrderChange(order, events)
                }
            }
            '''.stripIndent()
        return project
    }

    private static String replaceOnce(String text, String target, String replacement) {
        assert text.count(target) == 1: target
        return text.replace(target, replacement)
    }

    private void verifyMutations(List<List<String>> mutations) {
        mutations.each { row ->
            Project project = fixture()
            File file = domainFile(project, row[0])
            assert file.text.count(row[1]) == 1: row.toString()
            file.text = file.text.replace(row[1], row[2])
            Map result = analyze(project)
            if (row[3] == null) {
                assert result.violations.isEmpty(): "${row}: ${result.violations}"
            } else {
                assert result.violations.any { it.contains(row[3]) }: "${row}: ${result.violations}"
                assert result.violations.every { it.startsWith('[ARCH-') }
            }
        }
    }

    private Project fixture() {
        File dir = new File(tempDir, UUID.randomUUID().toString())
        dir.mkdirs()
        Project project = ProjectBuilder.builder().withProjectDir(dir).build()
        project.copy { spec ->
            spec.from(System.getProperty('kotlinConventionTemplates'))
            spec.into(dir)
        }
        return project
    }

    private static File domainFile(Project project, String name) {
        project.file("src/main/kotlin/com/example/saga/order/domain/${name}")
    }

    private static Map analyze(Project project) {
        KotlinDomainConventionValidator.analyze(project, KotlinSourceInspector.collect(project), new HexagonalConventionExtension())
    }
}
