package com.dochiri.convention.validator

import org.gradle.api.Project

import java.nio.charset.StandardCharsets
import java.util.regex.Pattern

class MigrationConventionValidator {
    static List<String> validate(Project project) {
        List<String> violations = []
        List<SqlSource> sqlSources = collectSqlFiles(project)
                .sort { File left, File right -> project.relativePath(left) <=> project.relativePath(right) }
                .collect { File file ->
                    String rawSql = file.getText(StandardCharsets.UTF_8.name())
                    new SqlSource(file, rawSql, normalizeSql(rawSql))
                }
        String cumulativeSql = sqlSources.collect { SqlSource source -> source.normalizedSql }.join(' ')
        Set<String> externalReferences = sqlSources.collectMany { SqlSource source ->
            extractExternalReferences(source.rawSql)
        }.toSet()

        sqlSources.each { SqlSource source ->
            validateCreateTableStatements(project, source.file, source.normalizedSql, violations)
        }
        validateReferenceIntegrity(project, sqlSources, cumulativeSql, externalReferences, violations)
        return violations
    }

    private static Set<File> collectSqlFiles(Project project) {
        File resourcesDir = project.file('src/main/resources')
        if (!resourcesDir.exists()) {
            return []
        }
        return project.fileTree(resourcesDir) {
            include '**/*.sql'
        }.files
    }

    private static void validateCreateTableStatements(
            Project project,
            File file,
            String sql,
            List<String> violations
    ) {
        def tableMatcher = sql =~ /(?is)\bcreate\s+table\s+(?:if\s+not\s+exists\s+)?([a-zA-Z0-9_".]+)\s*\((.*?)\);/
        while (tableMatcher.find()) {
            String tableName = tableMatcher.group(1).replace('"', '')
            String body = tableMatcher.group(2)
            String path = project.relativePath(file)

            extractIdentifierColumns(tableName, body).each { IdentifierColumn column ->
                if (column.length != 32) {
                    violations.add("${path} table '${tableName}' identifier column '${column.name}' must be char/varchar(32)")
                }
                if (column.primaryDomainIdentifier && !column.unique) {
                    violations.add("${path} table '${tableName}' domain identifier column '${column.name}' must have a unique constraint")
                }
            }

            Set<String> technicalReferenceColumns = extractTechnicalReferenceColumns(body)
            technicalReferenceColumns.each { String column ->
                violations.add("${path} table '${tableName}' reference column '${column}' must store identifier value as char/varchar(32), not DB technical id")
            }
        }
    }

    private static void validateReferenceIntegrity(
            Project project,
            List<SqlSource> sqlSources,
            String cumulativeSql,
            Set<String> externalReferences,
            List<String> violations
    ) {
        sqlSources.each { SqlSource source ->
            String path = project.relativePath(source.file)
            List<IdentifierColumn> identifierColumns = extractIdentifierColumns(source.normalizedSql)
            identifierColumns.findAll { IdentifierColumn column -> !column.unique }.each { IdentifierColumn column ->
                if (!hasIndexForColumn(cumulativeSql, column)) {
                    violations.add("${path} reference column '${column.name}' must have an index")
                }
                String qualifiedColumn = "${column.table}.${column.name}"
                if (!externalReferences.contains(qualifiedColumn)
                        && !hasForeignKeyForColumn(cumulativeSql, column)) {
                    violations.add("${path} reference column '${column.name}' must have an explicit foreign key")
                }
            }

            def technicalForeignKeyMatcher = source.normalizedSql =~ /(?is)\bforeign\s+key\s*\([^)]*\)\s*references\s+[a-zA-Z0-9_".]+\s*\(\s*id\s*\)/
            if (technicalForeignKeyMatcher.find()) {
                violations.add("${path} foreign keys must reference a domain identifier column, not DB technical id")
            }
        }
    }

    private static Set<String> extractExternalReferences(String rawSql) {
        Set<String> externalReferences = []
        def matcher = rawSql =~ /(?im)^\s*--\s*build-convention:\s*external-reference\s+([a-zA-Z0-9_".]+\.[a-zA-Z0-9_"]+)\s*$/
        while (matcher.find()) {
            externalReferences.add(matcher.group(1).replace('"', '').toLowerCase(Locale.ROOT))
        }
        return externalReferences
    }

    private static String normalizeSql(String sql) {
        return sql
                .replaceAll(/(?s)\/\*.*?\*\//, ' ')
                .replaceAll(/(?m)--.*$/, ' ')
                .replaceAll(/\s+/, ' ')
                .trim()
                .toLowerCase(Locale.ROOT)
    }

    private static boolean hasUniqueIdentifier(String tableBody, String column) {
        String quotedColumn = Pattern.quote(column)
        return (tableBody =~ /\b${quotedColumn}\b[^,;]*\bunique\b/).find()
                || (tableBody =~ /\bunique\s*\([^)]*\b${quotedColumn}\b[^)]*\)/).find()
                || (tableBody =~ /\bconstraint\b[^,;]*\bunique\s*\([^)]*\b${quotedColumn}\b[^)]*\)/).find()
    }

    private static List<IdentifierColumn> extractIdentifierColumns(String sql) {
        List<IdentifierColumn> columns = []
        def tableMatcher = sql =~ /(?is)\bcreate\s+table\s+(?:if\s+not\s+exists\s+)?([a-zA-Z0-9_".]+)\s*\((.*?)\);/
        while (tableMatcher.find()) {
            String tableName = tableMatcher.group(1).replace('"', '')
            String body = tableMatcher.group(2)
            columns.addAll(extractIdentifierColumns(tableName, body))
        }
        return columns
    }

    private static List<IdentifierColumn> extractIdentifierColumns(String tableName, String tableBody) {
        List<IdentifierColumn> columns = []
        String primaryIdentifierName = singularize(tableName) + '_id'
        def columnMatcher = tableBody =~ /(?:^|,)\s*([a-z][a-z0-9_]*_id)\s+(char|varchar)\s*\(\s*(\d+)\s*\)([^,;]*)/
        while (columnMatcher.find()) {
            String column = columnMatcher.group(1)
            columns.add(new IdentifierColumn(
                    tableName,
                    column,
                    Integer.parseInt(columnMatcher.group(3)),
                    hasUniqueIdentifier(tableBody, column),
                    column == primaryIdentifierName
            ))
        }
        return columns
    }

    private static Set<String> extractTechnicalReferenceColumns(String tableBody) {
        Set<String> columns = []
        def columnMatcher = tableBody =~ /\b([a-z][a-z0-9_]*_id)\b\s+(?:bigint|bigserial|integer|int8|int)\b/
        while (columnMatcher.find()) {
            String column = columnMatcher.group(1)
            if (column != 'id') {
                columns.add(column)
            }
        }
        return columns
    }

    private static boolean hasIndexForColumn(String sql, IdentifierColumn column) {
        Pattern pattern = Pattern.compile(
                /(?is)\bcreate\s+(?:unique\s+)?index\b[^;]*\bon\s+${Pattern.quote(column.table)}\b[^;]*\(\s*[^)]*\b${Pattern.quote(column.name)}\b[^)]*\)/
        )
        return pattern.matcher(sql).find()
    }

    private static boolean hasForeignKeyForColumn(String sql, IdentifierColumn column) {
        Pattern pattern = Pattern.compile(
                /(?is)\bforeign\s+key\s*\(\s*[^)]*\b${Pattern.quote(column.name)}\b[^)]*\)\s*references\s+[a-zA-Z0-9_".]+\s*\(\s*[a-z][a-z0-9_]*_id\s*\)/
        )
        return pattern.matcher(sql).find()
    }

    private static String singularize(String tableName) {
        if (tableName.endsWith('ies')) {
            return tableName.substring(0, tableName.length() - 3) + 'y'
        }
        if (tableName.endsWith('s') && tableName.length() > 1) {
            return tableName.substring(0, tableName.length() - 1)
        }
        return tableName
    }

    private static class IdentifierColumn {
        final String table
        final String name
        final int length
        final boolean unique
        final boolean primaryDomainIdentifier

        private IdentifierColumn(
                String table,
                String name,
                int length,
                boolean unique,
                boolean primaryDomainIdentifier
        ) {
            this.table = table
            this.name = name
            this.length = length
            this.unique = unique
            this.primaryDomainIdentifier = primaryDomainIdentifier
        }
    }

    private static class SqlSource {
        final File file
        final String rawSql
        final String normalizedSql

        private SqlSource(File file, String rawSql, String normalizedSql) {
            this.file = file
            this.rawSql = rawSql
            this.normalizedSql = normalizedSql
        }
    }
}
