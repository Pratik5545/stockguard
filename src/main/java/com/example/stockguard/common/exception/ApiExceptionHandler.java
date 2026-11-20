package com.example.stockguard.common.exception;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

// Standard RFC 9457 problem responses for MVC request errors.
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {
    @org.springframework.web.bind.annotation.ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
    public org.springframework.http.ProblemDetail conflict(org.springframework.dao.DataIntegrityViolationException exception) {
        Throwable cause = exception;
        while (cause != null) {
            if (cause instanceof org.hibernate.exception.ConstraintViolationException constraint
                    && "uq_users_email".equals(constraint.getConstraintName())) {
                return org.springframework.http.ProblemDetail.forStatusAndDetail(
                        org.springframework.http.HttpStatus.CONFLICT, "An account with this email already exists.");
            }
            if (cause instanceof org.hibernate.exception.ConstraintViolationException constraint
                    && "uq_products_sku".equals(constraint.getConstraintName())) {
                return org.springframework.http.ProblemDetail.forStatusAndDetail(
                        org.springframework.http.HttpStatus.CONFLICT, "A product with this SKU already exists.");
            }
            cause = cause.getCause();
        }
        return org.springframework.http.ProblemDetail.forStatusAndDetail(
                org.springframework.http.HttpStatus.CONFLICT, "The operation conflicts with stored data.");
    }
}
