package local.attendance;

import java.io.IOException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Revokes disabled accounts and pre-reset sessions, including bootstrap teacher/admin. */
@Component
public class AccountStatusFilter extends OncePerRequestFilter {
    private final JdbcTemplate db;
    public AccountStatusFilter(JdbcTemplate db) { this.db = db; }
    @Override protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getName())) {
            HttpSession session = req.getSession(false);
            Object stored = session == null ? null : session.getAttribute("FACEATTEND_AUTH_VERSION");
            var rows = db.query("SELECT active,auth_version FROM app_users WHERE username=?",
                (rs,i)->new int[]{rs.getBoolean("active")?1:0,rs.getInt("auth_version")},auth.getName());
            if (rows.size() != 1 || rows.get(0)[0] != 1 || !(stored instanceof Number)
                    || ((Number) stored).intValue() != rows.get(0)[1]) {
                SecurityContextHolder.clearContext();
                if (session != null) session.invalidate();
                resp.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Session expired or account disabled");
                return;
            }
        }
        chain.doFilter(req, resp);
    }
}
