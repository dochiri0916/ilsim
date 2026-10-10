package io.github.dochiri0916.buildconvention.validator.domain.vo

import io.github.dochiri0916.buildconvention.extension.HexagonalConventionExtension
import io.github.dochiri0916.buildconvention.source.kotlin.KotlinSourceInspector
import io.github.dochiri0916.buildconvention.validator.domain.KotlinDomainConventionValidator
import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class KotlinIdentifierRulesTest {
    private static final String ID = 'domain/vo/OrderId.kt'
    private static final String GUARD = 'adapter/in/UuidV7.kt'
    private static final String PORT = 'application/port/out/OrderIdGenerator.kt'
    private static final String GENERATOR = 'adapter/out/identifier/UuidV7OrderIdGenerator.kt'

    @TempDir
    File tempDir

    @Test
    @DisplayName('참조 ID와 복수 자체 ID는 입력 가드와 각 발급 포트·어댑터로 허용한다')
    void acceptsReferenceContractsAndAliases() {
        Project project = fixture()
        assert analyze(project).violations.isEmpty()
        source(project, 'adapter/in/web/Converter.kt').text = '''
            package com.example.saga.order.adapter.`in`.web
            import com.example.saga.order.adapter.`in`.requireV7
            fun convert(value: java.util.UUID) = value.requireV7("orderId")
        '''.stripIndent()
        assert analyze(project).violations.isEmpty()
        KotlinSourceInspector.collect(project).each { file ->
            file.text = file.text.replace('com.example.saga', 'net.inventory.shop')
        }
        assert analyze(project).violations.isEmpty()
        File generator = source(project, GENERATOR)
        generator.text = generator.text.replace('import com.github.f4b6a3.uuid.UuidCreator', 'import com.github.f4b6a3.uuid.UuidCreator as V7')
                .replace('import org.springframework.stereotype.Component', 'import org.springframework.stereotype.Component as Registered')
                .replace('@Component', '@Registered').replace('UuidCreator.getTimeOrderedEpoch()', 'V7.getTimeOrderedEpoch()')
        File guard = source(project, GUARD)
        guard.text = guard.text.replace('import java.util.UUID', 'import java.util.UUID as InputUuid')
                .replace(': UUID', ': InputUuid').replace('fun UUID.', 'fun InputUuid.')
                .replace('UUIDv7 형식이 아닙니다.', 'Invalid UUID v7:')
        File id = source(project, ID)
        id.text = id.text.replace('val value: UUID', '/* UUID wrapper */ val value: java.util.UUID')
                .replace('companion object {', 'companion object { // factories remain closed')
        assert analyze(project).violations.isEmpty()
    }

    @Test
    @DisplayName('ID는 정확한 단일 UUID 래퍼 외의 상태·생성자·메서드·팩터리를 거절한다')
    void rejectsAlternativeIdentifierShapes() {
        verify([
            [ID, 'value class OrderId', 'class OrderId'],
            [ID, 'value class OrderId', 'data class OrderId'],
            [ID, 'value class OrderId', 'open class OrderId'],
            [ID, 'value class OrderId private constructor(\n    val value: UUID\n)', 'interface OrderId'],
            [ID, 'private constructor', 'internal constructor'],
            [ID, 'private constructor', 'public constructor'],
            [ID, 'val value: UUID', 'val value: UUID?'],
            [ID, 'val value: UUID', 'var value: UUID'],
            [ID, 'val value: UUID', 'private val value: UUID'],
            [ID, 'val value: UUID', 'val raw: UUID'],
            [ID, 'val value: UUID', 'val value: String'],
            [ID, 'val value: UUID', 'val value: Long'],
            [ID, 'val value: UUID', 'val value: List<UUID>'],
            [ID, 'val value: UUID', 'val value: UUID = UUID.randomUUID()'],
            [ID, 'val value: UUID', 'val value: UUID, val extra: Int'],
            [ID, '@JvmInline', '@Deprecated("legacy")\n@JvmInline'],
            [ID, '@JvmInline', ''],
            [ID, 'import java.util.UUID', 'import fake.UUID'],
            [ID, '    companion object {', '    init { if (value.version() != 7) throw IllegalArgumentException() }\n    companion object {'],
            [ID, '    companion object {', '    val hint = "private constructor, UUID version 7"\n    companion object {'],
            [ID, '    companion object {', '    constructor(value: String) : this(UUID.fromString(value))\n    companion object {'],
            [ID, '    companion object {', '    fun parse(): UUID = value\n    companion object {'],
            [ID, '    companion object {', '    class NestedId\n    companion object {'],
            [ID, '    companion object {', '    companion object Factory {'],
            [ID, 'fun of(value: UUID)', 'private fun of(value: UUID)'],
            [ID, 'fun of(value: UUID)', 'internal fun of(value: UUID)'],
            [ID, 'fun of(value: UUID)', 'fun create(value: UUID)'],
            [ID, 'fun of(value: UUID)', 'fun of(value: UUID?)'],
            [ID, 'OrderId = OrderId(value)', 'OrderId = OrderId(UUID.randomUUID())'],
            [ID, 'OrderId = OrderId(value)', 'OrderId = run { OrderId(value) }'],
            [ID, 'OrderId = OrderId(value)', 'OrderId { val ignored = { OrderId(value) }; return OrderId(UUID.randomUUID()) }'],
            [ID, 'override fun toString(): String = value.toString()', 'override fun toString(): String = "constant"'],
            [ID, 'override fun toString(): String = value.toString()', ''],
            [ID, 'fun of(value: UUID): OrderId = OrderId(value)', 'fun of(value: UUID): OrderId = OrderId(value)\n        fun from(value: String): OrderId = OrderId(UUID.fromString(value))'],
            [ID, 'package com.example.saga.order.domain.vo', 'package com.example.saga.order.domain.model', 'ARCH-PACKAGE-TOPOLOGY'],
            [ID, 'OrderId', 'OrderKey'],
            [ID, 'value class OrderId private constructor(\n    val value: UUID\n)', 'object OrderId'],
        ], 'ARCH-DOMAIN-AGGREGATE')
    }

    @Test
    @DisplayName('Id 접미사를 바꾼 일반 UUID 래퍼도 ID의 선언 계약을 우회할 수 없다')
    void rejectsRenamedOrdinaryUuidWrapper() {
        Project project = fixture()
        source(project, 'domain/vo/AlternativeKey.kt').text = '''
            package com.example.saga.order.domain.vo
            import java.util.UUID
            class AlternativeKey private constructor(val value: UUID) {
                override fun equals(other: Any?): Boolean {
                    if (other !is AlternativeKey) return false
                    return value == other.value
                }
                override fun hashCode(): Int = value.hashCode()
                override fun toString(): String = value.toString()
                companion object { fun of(value: UUID): AlternativeKey = AlternativeKey(value) }
            }
        '''.stripIndent()
        assert analyze(project).violations.any { it.contains('identifier VO must be exactly') && it.contains('AlternativeKey.kt') }
    }

    @Test
    @DisplayName('입력 가드의 UUID v7·variant 검증 누락과 지연·죽은 분기 우회를 거절한다')
    void rejectsInboundGuardBypasses() {
        String check = 'if (version() != UUID_V7 || variant() != RFC_VARIANT) throw InvalidUuidV7Exception(field, this)'
        verify([
            [GUARD, 'UUID_V7 = 7', 'UUID_V7 = 4'],
            [GUARD, 'RFC_VARIANT = 2', 'RFC_VARIANT = 0'],
            [GUARD, 'private const val UUID_V7', 'const val UUID_V7'],
            [GUARD, 'private const val RFC_VARIANT', 'private var RFC_VARIANT'],
            [GUARD, ' || variant() != RFC_VARIANT', ''],
            [GUARD, 'version() != UUID_V7 || ', ''],
            [GUARD, ' || ', ' && '],
            [GUARD, 'version() != UUID_V7 || variant() != RFC_VARIANT', 'false'],
            [GUARD, check, "/* ${check} */"],
            [GUARD, check, "if (false) { ${check} }"],
            [GUARD, check, "val ignored = { ${check} }"],
            [GUARD, check, 'val hint = "version() != 7 || variant() != 2"'],
            [GUARD, check, "return this\n    ${check}"],
            [GUARD, check, "${check}; return UUID.randomUUID()"],
            [GUARD, 'return this', 'return UUID.randomUUID()'],
            [GUARD, 'throw InvalidUuidV7Exception(field, this)', 'throw IllegalArgumentException(field)'],
            [GUARD, 'throw InvalidUuidV7Exception(field, this)', 'throw InvalidUuidV7Exception("other", this)'],
            [GUARD, 'fun UUID.requireV7', 'private fun UUID.requireV7'],
            [GUARD, 'fun UUID.requireV7', 'fun UUID?.requireV7'],
            [GUARD, 'class InvalidUuidV7Exception', 'open class InvalidUuidV7Exception'],
            [GUARD, 'val field: String', 'var field: String'],
            [GUARD, 'IllegalArgumentException("UUIDv7 형식이 아닙니다. field=$field")', 'RuntimeException("$field")'],
            [GUARD, 'IllegalArgumentException("UUIDv7 형식이 아닙니다. field=$field")', 'IllegalArgumentException("invalid")'],
            [GUARD, 'IllegalArgumentException("UUIDv7 형식이 아닙니다. field=$field")', 'IllegalArgumentException("$field ${TODO()}")'],
            [GUARD, 'IllegalArgumentException("UUIDv7 형식이 아닙니다. field=$field")', 'IllegalArgumentException(field)'],
            [GUARD, 'IllegalArgumentException("UUIDv7 형식이 아닙니다. field=$field")', 'IllegalArgumentException'],
            [GUARD, 'class InvalidUuidV7Exception', 'class RenamedException'],
            [GUARD, 'package com.example.saga.order.adapter.`in`', 'package com.example.saga.order.application'],
            [GUARD, 'fun UUID.requireV7', 'fun UUID.validate'],
        ], 'ARCH-WEB-ADAPTER')
    }

    @Test
    @DisplayName('발급 포트·컴포넌트·UUID Creator의 단일 직접 경로 외의 구현을 거절한다')
    void rejectsGeneratorBypasses() {
        verify([
            [PORT, 'interface OrderIdGenerator', 'class OrderIdGenerator'],
            [PORT, 'fun generate(): OrderId', 'fun generate(): UUID'],
            [PORT, 'fun generate(): OrderId', 'fun generate(value: String): OrderId'],
            [PORT, 'fun generate(): OrderId', 'fun generate(): OrderId = TODO()'],
            [PORT, 'fun generate(): OrderId', 'fun generate(): OrderId\n    fun other(): OrderId'],
            [PORT, 'package com.example.saga.order.application.port.out', 'package com.example.saga.order.domain'],
            [GENERATOR, '@Component', ''],
            [GENERATOR, '@Component', '@Service'],
            [GENERATOR, 'class UuidV7OrderIdGenerator', 'open class UuidV7OrderIdGenerator'],
            [GENERATOR, 'class UuidV7OrderIdGenerator', 'object UuidV7OrderIdGenerator'],
            [GENERATOR, ': OrderIdGenerator', ': OtherIdGenerator'],
            [GENERATOR, 'override fun generate()', 'fun generate()'],
            [GENERATOR, 'override fun generate()', 'override fun generate(value: UUID)'],
            [GENERATOR, 'UuidCreator.getTimeOrderedEpoch()', 'java.util.UUID.randomUUID()'],
            [GENERATOR, 'UuidCreator.getTimeOrderedEpoch()', 'UuidCreator.getRandomBased()'],
            [GENERATOR, 'UuidCreator.getTimeOrderedEpoch()', 'UuidCreator.getTimeBased()'],
            [GENERATOR, 'OrderId.of(UuidCreator.getTimeOrderedEpoch())', 'OrderItemId.of(UuidCreator.getTimeOrderedEpoch())'],
            [GENERATOR, 'OrderId.of(UuidCreator.getTimeOrderedEpoch())', 'OrderId.of(java.util.UUID.fromString("00000000-0000-0000-0000-000000000000"))'],
            [GENERATOR, 'OrderId.of(UuidCreator.getTimeOrderedEpoch())', 'run { OrderId.of(UuidCreator.getTimeOrderedEpoch()) }'],
            [GENERATOR, 'OrderId.of(UuidCreator.getTimeOrderedEpoch())', 'TODO("OrderId.of(UuidCreator.getTimeOrderedEpoch())")'],
            [GENERATOR, 'override fun generate(): OrderId = OrderId.of(UuidCreator.getTimeOrderedEpoch())', 'override fun generate(): OrderId { val unused = { OrderId.of(UuidCreator.getTimeOrderedEpoch()) }; return TODO() }'],
            [GENERATOR, 'class UuidV7OrderIdGenerator :', 'class UuidV7OrderIdGenerator(val cached: OrderId) :'],
            [GENERATOR, 'package com.example.saga.order.adapter.out.identifier', 'package com.example.saga.order.application.service'],
            [GENERATOR, 'UuidV7OrderIdGenerator', 'AlternativeOrderIdGenerator'],
            [GENERATOR, '    override fun generate()', '    fun other(): String = "ignored"\n    override fun generate()'],
        ], 'ARCH-PORT-CONTRACT')
    }

    @Test
    @DisplayName('필수 경계 제거·타입 별칭·가짜 외부 타입·다른 계층의 UUID 발급을 거절한다')
    void rejectsMissingContractsAndAlternativePaths() {
        [PORT, GENERATOR, GUARD].each { path ->
            Project project = fixture()
            source(project, path).text = ''
            assert !analyze(project).violations.isEmpty(): path
        }
        List<String> additions = [
            'package com.example.saga.order.domain.vo\n typealias AlternateId = java.util.UUID',
            'package com.example.saga.order.domain.vo\n typealias RawUuid = java.util.UUID',
            'package com.example.saga.order.application\n import com.github.f4b6a3.uuid.UuidCreator\n fun generate() = UuidCreator.getTimeOrderedEpoch()',
            'package com.example.saga.order.application\n fun generate() = com.github.f4b6a3.uuid.UuidCreator.getTimeOrderedEpoch()',
            'package com.example.saga.order.application\n import com.github.f4b6a3.uuid.UuidCreator.getTimeOrderedEpoch\n fun generate() = getTimeOrderedEpoch()',
            'package com.example.saga.order.application\n import java.util.UUID.randomUUID as random\n fun generate() = random()',
            'package com.example.saga.order.application\n fun generate() = java.util.UUID.nameUUIDFromBytes(byteArrayOf())',
            'package com.example.saga.order.application\n fun generate() = java.util.UUID(0, 0)',
            'package com.example.saga.order.adapter.out.identifier\n fun generate() = TODO()',
            'package com.example.saga.order.adapter.out.identifier\n class UnknownGenerator',
            'package java.util\n class UUID',
            'package com.github.f4b6a3.uuid\n object UuidCreator',
            'package com.github.f4b6a3.uuid\n typealias UuidCreator = FakeCreator',
            'package com.example.saga.order.application.port.out\n interface StockReferenceIdGenerator',
            'package com.example.saga.order.adapter.out.identifier\n class UuidV7StockReferenceIdGenerator',
            'package com.example.saga.order.application\n fun requireV7() = TODO()',
            'package com.example.saga.order.application\n fun validate(value: java.util.UUID) = value.requireV7("id")',
            'package com.example.saga.order.adapter.invalid\n fun validate(value: java.util.UUID) = value.requireV7("id")',
            'package com.example.saga.order.domain.vo\n fun OrderId.Companion.of(value: String): OrderId = OrderId.of(java.util.UUID.fromString(value))',
            'package com.example.saga.order.domain.vo\n fun OrderId.raw(): String = value.toString()',
            'package com.example.saga.order.domain.vo\n fun OrderId.Companion?.of(value: String): OrderId = OrderId.of(java.util.UUID.fromString(value))',
            'package com.example.saga.order.application\n import com.example.saga.order.domain.vo.OrderId\n typealias Factory = OrderId.Companion',
            'package com.example.saga.order.application\n import com.example.saga.order.domain.vo.OrderId\n typealias Identifier = OrderId',
        ]
        additions.each { code ->
            Project project = fixture()
            source(project, 'Additional.kt').text = code
            Map result = analyze(project)
            assert !result.violations.isEmpty(): code
            assert result.violations.every { it.startsWith('[ARCH-') }
        }
        Project duplicate = fixture()
        source(duplicate, 'adapter/in/Extra.kt').text = source(duplicate, GUARD).text
        assert analyze(duplicate).violations.any { it.contains('exactly one') }
    }

    private void verify(List<List<String>> changes, String rule) {
        changes.each { row ->
            Project project = fixture()
            File file = source(project, row[0])
            assert file.text.contains(row[1]): row.toString()
            file.text = file.text.replace(row[1], row[2])
            Map result = analyze(project)
            String expected = row.size() > 3 ? row[3] : rule
            assert result.violations.any { it.startsWith("[${expected}]") }: "${row}: ${result.violations}"
            assert result.violations.every { it.startsWith('[ARCH-') }
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

    private static File source(Project project, String path) {
        File file = project.file("src/main/kotlin/com/example/saga/order/${path}")
        file.parentFile.mkdirs()
        return file
    }

    private static Map analyze(Project project) {
        KotlinDomainConventionValidator.analyze(project, KotlinSourceInspector.collect(project), new HexagonalConventionExtension())
    }
}
