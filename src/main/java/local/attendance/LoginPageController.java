package local.attendance;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** Renders the custom login page with a valid Spring Security CSRF form token. */
@Controller
public class LoginPageController {
    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;")
                .replace("\"", "&quot;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("'", "&#39;");
    }

    @GetMapping({"/login", "/login.html"})
    public ResponseEntity<String> showLogin(HttpServletRequest request) throws IOException {
        CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        if (token == null) {
            throw new IllegalStateException("Security token unavailable for login page");
        }
        String html;
        try (var source = new ClassPathResource("static/login.html").getInputStream()) {
            html = new String(source.readAllBytes(), StandardCharsets.UTF_8);
        }
        String csrfField = "<input type=\"hidden\" name=\""
                + escapeHtml(token.getParameterName())
                + "\" value=\"" + escapeHtml(token.getToken()) + "\">";
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.TEXT_HTML)
                .body(html.replace("<!--LOGIN_CSRF_FIELD-->", csrfField));
    }
}
