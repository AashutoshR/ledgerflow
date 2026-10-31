package dev.ledgerflow.walletledger;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@org.springframework.core.annotation.Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE)
class RequestIds extends OncePerRequestFilter {
  protected void doFilterInternal(
      HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws ServletException, IOException {
    String incoming = req.getHeader("X-Request-ID");
    String id =
        incoming != null
                && incoming.matches(
                    "[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}")
            ? incoming
            : UUID.randomUUID().toString();
    req.setAttribute("requestId", id);
    res.setHeader("X-Request-ID", id);
    chain.doFilter(req, res);
  }
}

@RestControllerAdvice
class Errors {
  @ExceptionHandler(LedgerException.class)
  ResponseEntity<?> business(LedgerException e, HttpServletRequest r) {
    return ResponseEntity.status(e.status)
        .body(Map.of("code", e.code, "requestId", r.getAttribute("requestId")));
  }

  @ExceptionHandler({
    org.springframework.http.converter.HttpMessageNotReadableException.class,
    org.springframework.web.bind.MissingRequestHeaderException.class,
    org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class
  })
  ResponseEntity<?> invalid(Exception e, HttpServletRequest r) {
    return ResponseEntity.badRequest()
        .body(Map.of("code", "INVALID_REQUEST", "requestId", r.getAttribute("requestId")));
  }
}
