package com.example.demo.global.persistence;

import com.example.demo.global.application.exception.RepositoryOperationException;
import jakarta.persistence.PersistenceException;
import org.springframework.dao.DataAccessException;
import org.hibernate.exception.ConstraintViolationException;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.function.Supplier;

public final class RepositoryExceptionTranslator {

    private RepositoryExceptionTranslator() {
    }

    public static <T> T execute(final Supplier<T> operation) {
        try {
            return operation.get();
        } catch (final DataAccessException | PersistenceException exception) {
            throw new RepositoryOperationException(exception);
        }
    }

    public static boolean isConstraintViolation(final Throwable exception, final String constraintName) {
        final Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable cause = exception;
        boolean matches = false;
        while (cause != null && !matches && visited.add(cause)) {
            matches = matchesConstraint(cause, constraintName);
            cause = cause.getCause();
        }
        return matches;
    }

    private static boolean matchesConstraint(final Throwable exception, final String constraintName) {
        if (!(exception instanceof final ConstraintViolationException violation)) {
            return false;
        }
        final String actualName = violation.getConstraintName();
        return constraintName.equals(actualName);
    }

}
