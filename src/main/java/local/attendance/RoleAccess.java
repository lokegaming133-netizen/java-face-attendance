package local.attendance;

import java.util.Locale;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

/** Server-side authorization. Hiding a frontend control is never authorization. */
@Service
public class RoleAccess {
    private final JdbcTemplate db;
    public RoleAccess(JdbcTemplate db) { this.db = db; }

    public String username() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a == null || !a.isAuthenticated() || "anonymousUser".equals(a.getPrincipal()))
            throw new AccessDeniedException("Sign-in required");
        return a.getName();
    }
    public boolean isAdmin() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        return a != null && a.getAuthorities().stream().anyMatch(g -> g.getAuthority().equals("ROLE_ADMIN"));
    }
    /** Student profile and biometric enrollment permissions; not admin account management. */
    public boolean isFaculty() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        return a != null && a.isAuthenticated() &&
            a.getAuthorities().stream().anyMatch(g -> "ROLE_FACULTY".equals(g.getAuthority()));
    }
    public boolean canManageStudents() { return isAdmin() || isFaculty(); }
    public void studentManager() {
        if (!canManageStudents()) throw new AccessDeniedException("Faculty or Admin required for student management");
    }

    public boolean isTeacher() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        return a != null && "teacher".equals(a.getName()) &&
            a.getAuthorities().stream().anyMatch(g -> "ROLE_FACULTY".equals(g.getAuthority()));
    }
    public boolean isLegacyTeacherOrAdmin() { return isAdmin() || isTeacher(); }
    public void admin() { if (!isAdmin()) throw new AccessDeniedException("Administrator access required"); }

    public void classroom(String classroomId) {
        if (isLegacyTeacherOrAdmin()) return;
        Integer count = db.queryForObject("SELECT COUNT(*) FROM faculty_access WHERE username=? AND classroom_id=?",
                Integer.class, username(), classroomId);
        if (count == null || count == 0) throw new AccessDeniedException("Classroom not assigned to this faculty account");
    }
    public void subject(String classroomId, String subjectId) {
        if (isLegacyTeacherOrAdmin()) return;
        Integer count = db.queryForObject("SELECT COUNT(*) FROM faculty_access WHERE username=? AND classroom_id=? AND subject_id=?",
                Integer.class, username(), classroomId, subjectId);
        if (count == null || count == 0) throw new AccessDeniedException("Subject not assigned to this faculty account");
    }
    public void sessionId(String id) {
        if (isLegacyTeacherOrAdmin()) return;
        Integer count = db.queryForObject("SELECT COUNT(*) FROM class_sessions s " +
                "JOIN faculty_access fa ON fa.classroom_id=s.classroom_id AND fa.subject_id=s.subject_id " +
                "WHERE s.id=? AND fa.username=?", Integer.class, id, username());
        if (count == null || count == 0) throw new AccessDeniedException("Attendance session not assigned to this faculty account");
    }
    public void sessionKey(String key) {
        if (isLegacyTeacherOrAdmin()) return;
        Integer count = db.queryForObject("SELECT COUNT(*) FROM class_sessions s " +
                "JOIN faculty_access fa ON fa.classroom_id=s.classroom_id AND fa.subject_id=s.subject_id " +
                "WHERE s.session_key=? AND fa.username=?", Integer.class, key, username());
        if (count == null || count == 0) throw new AccessDeniedException("Attendance session not assigned to this faculty account");
    }
    public boolean canIdentify(int studentId, String key) {
        if (isLegacyTeacherOrAdmin()) return true;
        if (key == null || !key.startsWith("CLS-")) return false;
        Integer n = db.queryForObject("SELECT COUNT(*) FROM class_sessions s " +
                "JOIN class_session_students css ON css.session_id=s.id " +
                "JOIN faculty_access fa ON fa.classroom_id=s.classroom_id AND fa.subject_id=s.subject_id " +
                "WHERE s.session_key=? AND s.status='OPEN' AND css.student_id=? AND fa.username=?",
                Integer.class, key, studentId, username());
        return n != null && n > 0;
    }
}
