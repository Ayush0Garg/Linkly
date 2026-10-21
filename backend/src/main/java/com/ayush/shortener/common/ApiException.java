package com.ayush.shortener.common;

import org.springframework.http.HttpStatus;

/** A failure the caller can act on; rendered as an RFC 9457 problem response. */
public class ApiException extends RuntimeException {

    private final HttpStatus status;

    private ApiException(HttpStatus status, String detail) {
        super(detail);
        this.status = status;
    }

    public static ApiException badRequest(String detail) {
        return new ApiException(HttpStatus.BAD_REQUEST, detail);
    }

    public static ApiException unauthorized(String detail) {
        return new ApiException(HttpStatus.UNAUTHORIZED, detail);
    }

    public static ApiException unavailable(String detail) {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, detail);
    }

    public static ApiException notFound(String detail) {
        return new ApiException(HttpStatus.NOT_FOUND, detail);
    }

    public static ApiException conflict(String detail) {
        return new ApiException(HttpStatus.CONFLICT, detail);
    }

    public static ApiException gone(String detail) {
        return new ApiException(HttpStatus.GONE, detail);
    }

    public HttpStatus status() {
        return status;
    }
}
