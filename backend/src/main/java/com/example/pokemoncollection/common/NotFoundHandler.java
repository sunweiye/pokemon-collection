package com.example.pokemoncollection.common;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@ControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class NotFoundHandler {
    private static final Logger log = LoggerFactory.getLogger(NotFoundHandler.class);

    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
    public Object handleNotFound(Exception exception, HttpServletRequest request) {
        String path = request.getRequestURI();
        log.info("event=REQUEST_FAILED status=404 error=NOT_FOUND path={}", path);

        if (path.equals("/api") || path.startsWith("/api/")) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new ErrorResponse("NOT_FOUND", "The requested resource was not found."));
        }

        if (path.startsWith("/assets/")) {
            return ResponseEntity.notFound().build();
        }

        ModelAndView view = new ModelAndView("error/404");
        view.setStatus(HttpStatus.NOT_FOUND);
        return view;
    }
}
