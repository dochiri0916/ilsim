package com.dochiri.convention.support

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder

class SourceInspectorTest {

    @TempDir
    File tempDir

    @Test
    @DisplayName('세미콜론 없는 Groovy 패키지와 import를 파싱한다')
    void parsesGroovyPackageAndImportsWithoutSemicolon() {
        // given
        String source = '''
                package com.example.order.application.service

                import com.example.order.application.port.in.PlaceOrderUseCase
                import static java.util.Objects.requireNonNull

                final class PlaceOrderService {
                }
                '''.stripIndent()

        // when
        String packageName = SourceInspector.extractPackageName(source)
        List<String> imports = SourceInspector.extractImports(source)

        // then
        assert packageName == 'com.example.order.application.service'
        assert imports == [
                'com.example.order.application.port.in.PlaceOrderUseCase',
                'java.util.Objects.requireNonNull'
        ]
    }

    @Test
    @DisplayName('Java와 Groovy main source를 함께 수집하고 다른 파일은 제외한다')
    void collectsJavaAndGroovyMainSources() {
        // given
        Project project = ProjectBuilder.builder().withProjectDir(tempDir).build()
        writeFile('src/main/java/com/example/Order.java', 'class Order {}')
        writeFile('src/main/groovy/com/example/OrderSpec.groovy', 'class OrderSpec {}')
        writeFile('src/main/resources/application.yml', 'spring: {}')

        // when
        List<File> files = SourceInspector.collectMainSourceFiles(project)

        // then
        assert files*.name.toSet() == ['Order.java', 'OrderSpec.groovy'] as Set
    }

    @Test
    @DisplayName('레이어 segment는 완전한 package segment로만 판정한다')
    void matchesOnlyCompleteLayerSegments() {
        // given
        List<String> matchingValues = ['domain', 'com.example.domain', 'com.example.domain.model']

        // when
        List<Boolean> matches = matchingValues.collect { value -> SourceInspector.isInLayer(value, 'domain') }

        // then
        assert matches.every { matched -> matched }
        assert !SourceInspector.isInLayer('com.example.domains.model', 'domain')
        assert !SourceInspector.isInLayer(null, 'domain')
        assert !SourceInspector.isInLayer('', 'domain')
        assert !SourceInspector.isInLayer('com.example.domain', null)
        assert !SourceInspector.isInLayer('com.example.domain', '')
    }

    @Test
    @DisplayName('JPA Entity class와 Table 이름을 fully-qualified annotation에서도 추출한다')
    void extractsJpaEntityMetadata() {
        // given
        String source = '''
                @jakarta.persistence.Entity
                @jakarta.persistence.Table(
                    name = "orders"
                )
                public final class OrderEntity {
                }
                '''.stripIndent()

        // when
        boolean entity = SourceInspector.isEntityClass(source)
        String className = SourceInspector.extractClassName(source)
        String tableName = SourceInspector.extractTableName(source)

        // then
        assert entity
        assert className == 'OrderEntity'
        assert tableName == 'orders'
        assert SourceInspector.extractClassName('public record Order() {}') == null
        assert SourceInspector.extractTableName('@Entity class OrderEntity {}') == null
    }

    private void writeFile(String path, String content) {
        File file = new File(tempDir, path)
        file.parentFile.mkdirs()
        file.text = content
    }
}
