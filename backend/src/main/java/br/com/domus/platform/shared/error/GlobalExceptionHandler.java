package br.com.domus.platform.shared.error;

import java.net.URI;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.core.PropertyReferenceException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Tratamento centralizado de erros no formato RFC 9457 (Problem Details). */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(NotFoundException.class)
    ProblemDetail notFound(NotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, "not-found", ex.getMessage());
    }

    @ExceptionHandler(ForbiddenException.class)
    ProblemDetail forbidden(ForbiddenException ex) {
        return problem(HttpStatus.FORBIDDEN, "forbidden", ex.getMessage());
    }

    @ExceptionHandler(ConflictException.class)
    ProblemDetail conflict(ConflictException ex) {
        return problem(HttpStatus.CONFLICT, "conflict", ex.getMessage());
    }

    @ExceptionHandler(TooManyRequestsException.class)
    ProblemDetail tooMany(TooManyRequestsException ex) {
        return problem(HttpStatus.TOO_MANY_REQUESTS, "too-many-requests", ex.getMessage());
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail integrity(DataIntegrityViolationException ex) {
        return problem(HttpStatus.CONFLICT, "conflict", "The request conflicts with existing data");
    }

    @ExceptionHandler(InvalidRequestException.class)
    ProblemDetail invalidRequest(InvalidRequestException ex) {
        return problem(HttpStatus.BAD_REQUEST, "bad-request", ex.getMessage());
    }

    @ExceptionHandler({PropertyReferenceException.class, IllegalArgumentException.class})
    ProblemDetail badRequest(RuntimeException ex) {
        return problem(HttpStatus.BAD_REQUEST, "bad-request", "Invalid request parameter");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail invalid(MethodArgumentNotValidException ex) {
        ProblemDetail pd = problem(HttpStatus.BAD_REQUEST, "validation", "Invalid request payload");
        List<String> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .toList();
        pd.setProperty("errors", errors);
        return pd;
    }

    private static ProblemDetail problem(HttpStatus status, String type, String detail) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setType(URI.create("https://portaria.example/problems/" + type));
        return pd;
    }
}
