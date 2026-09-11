package com.supplierconsumer.wire;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.util.HtmlUtils;

/**
 * Produces the HTML that Express's built-in final handler returned, for the situations Express
 * never routed to application code.
 *
 * <p>The Node app registers no error middleware and no catch-all route, so an unknown path, a
 * wrong method, or a malformed JSON body never reached a controller: they fell through to
 * {@code finalhandler}, which answers with a small HTML document rather than JSON. Spring would
 * instead return {@code {timestamp, status, error, path}} from {@code /error}, and a 405 where
 * Express gave a 404.
 *
 * <p>Emitting the original HTML is the conservative choice for a drop-in replacement: the
 * frontend's axios interceptor has never seen a JSON body on these paths, and inventing a
 * {@code {success:false}} envelope here would be a new behaviour rather than a preserved one.
 * Keeping it in one class makes that easy to revisit.
 */
public final class ExpressFinalHandler {

    private ExpressFinalHandler() {
    }

    /** Express answers an unmatched route with 404 and {@code Cannot <METHOD> <path>}. */
    public static ResponseEntity<String> cannotRoute(HttpServletRequest request) {
        String text = "Cannot " + request.getMethod() + " " + request.getRequestURI();
        return html(404, text);
    }

    /** A body Express could not parse ends up at the same place, as a 500. */
    public static ResponseEntity<String> badBody(String detail) {
        return html(500, detail);
    }

    public static ResponseEntity<String> html(int status, String text) {
        String escaped = HtmlUtils.htmlEscape(text);
        String body = """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                <meta charset="utf-8">
                <title>Error</title>
                </head>
                <body>
                <pre>%s</pre>
                </body>
                </html>
                """.formatted(escaped);
        return ResponseEntity.status(status)
                .contentType(MediaType.valueOf("text/html; charset=utf-8"))
                .header("X-Content-Type-Options", "nosniff")
                .body(body);
    }
}
