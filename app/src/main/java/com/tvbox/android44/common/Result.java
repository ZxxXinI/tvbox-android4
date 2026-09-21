package com.tvbox.android44.common;

/**
 * 统一结果类型：
 * Success(data, source, fromCache) / Failure(kind, userMessage, cause) / Cancelled。
 */
public abstract class Result<T> {

    private Result() {
    }

    public boolean isSuccess() {
        return this instanceof Success;
    }

    public boolean isFailure() {
        return this instanceof Failure;
    }

    public boolean isCancelled() {
        return this instanceof Cancelled;
    }

    @SuppressWarnings("unchecked")
    public T data() {
        if (this instanceof Success) {
            return ((Success<T>) this).data;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    public Failure asFailure() {
        return (Failure) this;
    }

    public static final class Success<T> extends Result<T> {
        public final T data;
        public final String source;
        public final boolean fromCache;

        public Success(T data) {
            this(data, null, false);
        }

        public Success(T data, String source, boolean fromCache) {
            this.data = data;
            this.source = source;
            this.fromCache = fromCache;
        }
    }

    public static final class Failure<T> extends Result<T> {
        public final ErrorKind kind;
        public final String userMessage;
        public final Throwable cause;

        public Failure(ErrorKind kind, String userMessage, Throwable cause) {
            this.kind = kind;
            this.userMessage = userMessage != null ? userMessage : kind.userMessage();
            this.cause = cause;
        }
    }

    public static final class Cancelled<T> extends Result<T> {
        public static final Cancelled<Object> INSTANCE = new Cancelled<Object>();
    }
}
