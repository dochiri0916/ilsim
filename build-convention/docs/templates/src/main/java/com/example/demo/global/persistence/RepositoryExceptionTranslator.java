package com.example.demo.global.persistence;

import com.example.demo.global.application.exception.RepositoryOperationException;
import jakarta.persistence.PersistenceException;
import org.springframework.dao.DataAccessException;

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

}
