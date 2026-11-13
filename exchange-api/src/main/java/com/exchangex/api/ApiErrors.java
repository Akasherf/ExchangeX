package com.exchangex.api;

import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;

@RestControllerAdvice
public class ApiErrors {
    @ExceptionHandler({IllegalArgumentException.class, MethodArgumentNotValidException.class, ConstraintViolationException.class, HttpMessageNotReadableException.class})
    ResponseEntity<Map<String,String>> badRequest(Exception e) {
        return ResponseEntity.badRequest().body(Map.of("error", "Invalid request", "detail", e.getMessage() == null ? "Validation failed" : e.getMessage()));
    }

    @ExceptionHandler(CompletionException.class)
    ResponseEntity<Map<String,String>> completionException(CompletionException e) {
        Throwable cause = e.getCause();
        if (cause instanceof IllegalArgumentException) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid request", "detail", cause.getMessage()));
        }
        if (cause instanceof RejectedExecutionException) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header("Retry-After", "1").body(Map.of("error", "Command queue busy or shutting down"));
        }
        if (cause instanceof ResponseStatusException rse) {
            return ResponseEntity.status(rse.getStatusCode()).body(Map.of("error", rse.getReason()));
        }
        if (cause instanceof HttpMessageNotReadableException) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid request", "detail", cause.getMessage()));
        }
        return ResponseEntity.internalServerError().body(Map.of("error", "Internal server error"));
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<Map<String,String>> responseStatusException(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode()).body(Map.of("error", e.getReason()));
    }

    @ExceptionHandler(RejectedExecutionException.class)
    ResponseEntity<Map<String,String>> busy(RejectedExecutionException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header("Retry-After", "1").body(Map.of("error", "Command queue busy or shutting down"));
    }
}
