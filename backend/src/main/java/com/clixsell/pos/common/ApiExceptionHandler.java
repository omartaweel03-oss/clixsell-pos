package com.clixsell.pos.common;

import com.clixsell.pos.auth.LoginFailedException;
import com.clixsell.pos.product.DuplicateBarcodeException;
import com.clixsell.pos.product.ProductNotFoundException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.Map;

/**
 * Turns business and request errors into clear JSON error responses.
 * (401 "not logged in" and 403 "wrong role" are written by SecurityConfig.)
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    /** Product, store and user validation errors (ProductValidationException is one of them). */
    @ExceptionHandler(ValidationException.class)
    public ResponseEntity<ApiError> validation(ValidationException e) {
        return error(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                "Some fields are not valid", e.getFieldErrors());
    }

    @ExceptionHandler(LoginFailedException.class)
    public ResponseEntity<ApiError> loginFailed(LoginFailedException e) {
        HttpStatus status = e.getReason() == LoginFailedException.Reason.ACCOUNT_LOCKED
                ? HttpStatus.TOO_MANY_REQUESTS
                : HttpStatus.UNAUTHORIZED;
        return error(status, e.getReason().name(), e.getMessage(), Map.of());
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ApiError> notFound(NotFoundException e) {
        return error(HttpStatus.NOT_FOUND, e.getCode(), e.getMessage(), Map.of());
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ApiError> conflict(ConflictException e) {
        return error(HttpStatus.CONFLICT, e.getCode(), e.getMessage(), e.fieldErrors());
    }

    @ExceptionHandler(DuplicateBarcodeException.class)
    public ResponseEntity<ApiError> duplicateBarcode(DuplicateBarcodeException e) {
        return error(HttpStatus.CONFLICT, "DUPLICATE_BARCODE", e.getMessage(),
                Map.of("barcode", e.getMessage()));
    }

    @ExceptionHandler(ProductNotFoundException.class)
    public ResponseEntity<ApiError> productNotFound(ProductNotFoundException e) {
        return error(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND", e.getMessage(), Map.of());
    }

    /** A database constraint failed (for example two saves with the same barcode at once). */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> constraint(DataIntegrityViolationException e) {
        return error(HttpStatus.CONFLICT, "CONSTRAINT_VIOLATION",
                "The data conflicts with existing records", Map.of());
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ApiError> badRequest(Exception e) {
        return error(HttpStatus.BAD_REQUEST, "BAD_REQUEST",
                "The request is not in the expected format", Map.of());
    }

    private static ResponseEntity<ApiError> error(HttpStatus status, String code, String message,
                                                  Map<String, String> fieldErrors) {
        return ResponseEntity.status(status)
                .body(new ApiError(status.value(), code, message, fieldErrors));
    }
}
