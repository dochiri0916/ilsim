package io.github.dochiri0916.buildconvention.catalog

final class TechnicalDependencyCatalog {
    static final Set<String> ALLOWED_APPLICATION_SPRING_TYPES = [
            'org.springframework.stereotype.Service',
            'org.springframework.transaction.annotation.Transactional'
    ] as Set
    static final Set<String> APPLICATION_TECHNICAL_PACKAGE_PREFIXES = [
            'com.amazonaws.',
            'com.fasterxml.jackson.',
            'com.mongodb.',
            'com.mysql.',
            'com.querydsl.',
            'com.zaxxer.',
            'feign.',
            'io.lettuce.',
            'io.micrometer.',
            'jakarta.persistence.',
            'jakarta.servlet.',
            'java.io.File',
            'java.io.RandomAccessFile',
            'java.net.HttpURLConnection',
            'java.net.Socket',
            'java.net.URLConnection',
            'java.net.http.',
            'java.nio.channels.',
            'java.nio.file.',
            'java.sql.',
            'javax.persistence.',
            'javax.servlet.',
            'javax.sql.',
            'okhttp3.',
            'org.apache.http.',
            'org.apache.ibatis.',
            'org.apache.kafka.',
            'org.hibernate.',
            'org.jooq.',
            'org.mybatis.',
            'org.postgresql.',
            'org.springframework.',
            'reactor.',
            'redis.clients.',
            'retrofit2.',
            'software.amazon.awssdk.',
            'tools.jackson.'
    ] as Set
    static final List<String> DOMAIN_FORBIDDEN_ARCHUNIT_PACKAGES = [
            'com.fasterxml.jackson..',
            'com.querydsl..',
            'jakarta.persistence..',
            'jakarta.servlet..',
            'jakarta.validation..',
            'javax.persistence..',
            'org.hibernate..',
            'org.springframework..',
            'tools.jackson..'
    ].asImmutable()
    static final List<String> DOMAIN_ALLOWED_EXTERNAL_PACKAGE_PREFIXES = [
            'com.github.f4b6a3.',
            'java.',
            'kotlin.',
            'org.jetbrains.annotations.'
    ].asImmutable()

    private TechnicalDependencyCatalog() {
    }

    static boolean isForbiddenInApplication(String typeName) {
        if (ALLOWED_APPLICATION_SPRING_TYPES.contains(typeName)) {
            return false
        }
        return APPLICATION_TECHNICAL_PACKAGE_PREFIXES.any { String prefix -> typeName.startsWith(prefix) }
    }

    static boolean isAllowedExternalInDomain(String typeName) {
        return DOMAIN_ALLOWED_EXTERNAL_PACKAGE_PREFIXES.any { String prefix -> typeName.startsWith(prefix) }
    }
}
