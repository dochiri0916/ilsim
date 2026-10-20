package io.github.dochiri0916.buildconvention.catalog

final class ConventionRuleCatalog {
    private ConventionRuleCatalog() {
    }

    static List<String> withRuleIds(List<String> violations) {
        violations.collect { String violation ->
            if (violation == null || violation ==~ /^\[[A-Z]+-[A-Z0-9-]+\].*/) {
                return violation
            }
            "[${ruleId(violation)}] ${violation}"
        }
    }

    static String ruleId(String violation) {
        List<Map<String, String>> rules = [
                [id: 'ARCH-CODE-STYLE', pattern: '^code convention'],
                [id: 'ARCH-MESSAGING', pattern: '^messaging'],
                [id: 'ARCH-QUERYDSL', pattern: '^Querydsl'],
                [id: 'ARCH-DOMAIN-AGGREGATE', pattern: 'domain Value Object factories|domain object .*state'],
                [id: 'ARCH-SOURCE-SYNTAX', pattern: 'cannot be parsed|syntax|parse'],
                [id: 'ARCH-PACKAGE-TOPOLOGY', pattern: 'package|base package|bounded context'],
                [id: 'ARCH-DOMAIN-DEPENDENCY', pattern: 'domain must not|domain .*depend|domain .*framework'],
                [id: 'ARCH-DOMAIN-AGGREGATE', pattern: 'aggregate|first-class collection|Value Object|restore|state changes'],
                [id: 'ARCH-APPLICATION-DEPENDENCY', pattern: 'application must not|application .*depend'],
                [id: 'ARCH-PORT-CONTRACT', pattern: 'port .*must|UseCase|repository port'],
                [id: 'ARCH-EXCEPTION-CONTRACT', pattern: 'exception|ProblemDetail|ErrorCode|BusinessException'],
                [id: 'ARCH-WEB-ADAPTER', pattern: 'controller|API DTO|Spring Web|web adapter'],
                [id: 'ARCH-PERSISTENCE-ADAPTER', pattern: 'JPA|persistence|Entity|Mapper'],
                [id: 'ARCH-TRANSACTION-BOUNDARY', pattern: 'transaction|Transactional|I/O|side effect'],
                [id: 'ARCH-TEST-CONVENTION', pattern: '\\b(?:test|tests|given|when|then|assert)\\b'],
                [id: 'ARCH-QUALITY-GATE', pattern: 'coverage|mutation|lint|quality|suppress'],
                [id: 'ARCH-NAMING', pattern: 'name|naming|must end|must be named']
        ]
        String description = withoutLeadingPath(violation)
        Map<String, String> matched = rules.find { entry ->
            (description =~ /(?i)${entry.pattern}/).find()
        }
        return matched?.id ?: 'ARCH-GENERAL'
    }

    private static String withoutLeadingPath(String violation) {
        violation.replaceFirst(/^\S*\.(?:java|groovy|kt|sql|xml|gradle)\b\S*\s*/, '')
    }
}
