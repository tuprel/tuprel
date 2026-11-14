package example.products;

import dev.tuprel.runtime.TuprelDatabaseException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
class ApiErrors extends ResponseEntityExceptionHandler {
    @Override
    protected ResponseEntity<Object> createResponseEntity(
            Object body, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        if (status.value() == 400) {
            body = ProblemDetail.forStatusAndDetail(status,
                    "Check the JSON body, product identifier and pagination parameters.");
        }
        return super.createResponseEntity(body, headers, status, request);
    }

    @ExceptionHandler(TuprelDatabaseException.class)
    ProblemDetail databaseFailure(TuprelDatabaseException failure) {
        String state = failure.sqlState();
        if (state != null && (state.startsWith("08") || state.equals("57P01"))) {
            return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                    "The product database is temporarily unavailable.");
        }
        return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
                "The product database operation failed.");
    }
}
