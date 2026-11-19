package local.attendance;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/** Administrator-only repair for a Student portal linked to a different profile ID.
 * Student records, face embeddings, attendance, sessions and ownership are untouched.
 */
@RestController
@RequestMapping("/api/admin/student-link-repair")
public class StudentLinkRepairApi {
    private final JdbcTemplate db;
    private final RoleAccess access;
    public StudentLinkRepairApi(JdbcTemplate db, RoleAccess access) {
        this.db = db;
        this.access = access;
    }

    @GetMapping("/audit")
    public Map<String, Object> audit() {
        access.admin();
        var logins = db.queryForList(
            "SELECT u.username, u.student_id, s.roll, s.name, s.owner_username " +
            "FROM app_users u LEFT JOIN students s ON s.id=u.student_id " +
            "WHERE u.account_role='STUDENT' ORDER BY u.username");
        var profiles = db.queryForList(
            "SELECT s.id, s.roll, s.name, s.owner_username, " +
            "(SELECT COUNT(*) FROM attendance a WHERE a.student=s.id) AS attendance_marks, " +
            "(SELECT COUNT(*) FROM class_session_students css WHERE css.student_id=s.id) AS class_sessions " +
            "FROM students s ORDER BY s.roll, s.owner_username, s.id");
        return Map.of("logins", normalized(logins), "profiles", normalized(profiles));
    }

    private List<Map<String, Object>> normalized(List<Map<String, Object>> raw) {
        return raw.stream().map(row -> {
            Map<String, Object> result = new LinkedHashMap<>();
            row.forEach((key, value) -> result.put(key.toLowerCase(Locale.ROOT), value));
            return result;
        }).toList();
    }

    @PostMapping("/relink")
    @Transactional
    public Map<String, Object> relink(@RequestBody Map<String, Object> body) {
        access.admin();
        String username = Objects.toString(body.get("username"), "").trim();
        if (!username.matches("[A-Za-z0-9._-]{3,50}")) {
            throw new IllegalArgumentException("Select a valid existing student login");
        }
        int studentId;
        try { studentId = Integer.parseInt(Objects.toString(body.get("studentId"), "")); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("Select a valid student profile"); }
        if (studentId <= 0) throw new IllegalArgumentException("Select a valid student profile");

        var accounts = db.queryForList(
            "SELECT u.student_id, s.roll AS current_roll FROM app_users u " +
            "LEFT JOIN students s ON s.id=u.student_id " +
            "WHERE u.username=? AND u.account_role='STUDENT' AND u.active=TRUE", username);
        if (accounts.size() != 1) throw new IllegalArgumentException("Active student login not found");
        var targets = db.queryForList("SELECT id,roll,name,owner_username FROM students WHERE id=?", studentId);
        if (targets.size() != 1) throw new IllegalArgumentException("Selected student profile not found");
        var current = accounts.get(0);
        var target = targets.get(0);
        String currentRoll = Objects.toString(SchoolService.column(current, "current_roll"), "");
        String targetRoll = Objects.toString(SchoolService.column(target, "roll"), "");
        // Prevent accidentally linking an account belonging to a completely different roll.
        if (!currentRoll.isBlank() && !currentRoll.equalsIgnoreCase(targetRoll)) {
            throw new IllegalArgumentException("Student roll mismatch. Select a profile with the same roll number");
        }
        Integer claimed = db.queryForObject(
            "SELECT COUNT(*) FROM app_users WHERE student_id=? AND username<>?", Integer.class, studentId, username);
        if (claimed != null && claimed > 0) {
            throw new IllegalArgumentException("That student profile is already linked to another login");
        }
        int changed = db.update(
            "UPDATE app_users SET student_id=?, auth_version=auth_version+1 " +
            "WHERE username=? AND account_role='STUDENT' AND active=TRUE", studentId, username);
        if (changed != 1) throw new IllegalArgumentException("Student login not found");
        return Map.of("message", "Student login linked to selected profile. Student must sign in again.",
            "studentId", studentId, "roll", targetRoll);
    }

    @ExceptionHandler({IllegalArgumentException.class, DataIntegrityViolationException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> badInput(Exception e) {
        return Map.of("message", e instanceof IllegalArgumentException ? e.getMessage() :
            "Student link already assigned to another account, or invalid reference");
    }
}
