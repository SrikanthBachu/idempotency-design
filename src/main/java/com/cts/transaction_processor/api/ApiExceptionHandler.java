package com.cts.transaction_processor.api;

import java.time.Instant;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;

import com.cts.transaction_processor.api.dto.ApiErrorDto;

import jakarta.validation.ConstraintViolationException;

@RestControllerAdvice
public class ApiExceptionHandler {

	@ExceptionHandler(ResourceNotFoundException.class)
	public ResponseEntity<ApiErrorDto> handleNotFound(ResourceNotFoundException exception, WebRequest request) {
		return error(HttpStatus.NOT_FOUND, exception.getMessage(), List.of(), request);
	}

	@ExceptionHandler(ConflictException.class)
	public ResponseEntity<ApiErrorDto> handleConflict(ConflictException exception, WebRequest request) {
		return error(HttpStatus.CONFLICT, exception.getMessage(), List.of(), request);
	}

	@ExceptionHandler({MethodArgumentNotValidException.class, ConstraintViolationException.class})
	public ResponseEntity<ApiErrorDto> handleValidation(Exception exception, WebRequest request) {
		List<String> details;
		if (exception instanceof MethodArgumentNotValidException invalid) {
			details = invalid.getBindingResult().getFieldErrors().stream()
					.map(error -> error.getField() + " " + error.getDefaultMessage())
					.distinct()
					.toList();
		} else if (exception instanceof ConstraintViolationException invalid) {
			details = invalid.getConstraintViolations().stream()
					.map(violation -> violation.getPropertyPath() + " " + violation.getMessage())
					.distinct()
					.toList();
		} else {
			details = List.of();
		}
		return error(HttpStatus.BAD_REQUEST, "Request validation failed", details, request);
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	public ResponseEntity<ApiErrorDto> handleUnreadableBody(
			HttpMessageNotReadableException exception, WebRequest request) {
		return error(HttpStatus.BAD_REQUEST, "Request body is malformed or contains an unsupported value",
				List.of(), request);
	}

	@ExceptionHandler(IllegalArgumentException.class)
	public ResponseEntity<ApiErrorDto> handleIllegalArgument(
			IllegalArgumentException exception, WebRequest request) {
		return error(HttpStatus.BAD_REQUEST, exception.getMessage(), List.of(), request);
	}

	@ExceptionHandler(org.springframework.core.task.TaskRejectedException.class)
	public ResponseEntity<ApiErrorDto> handleOverloadedExecutor(
			org.springframework.core.task.TaskRejectedException exception, WebRequest request) {
		return error(HttpStatus.SERVICE_UNAVAILABLE, "Batch queue is full; retry the request later",
				List.of(), request);
	}

	private static ResponseEntity<ApiErrorDto> error(
			HttpStatus status, String message, List<String> details, WebRequest request) {
		String path = request instanceof ServletWebRequest servletRequest
				? servletRequest.getRequest().getRequestURI()
				: "";
		return ResponseEntity.status(status).body(new ApiErrorDto(
				Instant.now(), status.value(), status.getReasonPhrase(), message, path, details));
	}
}
