package com.example.nms.api;

import com.example.nms.observability.RedactedThrowable;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Replaces Boot's whitelabel/JSON error page: every {@code /error} dispatch (container {@code
 * sendError}, exceptions thrown by filters, direct requests) gets the standard error body. Neither
 * the path nor the exception message is echoed. A direct request with no error is 404.
 */
@RestController
class ErrorPageController implements ErrorController {

  private static final Logger log = LoggerFactory.getLogger(ErrorPageController.class);

  private final ErrorResponseWriter writer;

  ErrorPageController(ErrorResponseWriter writer) {
    this.writer = writer;
  }

  @RequestMapping("${server.error.path:${error.path:/error}}")
  void error(HttpServletRequest request, HttpServletResponse response) throws IOException {
    HttpStatusCode status =
        request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE) instanceof Integer code
                && code >= 400
                && code <= 599
            ? HttpStatusCode.valueOf(code)
            : HttpStatus.NOT_FOUND;
    if (request.getAttribute(RequestDispatcher.ERROR_EXCEPTION) instanceof Throwable ex) {
      if (status.is5xxServerError()) {
        log.error("Request failed outside a controller", RedactedThrowable.of(ex));
      } else {
        log.debug("Request rejected outside a controller ({})", ex.getClass().getName());
      }
    }
    writer.write(response, status, List.of(ApiError.forStatus(status)));
  }
}
