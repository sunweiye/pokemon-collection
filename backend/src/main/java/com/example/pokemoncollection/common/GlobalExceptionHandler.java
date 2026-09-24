package com.example.pokemoncollection.common;

import com.example.pokemoncollection.common.exception.CollectionEntryNotFoundException;
import com.example.pokemoncollection.common.exception.DuplicateCollectionEntryException;
import com.example.pokemoncollection.common.exception.PokeApiUnavailableException;
import com.example.pokemoncollection.common.exception.PokemonNotFoundException;
import com.example.pokemoncollection.common.exception.UnauthorizedException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingPathVariableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(PokemonNotFoundException.class)
    public ResponseEntity<ErrorResponse> handlePokemonNotFound(PokemonNotFoundException exception,
                                                                HttpServletRequest request) {
        log.info("event=REQUEST_FAILED status=404 error=NOT_FOUND path={}", request.getRequestURI());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse("NOT_FOUND", exception.getMessage()));
    }

    @ExceptionHandler(CollectionEntryNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleCollectionNotFound(CollectionEntryNotFoundException exception,
                                                                  HttpServletRequest request) {
        log.info("event=REQUEST_FAILED status=404 error=NOT_FOUND path={}", request.getRequestURI());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse("NOT_FOUND", exception.getMessage()));
    }

    @ExceptionHandler(PokeApiUnavailableException.class)
    public ResponseEntity<ErrorResponse> handlePokeApiUnavailable(PokeApiUnavailableException exception,
                                                                  HttpServletRequest request) {
        log.info("event=REQUEST_FAILED status=502 error=UPSTREAM_UNAVAILABLE path={}", request.getRequestURI());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(new ErrorResponse("UPSTREAM_UNAVAILABLE",
                        "Pokémon data service is temporarily unavailable. Please try again later."));
    }

    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<ErrorResponse> handleUnauthorized(UnauthorizedException exception,
                                                            HttpServletRequest request) {
        log.info("event=REQUEST_FAILED status=401 error=UNAUTHORIZED path={}", request.getRequestURI());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new ErrorResponse("UNAUTHORIZED", exception.getMessage()));
    }

    @ExceptionHandler(DuplicateCollectionEntryException.class)
    public ResponseEntity<ErrorResponse> handleDuplicate(DuplicateCollectionEntryException exception,
                                                         HttpServletRequest request) {
        log.info("event=REQUEST_FAILED status=409 error=DUPLICATE_COLLECTION_ENTRY path={}",
                request.getRequestURI());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("DUPLICATE_COLLECTION_ENTRY", exception.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleBadRequest(IllegalArgumentException exception,
                                                          HttpServletRequest request) {
        return badRequest(exception.getMessage(), request);
    }

    @ExceptionHandler({
            MissingServletRequestParameterException.class,
            MissingPathVariableException.class,
            MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class,
            MethodArgumentNotValidException.class,
            HandlerMethodValidationException.class
    })
    public ResponseEntity<ErrorResponse> handleMalformedRequest(Exception exception,
                                                                HttpServletRequest request) {
        return badRequest("The request is invalid.", request);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotAllowed(HttpRequestMethodNotSupportedException exception,
                                                                HttpServletRequest request) {
        log.info("event=REQUEST_FAILED status=405 error=METHOD_NOT_ALLOWED path={}", request.getRequestURI());
        ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED);
        if (exception.getSupportedHttpMethods() != null) {
            response.header(HttpHeaders.ALLOW, String.join(", ",
                    exception.getSupportedHttpMethods().stream().map(method -> method.name()).toList()));
        }
        return response.body(new ErrorResponse("METHOD_NOT_ALLOWED",
                "The HTTP method is not supported for this endpoint."));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleUnsupportedMediaType(HttpMediaTypeNotSupportedException exception,
                                                                    HttpServletRequest request) {
        log.info("event=REQUEST_FAILED status=415 error=UNSUPPORTED_MEDIA_TYPE path={}", request.getRequestURI());
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .body(new ErrorResponse("UNSUPPORTED_MEDIA_TYPE", "The request content type is not supported."));
    }

    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public ResponseEntity<ErrorResponse> handleNotAcceptable(HttpMediaTypeNotAcceptableException exception,
                                                              HttpServletRequest request) {
        log.info("event=REQUEST_FAILED status=406 error=NOT_ACCEPTABLE path={}", request.getRequestURI());
        return ResponseEntity.status(HttpStatus.NOT_ACCEPTABLE)
                .body(new ErrorResponse("NOT_ACCEPTABLE", "The requested response format is not available."));
    }

    private ResponseEntity<ErrorResponse> badRequest(String message, HttpServletRequest request) {
        log.info("event=REQUEST_FAILED status=400 error=BAD_REQUEST path={}", request.getRequestURI());
        return ResponseEntity.badRequest()
                .body(new ErrorResponse("BAD_REQUEST", message == null ? "The request is invalid." : message));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception exception, HttpServletRequest request) {
        log.error("event=REQUEST_FAILED status=500 error=INTERNAL_ERROR path={}",
                request.getRequestURI(), exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse("INTERNAL_ERROR", "An unexpected error occurred."));
    }
}
