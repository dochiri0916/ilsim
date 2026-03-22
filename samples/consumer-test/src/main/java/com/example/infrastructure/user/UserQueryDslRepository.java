package com.example.infrastructure.user;

import com.example.application.user.port.out.dto.UserReadModel;
import com.example.infrastructure.department.QDepartmentEntity;
import com.querydsl.core.types.Projections;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class UserQueryDslRepository {

    private final JPAQueryFactory queryFactory;

    public Optional<UserReadModel> findByPublicId(final String publicId) {
        final QUserEntity user = QUserEntity.userEntity;
        final QDepartmentEntity department = QDepartmentEntity.departmentEntity;
        return Optional.ofNullable(
                queryFactory.select(
                                Projections.constructor(
                                        UserReadModel.class,
                                        user.publicId,
                                        user.name,
                                        user.departmentId,
                                        department.name
                                )
                        )
                        .from(user)
                        .join(department)
                        .on(user.departmentId.eq(department.publicId))
                        .where(user.publicId.eq(publicId))
                        .fetchOne()
        );
    }

}