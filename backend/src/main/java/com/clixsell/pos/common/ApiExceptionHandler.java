package com.clixsell.pos.common;

import com.clixsell.pos.product.DuplicateBarcodeException;
import com.clixsell.pos.product.ProductNotFoundException;
import com.clixsell.pos.product.ProductValidationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.Map;

/** Turns business and request errors into clear JSON error responses. */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(ProductValidationException.class)
    public ResponseEntity<ApiError> validation(ProductValidationException e) {
        return error(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                "Some product fields are not valid", e.getFieldErrors());
    }

    @ExceptionHandler(DuplicateBarcodeException.class)
    public ResponseEntity<ApiError> duplicateBarcode(DuplicateBarcodeException e) {
        return error(HttpStatus.CONFLICT, "DUPLICATE_BARCODE", e.getMessage(),
                Map.of("barcode", e.getMessage()));
    }

    @ExceptionHandler(ProductNotFoundException.class)
    public ResponseEntity<ApiError> notFound(ProductNotFoundException e) {
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
