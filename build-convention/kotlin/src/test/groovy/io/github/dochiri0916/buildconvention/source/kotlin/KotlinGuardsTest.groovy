package io.github.dochiri0916.buildconvention.source.kotlin

import org.jetbrains.kotlin.psi.KtNamedFunction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class KotlinGuardsTest {
    @TempDir
    File tempDir

    @Test
    @DisplayName('가드 조건의 경계 비교와 enum 정책을 해석하고 알 수 없는 연산은 증거로 인정하지 않는다')
    void evaluatesBoundaryConditionsConservatively() {
        List cases = [
            ['(value >= 1)', [value: 1L], true],
            ['value > 0', [value: 0L], false],
            ['value <= 0', [value: -1L], true],
            ['value < 1', [value: 1L], false],
            ['value != 0', [value: -1L], true],
            ['value == 0', [value: 0L], true],
            ['-value', [value: 1L], -1L],
            ['true && false', [:], false],
            ['true || false', [:], true],
            ['false', [:], false],
            ['null', [:], null],
            ['1_000L', [:], 1000L],
            ['0x7', [:], 7L],
            ['1.5', [:], null],
            ['values.isNotEmpty()', [values: [empty: false]], true],
            ['values.isNotEmpty()', [values: [:]], null],
            ['values.isEmpty()', [values: [empty: true]], true],
            ['value.version', [value: [version: 7]], 7],
            ['status.canTransitionTo(target)', [status: [canTransitionTo: false]], false],
            ['this == PENDING && target != PENDING', [this: 'PENDING', PENDING: 'PENDING', target: 'COMPLETED'], true],
            ['unknown(value)', [value: 1L], null],
            ['value + 1', [value: 1L], null],
            ['value < missing', [value: 1L], null],
            ['!value', [value: 1L], null],
            ['-value', [value: false], null],
            ['value.isEmpty()', [value: 1L], null],
            ['value && true', [value: 1L], null],
            ['value || false', [value: 1L], null],
            ['value < true', [value: 1L], null],
            ['value <= true', [value: 1L], null],
            ['value > true', [value: 1L], null],
            ['value >= true', [value: 1L], null],
        ]
        File file = new File(tempDir, 'Conditions.kt')
        file.text = cases.withIndex().collect { row, index -> "fun condition${index}() = ${row[0]}" }.join('\n')
        KotlinSourceAstInspector.inspect([file]) { inspections ->
            assert inspections.first().errors.isEmpty()
            def functions = inspections.first().ast.declarations
            cases.eachWithIndex { row, index ->
                assert KotlinGuards.evaluate(functions[index].bodyExpression, row[1]) == row[2]: row.toString()
            }
        }
    }

    @Test
    @DisplayName('UUID 가드 평가기는 버전과 variant의 모든 조합 및 정상 값 거절을 구분한다')
    void evaluatesUuidGuardsExhaustively() {
        File file = new File(tempDir, 'UuidConditions.kt')
        file.text = '''
            fun valid(value: UUID) { if (value.version() != 7 || value.variant() != 2) throw DomainException() }
            fun wrongVersion(value: UUID) { if (value.version() != 4 || value.variant() != 2) throw DomainException() }
            fun wrongVariant(value: UUID) { if (value.version() != 7) throw DomainException() }
        '''.stripIndent()
        KotlinSourceAstInspector.inspect([file]) { inspections ->
            def functions = inspections.first().ast.declarations
            functions.eachWithIndex { function, index ->
                assert KotlinGuards.validatesUuid(KotlinGuards.guards([function.bodyBlockExpression]), 'value',
                        { it.thrownExpression?.text == 'DomainException()' }) == (index == 0)
            }
        }
    }

    @Test
    @DisplayName('직접 throw만 증명하고 조기 반환과 중첩 가드는 실패로 판단한다')
    void rejectsUnexecutedThrowEvidence() {
        List cases = [
            ['if (value < 1) { throw DomainException() }', true],
            ['if (value < 1) { consume(value); throw DomainException() }', true],
            ['if (value < 1) { return; throw DomainException() }', false],
            ['if (value < 1) { consume(value) }', false],
            ['if (value < 1) {}', false],
            ['if (false) { if (value < 1) throw DomainException() }', false],
            ['val deferred = { if (value < 1) throw DomainException() }', false],
            ['if (value >= 1) {} else { throw DomainException() }', true],
        ]
        File file = new File(tempDir, 'Guards.kt')
        file.text = cases.withIndex().collect { row, index -> "fun guard${index}(value: Int) { ${row[0]} }" }.join('\n')
        KotlinSourceAstInspector.inspect([file]) { inspections ->
            def functions = KotlinSourceAstInspector.nodes(inspections.first().ast, KtNamedFunction)
            cases.eachWithIndex { row, index ->
                def guards = KotlinGuards.guards([functions[index].bodyBlockExpression])
                assert KotlinGuards.rejects(guards, [value: 0L], { it.thrownExpression?.text == 'DomainException()' }) == row[1]
            }
        }
    }
}
