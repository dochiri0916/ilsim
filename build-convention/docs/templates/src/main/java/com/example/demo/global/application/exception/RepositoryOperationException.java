package com.example.demo.global.application.exception;

public final class RepositoryOperationException extends RuntimeException {

    public RepositoryOperationException(final Throwable cause) {
        super("저장소 작업을 처리하지 못했습니다.", cause);
    }

}
