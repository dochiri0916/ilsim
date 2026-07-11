package com.dochiri.convention.validator

import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class MigrationConventionValidatorTest {

    @TempDir
    File tempDir

    @Test
    @DisplayName('여러 migration에 나뉜 인덱스와 외래키를 누적 스키마로 검증한다')
    void acceptsReferenceIntegrityAddedByLaterMigration() {
        // given
        Project project = sampleProject('incremental')
        writeSql(project, 'db/migration/V1__create_tables.sql', '''
                create table members (
                    id bigint primary key,
                    member_id varchar(32) not null unique
                );

                create table orders (
                    id bigint primary key,
                    order_id varchar(32) not null unique,
                    member_id varchar(32) not null
                );
                ''')
        writeSql(project, 'db/migration/V2__add_order_member_reference.sql', '''
                create index idx_orders_member_id on orders(member_id);
                alter table orders
                    add constraint fk_orders_member
                    foreign key (member_id) references members(member_id);
                ''')

        // when
        List<String> violations = MigrationConventionValidator.validate(project)

        // then
        assert !violations.any { it.contains("reference column 'member_id' must have an index") }
        assert !violations.any { it.contains("reference column 'member_id' must have an explicit foreign key") }
    }

    @Test
    @DisplayName('외부 서비스 식별자는 명시 metadata가 있으면 외래키를 요구하지 않는다')
    void acceptsExternalReferenceWithoutForeignKey() {
        // given
        Project project = sampleProject('external-reference')
        writeSql(project, 'db/migration/V1__create_deliveries.sql', '''
                -- build-convention: external-reference deliveries.remote_member_id
                create table deliveries (
                    id bigint primary key,
                    delivery_id varchar(32) not null unique,
                    remote_member_id varchar(32) not null
                );
                create index idx_deliveries_remote_member_id on deliveries(remote_member_id);
                ''')

        // when
        List<String> violations = MigrationConventionValidator.validate(project)

        // then
        assert !violations.any { it.contains("reference column 'remote_member_id' must have an explicit foreign key") }
        assert !violations.any { it.contains("reference column 'remote_member_id' must have an index") }
    }

    private Project sampleProject(String name) {
        File projectDir = new File(tempDir, name)
        projectDir.mkdirs()
        return ProjectBuilder.builder().withProjectDir(projectDir).build()
    }

    private static void writeSql(Project project, String path, String source) {
        File file = new File(project.projectDir, "src/main/resources/${path}")
        file.parentFile.mkdirs()
        file.text = source.stripIndent().trim() + System.lineSeparator()
    }
}
