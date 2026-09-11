package com.supplierconsumer.wire;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.env.Environment;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.NoHandlerFoundException;

import java.util.Arrays;

/**
 * Turns exceptions into the bodies the Express app produced.
 *
 * <p>Two distinct jobs. Application errors become the {@code {success:false, message}} envelope,
 * plus whatever extra keys the endpoint contributed. Framework errors have to be
 * <em>suppressed</em> rather than reformatted: Spring generates responses for cases Express never
 * generated at all, and returning Spring's version would be a visible change.
 */
@RestControllerAdvice
public class WireExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(WireExceptionHandler.class);

    private final boolean devProfile;

    public WireExceptionHandler(Environment environment) {
        this.devProfile = Arrays.asList(environment.getActiveProfiles()).contains("dev");
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiResponse> handleApi(ApiException ex) {
        ApiResponse body = ApiResponse.fail().message(ex.getMessage());
        ex.extras().forEach(body::put);
        return ResponseEntity.status(ex.status()).body(body);
    }

    /**
     * Express had no route table fallback, so an unknown path produced finalhandler's HTML 404.
     * A wrong method fell through to exactly the same place -- hence no 405 here.
     */
    @ExceptionHandler({NoHandlerFoundException.class, HttpRequestMethodNotSupportedException.class})
    public ResponseEntity<String> handleNoRoute(HttpServletRequest request) {
        return ExpressFinalHandler.cannotRoute(request);
    }

    /**
     * {@code express.json()} rejects an unparseable body before any handler runs, and with no
     * error middleware registered that surfaces as finalhandler HTML rather than JSON.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<String> handleUnreadableBody(HttpMessageNotReadableException ex) {
        log.debug("Unparseable request body", ex);
        return ExpressFinalHandler.badBody("Unexpected token in JSON");
    }

    /** multer throws past its size limit, and that too became an HTML 500. */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<String> handleTooLarge(MaxUploadSizeExceededException ex) {
        log.warn("Upload exceeded the configured size limit", ex);
        return ExpressFinalHandler.badBody("File too large");
    }

    /**
     * Anything else is a 500, shaped according to the handler's {@link WireError} policy. The
     * {@link HandlerMethod} argument is how the policy is discovered -- it is the controller
     * method Spring had already matched when the exception escaped.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse> handleUnexpected(Exception ex, HandlerMethod handler) {
        WireError spec = resolve(handler);
        log.error("Unhandled exception in {}",
                handler == null ? "unknown handler" : handler.getMethod().getName(), ex);

        ApiResponse body = ApiResponse.fail().message(spec.message());
        if (spec.detail() == WireError.ErrorDetail.ALWAYS
                || (spec.detail() == WireError.ErrorDetail.DEV_ONLY && devProfile)) {
            body.put("error", ex.getMessage());
        }
        return ResponseEntity.status(500).body(body);
    }

    /** Method annotation wins over class annotation; absent both, the plain default applies. */
    private WireError resolve(HandlerMethod handler) {
        if (handler != null) {
            WireError onMethod = handler.getMethodAnnotation(WireError.class);
            if (onMethod != null) {
                return onMethod;
            }
            WireError onClass = AnnotationUtils.findAnnotation(handler.getBeanType(), WireError.class);
            if (onClass != null) {
                return onClass;
            }
        }
        return DEFAULT_WIRE_ERROR;
    }

    @WireError
    private static final class Defaults {
    }

    private static final WireError DEFAULT_WIRE_ERROR =
            AnnotationUtils.findAnnotation(Defaults.class, WireError.class);
}
