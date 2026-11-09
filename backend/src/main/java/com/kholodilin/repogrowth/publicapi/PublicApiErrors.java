package com.kholodilin.repogrowth.publicapi;
import com.kholodilin.repogrowth.common.api.ApiException;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
@RestControllerAdvice(assignableTypes=PublicApiController.class) @Order(Ordered.HIGHEST_PRECEDENCE)
public class PublicApiErrors {
 public record Error(String code,String message,String requestId){}
 private Error error(String code,String message){return new Error(code,message,java.util.UUID.randomUUID().toString());}
 @ExceptionHandler(ApiException.class) public ResponseEntity<Error> policy(ApiException ex){return ResponseEntity.status(ex.status()).body(error(ex.code().name(),ex.getMessage()));}
 @ExceptionHandler(org.springframework.dao.DataAccessException.class) public ResponseEntity<Error> database(Exception ex){return ResponseEntity.status(503).body(error("UNAVAILABLE","Data temporarily unavailable"));}
 @ExceptionHandler({org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,org.springframework.web.bind.MissingServletRequestParameterException.class}) public ResponseEntity<Error> parameters(Exception ex){return ResponseEntity.badRequest().body(error("VALIDATION_ERROR","Invalid request parameters"));}
 @ExceptionHandler(Exception.class) public ResponseEntity<Error> unknown(Exception ex){return ResponseEntity.status(500).body(error("INTERNAL_ERROR","Internal server error"));}
}
