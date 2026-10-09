package io.github.dochiri0916.buildconvention.validator

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Pins the rule ID of every violation message template. Rule IDs are an external report contract, so a wording
 * change that silently reclassifies a message must fail here and be reviewed deliberately.
 * Regenerate with UPDATE_RULE_ID_CONTRACT=true ./gradlew test --tests '*RuleIdContractTest' after an intentional change.
 */
class RuleIdContractTest {

    // Validators live in the core and Java modules; the contract covers both.
    private static final List<File> SOURCE_ROOTS = [new File('src/main/groovy'), new File('../core/src/main/groovy')]
    private static final File GOLDEN = new File('src/test/resources/rule-id-contract.txt')

    @Test
    @DisplayName('모든 위반 메시지 템플릿의 rule ID가 기록된 계약과 일치한다')
    void ruleIdsMatchRecordedContract() {
        // given
        List<String> actual = currentContract()

        // when
        if (System.getenv('UPDATE_RULE_ID_CONTRACT') == 'true') {
            GOLDEN.text = actual.join('\n') + '\n'
        }

        // then
        assert !actual.isEmpty()
        assert GOLDEN.readLines('UTF-8') == actual
    }

    private static List<String> currentContract() {
        Set<String> lines = new TreeSet<>()
        List<File> files = []
        SOURCE_ROOTS.each { File sourceRoot -> sourceRoot.eachFileRecurse { File file -> files.add(file) } }
        files.each { File file ->
            if (!file.name.endsWith('.groovy') || !file.path.contains('/validator/')) {
                return
            }
            def matcher = file.getText('UTF-8') =~ /(?s)\.add\(\s*"((?:[^"\\]|\\.)*)"\s*\)/
            while (matcher.find()) {
                String template = matcher.group(1).replaceAll(/\$\{[^}]*\}/, 'X')
                if (!template.startsWith('X ')) {
                    continue
                }
                String message = "src/main/java/A.java ${template.substring(2)}"
                lines.add("${ConventionRuleCatalog.ruleId(message)}\t${template}".toString())
            }
        }
        return lines.toList()
    }
}
