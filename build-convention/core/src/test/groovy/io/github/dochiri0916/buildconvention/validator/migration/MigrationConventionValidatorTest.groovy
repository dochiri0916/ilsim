package io.github.dochiri0916.buildconvention.validator.migration

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
                    public_id varchar(36) not null unique
                );

                create table orders (
                    id bigint primary key,
                    public_id varchar(36) not null unique,
                    member_id varchar(36) not null
                );
                ''')
        writeSql(project, 'db/migration/V2__add_order_member_reference.sql', '''
                create index idx_orders_member_id on orders(member_id);
                alter table orders
                    add constraint fk_orders_member
                    foreign key (member_id) references members(public_id);
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
                    public_id varchar(36) not null unique,
                    remote_member_id varchar(36) not null
                );
                create index idx_deliveries_remote_member_id on deliveries(remote_member_id);
                ''')

        // when
        List<String> violations = MigrationConventionValidator.validate(project)

        // then
        assert !violations.any { it.contains("reference column 'remote_member_id' must have an explicit foreign key") }
        assert !violations.any { it.contains("reference column 'remote_member_id' must have an index") }
    }

    @Test
    @DisplayName('public_id는 36자·필수·단일 유니크이고 복합 제약으로 우회하지 못한다')
    void rejects_public_identifier_schema_bypasses() {
        // given
        Project project = sampleProject('public-identifier')
        String sql = '''
            create table orders (id bigint primary key,
                public_id varchar(36) not null,
                source varchar(50), constraint uk_orders_public_id unique(public_id));
        '''
        writeSql(project, 'db/migration/V1__orders.sql', sql)
        // when
        // then
        assert MigrationConventionValidator.validate(project).empty
        [['varchar(36)', 'varchar(32)', 'char/varchar(36)'],
         ['not null', '', 'must be not null'],
         ['unique(public_id)', 'unique(public_id, source)', 'must have a unique constraint']].each { variant ->
            writeSql(project, 'db/migration/V1__orders.sql', sql.replace(variant[0], variant[1]))
            assert MigrationConventionValidator.validate(project).any { it.contains(variant[2]) }
        }
    }

    @Test
    @DisplayName('인덱스와 외래키가 없는 참조 식별자 컬럼을 거부한다')
    void rejects_reference_column_without_index_and_foreign_key() {
        // given
        Project project = sampleProject('missing-reference-integrity')
        writeSql(project, 'db/migration/V1__create_orders.sql', '''
                create table orders (
                    id bigint primary key,
                    public_id varchar(36) not null unique,
                    member_id varchar(36) not null
                );
                ''')

        // when
        List<String> violations = MigrationConventionValidator.validate(project)

        // then
        assert violations.any { it.contains("reference column 'member_id' must have an index") }
        assert violations.any { it.contains("reference column 'member_id' must have an explicit foreign key") }
    }

    @Test
    @DisplayName('DB 기술 ID를 저장하거나 참조하는 컬럼과 외래키를 거부한다')
    void rejects_technical_identifier_references() {
        // given
        Project project = sampleProject('technical-reference')
        writeSql(project, 'db/migration/V1__create_orders.sql', '''
                create table orders (
                    id bigint primary key,
                    public_id varchar(36) not null unique,
                    member_id bigint not null,
                    foreign key (member_id) references members(id)
                );
                ''')

        // when
        List<String> violations = MigrationConventionValidator.validate(project)

        // then
        assert violations.any {
            it.contains("reference column 'member_id' must store identifier value as char/varchar(36), not DB technical id")
        }
        assert violations.any { it.contains('foreign keys must reference a domain identifier column, not DB technical id') }
    }

    @Test
    @DisplayName('migration 자원이 없는 프로젝트는 위반이 없다')
    void accepts_project_without_resources() {
        // given
        Project project = sampleProject('no-resources')

        // when
        List<String> violations = MigrationConventionValidator.validate(project)

        // then
        assert violations.empty
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
