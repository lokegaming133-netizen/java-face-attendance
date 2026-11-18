package local.attendance;

import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Validates classroom-scoped attendance while preserving existing manual sessions. */
@Service
public class SchoolService {
    private final JdbcTemplate db;
    private final RoleAccess access;

    public SchoolService(JdbcTemplate db, RoleAccess access) { this.db = db; this.access = access; }

    public void validateMark(int studentId, String sessionKey) {
        if (sessionKey == null || !sessionKey.startsWith("CLS-")) {
            // Legacy free-form session names are reserved for administrators.
            if (!access.isTeacher()) access.admin();
            return;
        }
        access.sessionKey(sessionKey);

        var sessions = db.queryForList(
            "SELECT id, status FROM class_sessions WHERE session_key=?", sessionKey);
        if (sessions.isEmpty()) {
            throw new IllegalArgumentException("Classroom session not found; please select an active session");
        }
        Map<String, Object> record = sessions.get(0);
        String status = String.valueOf(column(record, "status"));
        if (!"OPEN".equalsIgnoreCase(status)) {
            throw new IllegalArgumentException("This classroom session is closed");
        }
        String sessionId = String.valueOf(column(record, "id"));
        Integer enrolled = db.queryForObject(
            "SELECT COUNT(*) FROM class_session_students WHERE session_id=? AND student_id=?",
            Integer.class, sessionId, studentId);
        if (enrolled == null || enrolled == 0) {
            throw new IllegalArgumentException("Selected student is not enrolled in this attendance session");
        }
    }

    static Object column(Map<String, Object> row, String key) {
        for (var entry : row.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(key)) return entry.getValue();
        }
        return null;
    }
}
