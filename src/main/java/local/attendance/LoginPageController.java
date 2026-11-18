package local.attendance;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@Controller
public class LoginPageController {
    private static String read(String path) throws IOException {
        try (var stream = new ClassPathResource("static/" + path).getInputStream()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("\"", "&quot;")
                .replace("<", "&lt;").replace(">", "&gt;").replace("'", "&#39;");
    }
    private static ResponseEntity<String> page(String html) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .contentType(MediaType.TEXT_HTML).body(html);
    }
    @GetMapping({"/login", "/login.html"})
    public ResponseEntity<String> chooser() throws IOException { return page(read("login.html")); }

    @GetMapping("/login/{role}")
    public ResponseEntity<String> login(@PathVariable String role, HttpServletRequest request) throws IOException {
        Map<String, String[]> names = Map.of(
            "teacher", new String[]{"Teacher Sign In", "teacher", "FACULTY", "Take attendance, run camera, and review assigned classes."},
            "student", new String[]{"Student Sign In", "student.yourroll", "STUDENT", "View only your personal attendance and subject percentages."},
            "admin", new String[]{"Admin Sign In", "admin", "ADMIN", "Manage accounts, students, faculty, classes, and attendance."}
        );
        String[] values = names.get(role.toLowerCase(java.util.Locale.ROOT));
        if (values == null) return ResponseEntity.notFound().build();
        CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        if (token == null) throw new IllegalStateException("CSRF token not available");
        String field = "<input type=\"hidden\" name=\"" + escapeHtml(token.getParameterName()) +
            "\" value=\"" + escapeHtml(token.getToken()) + "\">";
        String html = read("role-login.html")
            .replace("<!--LOGIN_CSRF_FIELD-->", field)
            .replace("__ROLE_TITLE__", escapeHtml(values[0]))
            .replace("__ROLE_USERNAME__", escapeHtml(values[1]))
            .replace("__ROLE_KIND__", escapeHtml(values[2]))
            .replace("__ROLE_DESCRIPTION__", escapeHtml(values[3]));
        return page(html);
    }
}
