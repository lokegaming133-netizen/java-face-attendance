package local.attendance;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;

/** Account creation is administrator-only; there is intentionally no public sign-up. */
@RestController
@RequestMapping("/api/admin")
public class AdminAccountApi {
    private final JdbcTemplate db;
    private final BCryptPasswordEncoder encoder;
    private final RoleAccess access;
    public AdminAccountApi(JdbcTemplate db, BCryptPasswordEncoder encoder, RoleAccess access) {
        this.db = db; this.encoder = encoder; this.access = access;
    }
    private String required(Map<String, ?> body, String key, int max) {
        String val = Objects.toString(body.get(key), "").trim();
        if (val.isEmpty() || val.length() > max) throw new IllegalArgumentException(key + " required (max " + max + ")");
        return val;
    }
    private String password(Map<String, ?> body) {
        // No plaintext passwords are logged, stored in source or returned by the API.
        String p = Objects.toString(body.get("password"), "");
        if (p.length() < 12 || p.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72)
            throw new IllegalArgumentException("Password must be 12+ characters and at most 72 UTF-8 bytes");
        return p;
    }
    private String accountRole(String supplied) {
        String r = supplied.toUpperCase(Locale.ROOT);
        if (!List.of("FACULTY", "STUDENT").contains(r)) throw new IllegalArgumentException("Invalid role");
        return r;
    }
    private List<Map<String, Object>> normalize(List<Map<String, Object>> raw) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (var item : raw) {
            Map<String, Object> row = new LinkedHashMap<>();
            item.forEach((key, value) -> row.put(key.toLowerCase(Locale.ROOT), value));
            out.add(row);
        }
        return out;
    }
    @GetMapping("/accounts") public Object list() {
        access.admin();
        return normalize(db.queryForList("SELECT u.username,u.account_role,u.active,u.student_id,s.roll,s.name, " +
                "u.created_at,u.email FROM app_users u LEFT JOIN students s ON s.id=u.student_id ORDER BY u.account_role,u.username"));
    }
    @PostMapping("/accounts") public Object create(@RequestBody Map<String, Object> body) {
        access.admin();
        String username = required(body,"username",50).toLowerCase(Locale.ROOT);
        if (!username.matches("[a-z][a-z0-9._-]{2,49}") || ("teacher".equals(username) || "admin".equals(username)))
            throw new IllegalArgumentException("Username must be 3–50 letters/numbers and can include ._- (teacher and admin are reserved)");
        String role = accountRole(required(body,"role",16));
        String email = EmailOtpService.normalizedEmail(required(body,"email",254));
        String p = password(body);
        Integer studentId = null;
        if (role.equals("STUDENT")) {
            try { studentId = Integer.valueOf(required(body,"studentId",10)); }
            catch (NumberFormatException bad) { throw new IllegalArgumentException("Select an existing student"); }
            Integer exists = db.queryForObject("SELECT COUNT(*) FROM students WHERE id=?", Integer.class, studentId);
            if (exists == null || exists == 0) throw new IllegalArgumentException("Student profile not found");
        }
        db.update("INSERT INTO app_users(username,password_hash,account_role,active,student_id,created_at,email,auth_version) VALUES(?,?,?,TRUE,?,?,?,0)",
                username, encoder.encode(p), role, studentId, OffsetDateTime.now().toString(), email);
        return Map.of("message", "Account created", "username", username, "role", role);
    }
    @PostMapping("/accounts/{username}/email") public Object setEmail(@PathVariable String username,
                                                                  @RequestBody Map<String, Object> body) {
        access.admin();
        String email = EmailOtpService.normalizedEmail(required(body, "email", 254));
        int changed = db.update("UPDATE app_users SET email=?,auth_version=auth_version+1 WHERE username=?",
                                email, username);
        if (changed == 0) throw new IllegalArgumentException("Account not found");
        return Map.of("message", "Registered email updated. Old sessions revoked; user must verify an OTP to sign in.");
    }
    @PostMapping("/accounts/{username}/password") public Object reset(@PathVariable String username,
                                                               @RequestBody Map<String, Object> body) {
        access.admin();
        int changed = db.update("UPDATE app_users SET password_hash=?,auth_version=auth_version+1 WHERE username=?", encoder.encode(password(body)), username);
        if (changed == 0) throw new IllegalArgumentException("Account not found");
        return Map.of("message", "Password updated; share it privately with the account owner");
    }
    @PostMapping("/accounts/{username}/status") public Object status(@PathVariable String username,
                                                               @RequestBody Map<String, Object> body) {
        access.admin();
        if (username.equals(access.username())) throw new IllegalArgumentException("Cannot disable your own account");
        Object flag = body.get("active");
        if (!(flag instanceof Boolean)) throw new IllegalArgumentException("active must be true/false");
        int changed = db.update("UPDATE app_users SET active=?,auth_version=auth_version+1 WHERE username=?", flag, username);
        if (changed == 0) throw new IllegalArgumentException("Account not found");
        return Map.of("message", Boolean.TRUE.equals(flag) ? "Account enabled" : "Account disabled");
    }
    /** Remove only an application login, never the linked student or attendance records. */
    @DeleteMapping("/accounts/{username}") public Object deleteAccount(@PathVariable String username) {
        access.admin();
        String target = username.trim().toLowerCase(Locale.ROOT);
        if ("teacher".equals(target) || "admin".equals(target) || target.equals(access.username()))
            throw new IllegalArgumentException("Built-in accounts cannot be deleted");
        // faculty_access rows are removed by their ON DELETE CASCADE foreign key.
        // The students/face_embeddings/attendance tables remain untouched.
        int removed = db.update("DELETE FROM app_users WHERE username=? AND account_role IN ('STUDENT','FACULTY')", target);
        if (removed == 0) throw new IllegalArgumentException("Student or faculty login not found");
        return Map.of("message", "Login deleted. Student profile, face samples, and attendance are preserved.");
    }
    @GetMapping("/assignments") public Object assignments() {
        access.admin();
        return normalize(db.queryForList("SELECT fa.username,fa.classroom_id,fa.subject_id, " +
                "c.name AS classroom_name,c.section,s.code AS subject_code,s.title AS subject_title " +
                "FROM faculty_access fa JOIN classrooms c ON c.id=fa.classroom_id " +
                "JOIN subjects s ON s.id=fa.subject_id ORDER BY fa.username,c.name,s.code"));
    }
    @PostMapping("/assignments") public Object assign(@RequestBody Map<String, Object> body) {
        access.admin();
        String username = required(body,"username",50);
        String classroom = required(body,"classroomId",36);
        String subject = required(body,"subjectId",36);
        Integer faculty = db.queryForObject("SELECT COUNT(*) FROM app_users WHERE username=? AND account_role='FACULTY' AND active=TRUE",
                Integer.class, username);
        if (faculty == null || faculty == 0) throw new IllegalArgumentException("Select an active faculty account");
        Integer exists = db.queryForObject("SELECT COUNT(*) FROM classroom_subjects WHERE classroom_id=? AND subject_id=?",
                Integer.class, classroom, subject);
        if (exists == null || exists == 0) throw new IllegalArgumentException("Assign this subject to the classroom first");
        db.update("INSERT INTO faculty_access(username,classroom_id,subject_id) VALUES(?,?,?)", username,classroom,subject);
        return Map.of("message", "Faculty can now access this classroom subject");
    }
    @DeleteMapping("/assignments") public Object unassign(@RequestParam String username,
                                               @RequestParam String classroomId, @RequestParam String subjectId) {
        access.admin();
        int n = db.update("DELETE FROM faculty_access WHERE username=? AND classroom_id=? AND subject_id=?",
                username, classroomId, subjectId);
        if (n == 0) throw new IllegalArgumentException("Assignment not found");
        return Map.of("message", "Faculty assignment removed");
    }
    @ExceptionHandler({IllegalArgumentException.class, DataIntegrityViolationException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Object invalid(Exception ex) {
        return Map.of("message", ex instanceof IllegalArgumentException ? ex.getMessage()
                : "Duplicate account/assignment or invalid student reference");
    }
}
