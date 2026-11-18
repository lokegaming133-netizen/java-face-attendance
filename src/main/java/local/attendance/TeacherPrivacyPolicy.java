package local.attendance;

import java.util.HashSet;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

/** Server-side student ownership. Never trust student IDs supplied by the browser. */
@Service
public class TeacherPrivacyPolicy {
    private final JdbcTemplate db;
    private final RoleAccess access;

    public TeacherPrivacyPolicy(JdbcTemplate db, RoleAccess access) {
        this.db = db;
        this.access = access;
    }

    public void requireStudent(int studentId) {
        if (access.isAdmin()) return;
        if (!access.isFaculty()) throw new AccessDeniedException("Teacher or administrator required");
        Integer count = db.queryForObject(
            "SELECT COUNT(*) FROM students WHERE id=? AND owner_username=?",
            Integer.class, studentId, access.username());
        if (count == null || count != 1) {
            throw new AccessDeniedException("Student is outside this teacher's workspace");
        }
    }

    public boolean ownsClassroom(String classroomId) {
        if (access.isAdmin()) return true;
        if (!access.isFaculty()) return false;
        Integer count = db.queryForObject(
            "SELECT COUNT(*) FROM classrooms WHERE id=? AND owner_username=?",
            Integer.class, classroomId, access.username());
        return count != null && count == 1;
    }

    public void requireOwnedClassroom(String classroomId) {
        if (!ownsClassroom(classroomId)) {
            throw new AccessDeniedException("Only a classroom owner or Admin may change its subjects");
        }
    }

    /** Only these IDs may participate in face matching, before computing similarity. */
    public Set<Integer> visibleStudentIds(String sessionKey) {
        if (access.isAdmin()) {
            return new HashSet<>(db.queryForList("SELECT id FROM students", Integer.class));
        }
        if (!access.isFaculty()) throw new AccessDeniedException("Teacher or administrator required");
        String teacher = access.username();
        if (sessionKey != null && sessionKey.startsWith("CLS-")) {
            access.sessionKey(sessionKey);
            return new HashSet<>(db.queryForList(
                "SELECT st.id FROM students st JOIN class_session_students css ON css.student_id=st.id " +
                "JOIN class_sessions cs ON cs.id=css.session_id " +
                "WHERE st.owner_username=? AND cs.session_key=? AND cs.status='OPEN'",
                Integer.class, teacher, sessionKey));
        }
        return new HashSet<>(db.queryForList(
            "SELECT id FROM students WHERE owner_username=?", Integer.class, teacher));
    }
}
