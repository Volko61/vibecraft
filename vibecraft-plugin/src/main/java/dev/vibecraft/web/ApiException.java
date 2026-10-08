package dev.vibecraft.web;

/** An error that maps to an HTTP status and a {@code {"error": "..."}} body. */
final class ApiException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int status;

    ApiException(int status, String message) {
        super(message, null, false, false);
        this.status = status;
    }

    int status() {
        return status;
    }
}
