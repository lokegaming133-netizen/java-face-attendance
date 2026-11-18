package local.attendance;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/** Phase 1: classroom/subject/faculty/roster/period attendance administration. */
@RestController
@RequestMapping("/api/school")
public class SchoolApi {
    private final JdbcTemplate db;
    private final RoleAccess access;

    public SchoolApi(JdbcTemplate db, RoleAccess access) { this.db = db; this.access = access; }

    private List<Map<String, Object>> rows(String sql, Object... parameters) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : db.queryForList(sql, parameters)) {
            Map<String, Object> normalized = new LinkedHashMap<>();
            row.forEach((key, value) -> normalized.put(key.toLowerCase(Locale.ROOT), value));
            result.add(normalized);
        }
        return result;
    }

    private String value(Map<String, String> body, String key, int max) {
        String text = Objects.toString(body.get(key), "").trim();
        if (text.isBlank() || text.length() > max || text.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(key + " is required (maximum " + max + " characters)");
        }
        return text;
    }

    private String uuid(String supplied) {
        try { return UUID.fromString(supplied).toString(); }
        catch (RuntimeException exception) { throw new IllegalArgumentException("Invalid identifier"); }
    }

    private int studentId(String value) {
        try { int id = Integer.parseInt(value); if (id > 0) return id; }
        catch (NumberFormatException ignored) { }
        throw new IllegalArgumentException("Select a valid student");
    }

    private int period(String supplied) {
        try {
            int number = Integer.parseInt(supplied);
            if (number >= 1 && number <= 12) return number;
        } catch (NumberFormatException ignored) { }
        throw new IllegalArgumentException("Period must be a number from 1 to 12");
    }

    private void requireClassroom(String id) {
        Integer count = db.queryForObject("SELECT COUNT(*) FROM classrooms WHERE id=?", Integer.class, id);
        if (count == null || count == 0) throw new IllegalArgumentException("Classroom not found");
        access.classroom(id);
    }

    @GetMapping("/classrooms")
    public Object classrooms() {
        if (access.isLegacyTeacherOrAdmin()) return rows("SELECT * FROM classrooms ORDER BY academic_year DESC, name, section");
        return rows("SELECT DISTINCT c.* FROM classrooms c JOIN faculty_access fa ON fa.classroom_id=c.id " +
            "WHERE fa.username=? ORDER BY c.academic_year DESC,c.name,c.section", access.username());
    }

    @PostMapping("/classrooms")
    public Object createClassroom(@RequestBody Map<String, String> body) {
        access.admin();
        String name = value(body, "name", 80);
        String section = value(body, "section", 30);
        String year = value(body, "academicYear", 20);
        if (!year.matches("[0-9]{4}[-/][0-9]{2,4}")) {
            throw new IllegalArgumentException("Academic year format: 2026-27 or 2026-2027");
        }
        String id = UUID.randomUUID().toString();
        db.update("INSERT INTO classrooms(id,name,section,academic_year,created_at) VALUES(?,?,?,?,?)",
            id, name, section, year, OffsetDateTime.now().toString());
        return Map.of("message", "Classroom created", "id", id);
    }

    @GetMapping("/subjects")
    public Object subjects() {
        if (access.isLegacyTeacherOrAdmin()) return rows("SELECT * FROM subjects ORDER BY code");
        return rows("SELECT DISTINCT s.* FROM subjects s JOIN faculty_access fa ON fa.subject_id=s.id " +
            "WHERE fa.username=? ORDER BY s.code", access.username());
    }

    @PostMapping("/subjects")
    public Object createSubject(@RequestBody Map<String, String> body) {
        access.admin();
        String code = value(body, "code", 25).toUpperCase(Locale.ROOT);
        if (!code.matches("[A-Z0-9_-]{2,25}")) {
            throw new IllegalArgumentException("Subject code must contain letters, numbers, _ or -");
        }
        String title = value(body, "title", 120);
        String id = UUID.randomUUID().toString();
        db.update("INSERT INTO subjects(id,code,title) VALUES(?,?,?)", id, code, title);
        return Map.of("message", "Subject created", "id", id);
    }

    @GetMapping("/classrooms/{classroomId}/subjects")
    public Object classSubjects(@PathVariable String classroomId) {
        String id = uuid(classroomId);
        requireClassroom(id);
        if (access.isLegacyTeacherOrAdmin()) return rows("SELECT s.id, s.code, s.title, cs.faculty_name FROM classroom_subjects cs " +
            "JOIN subjects s ON s.id=cs.subject_id WHERE cs.classroom_id=? ORDER BY s.code", id);
        return rows("SELECT s.id,s.code,s.title,cs.faculty_name FROM classroom_subjects cs " +
            "JOIN subjects s ON s.id=cs.subject_id JOIN faculty_access fa " +
            "ON fa.classroom_id=cs.classroom_id AND fa.subject_id=cs.subject_id " +
            "WHERE cs.classroom_id=? AND fa.username=? ORDER BY s.code", id, access.username());
    }

    @PostMapping("/classrooms/{classroomId}/subjects")
    public Object assignSubject(@PathVariable String classroomId, @RequestBody Map<String, String> body) {
        access.admin();
        String id = uuid(classroomId);
        requireClassroom(id);
        String subject = uuid(value(body, "subjectId", 36));
        String faculty = value(body, "facultyName", 100);
        Integer exists = db.queryForObject("SELECT COUNT(*) FROM subjects WHERE id=?", Integer.class, subject);
        if (exists == null || exists == 0) throw new IllegalArgumentException("Subject not found");
        Integer already = db.queryForObject("SELECT COUNT(*) FROM classroom_subjects WHERE classroom_id=? AND subject_id=?",
            Integer.class, id, subject);
        if (already != null && already > 0) {
            db.update("UPDATE classroom_subjects SET faculty_name=? WHERE classroom_id=? AND subject_id=?",
                faculty, id, subject);
        } else {
            db.update("INSERT INTO classroom_subjects(classroom_id,subject_id,faculty_name) VALUES(?,?,?)",
                id, subject, faculty);
        }
        return Map.of("message", "Subject and faculty assigned");
    }

    @GetMapping("/classrooms/{classroomId}/students")
    public Object classStudents(@PathVariable String classroomId) {
        String id = uuid(classroomId);
        requireClassroom(id);
        return rows("SELECT s.id, s.roll, s.name FROM classroom_students cs " +
            "JOIN students s ON s.id=cs.student_id WHERE cs.classroom_id=? ORDER BY s.roll", id);
    }

    @PostMapping("/classrooms/{classroomId}/students")
    public Object addStudent(@PathVariable String classroomId, @RequestBody Map<String, String> body) {
        access.admin();
        String id = uuid(classroomId);
        requireClassroom(id);
        int student = studentId(value(body, "studentId", 16));
        db.update("INSERT INTO classroom_students(classroom_id,student_id) VALUES(?,?)", id, student);
        return Map.of("message", "Student added to classroom roster");
    }

    @DeleteMapping("/classrooms/{classroomId}/students/{studentId}")
    public Object removeStudent(@PathVariable String classroomId, @PathVariable int studentId) {
        access.admin();
        String id = uuid(classroomId);
        // Preserve historic attendance, biometric templates and the global student profile.
        int removed = db.update("DELETE FROM classroom_students WHERE classroom_id=? AND student_id=?", id, studentId);
        if (removed == 0) throw new IllegalArgumentException("Student not found in classroom roster");
        return Map.of("message", "Removed from this classroom only; historical attendance was preserved");
    }

    @GetMapping("/classrooms/{classroomId}/sessions")
    public Object sessions(@PathVariable String classroomId) {
        String id = uuid(classroomId);
        requireClassroom(id);
        if (access.isLegacyTeacherOrAdmin()) return rows("SELECT x.*, s.code AS subject_code, s.title AS subject_title " +
            "FROM class_sessions x JOIN subjects s ON s.id=x.subject_id " +
            "WHERE x.classroom_id=? ORDER BY x.session_date DESC, x.period_number DESC", id);
        return rows("SELECT x.*,s.code AS subject_code,s.title AS subject_title " +
            "FROM class_sessions x JOIN subjects s ON s.id=x.subject_id JOIN faculty_access fa " +
            "ON fa.classroom_id=x.classroom_id AND fa.subject_id=x.subject_id " +
            "WHERE x.classroom_id=? AND fa.username=? ORDER BY x.session_date DESC,x.period_number DESC",
            id, access.username());
    }

    @PostMapping("/classrooms/{classroomId}/sessions")
    @Transactional
    public Object startSession(@PathVariable String classroomId, @RequestBody Map<String, String> body) {
        String classroom = uuid(classroomId);
        requireClassroom(classroom);
        String subject = uuid(value(body, "subjectId", 36));
        access.subject(classroom, subject);
        String day = value(body, "date", 10);
        try { day = LocalDate.parse(day).toString(); }
        catch (RuntimeException ignored) { throw new IllegalArgumentException("Date must be YYYY-MM-DD"); }
        int period = period(value(body, "period", 2));
        var assigned = rows("SELECT faculty_name FROM classroom_subjects WHERE classroom_id=? AND subject_id=?", classroom, subject);
        if (assigned.isEmpty()) throw new IllegalArgumentException("Assign the subject to this classroom first");
        String facultyName = String.valueOf(assigned.get(0).get("faculty_name"));
        Integer rosterCount = db.queryForObject("SELECT COUNT(*) FROM classroom_students WHERE classroom_id=?",
            Integer.class, classroom);
        if (rosterCount == null || rosterCount == 0) throw new IllegalArgumentException("Add students to this classroom before starting a session");
        String id = UUID.randomUUID().toString();
        String key = "CLS-" + id.replace("-", "");
        db.update("INSERT INTO class_sessions(id,classroom_id,subject_id,session_date,period_number,faculty_name,session_key,status,created_at) " +
            "VALUES(?,?,?,?,?,?,?,?,?)", id, classroom, subject, day, period, facultyName, key, "OPEN", OffsetDateTime.now().toString());
        db.update("INSERT INTO class_session_students(session_id,student_id) " +
            "SELECT ?, student_id FROM classroom_students WHERE classroom_id=?", id, classroom);
        return Map.of("message", "Class attendance session started", "id", id, "sessionKey", key);
    }

    private Map<String, Object> findSession(String sessionId) {
        var matches = rows("SELECT x.*, s.code AS subject_code, s.title AS subject_title, " +
            "c.name AS classroom_name, c.section AS classroom_section FROM class_sessions x " +
            "JOIN subjects s ON s.id=x.subject_id JOIN classrooms c ON c.id=x.classroom_id WHERE x.id=?", uuid(sessionId));
        if (matches.isEmpty()) throw new IllegalArgumentException("Attendance session not found");
        access.sessionId(sessionId);
        return matches.get(0);
    }

    @GetMapping("/sessions/{sessionId}/report")
    public Object sessionReport(@PathVariable String sessionId) {
        var session = findSession(sessionId);
        var roster = rows("SELECT s.id, s.roll, s.name, " +
            "CASE WHEN a.student IS NULL THEN 'NOT_MARKED' ELSE 'PRESENT' END AS attendance_status, " +
            "a.marked_at, a.method FROM class_session_students css JOIN students s ON s.id=css.student_id " +
            "LEFT JOIN attendance a ON a.student=s.id AND a.session_name=? " +
            "WHERE css.session_id=? ORDER BY s.roll", session.get("session_key"), session.get("id"));
        long present = roster.stream().filter(row -> "PRESENT".equals(row.get("attendance_status"))).count();
        return Map.of("session", session, "roster", roster, "present", present, "total", roster.size());
    }

    @PostMapping("/sessions/{sessionId}/close")
    public Object closeSession(@PathVariable String sessionId) {
        String id = uuid(sessionId);
        findSession(id); // authorize faculty against this exact subject before modifying
        int affected = db.update("UPDATE class_sessions SET status='CLOSED' WHERE id=? AND status='OPEN'", id);
        if (affected == 0) throw new IllegalArgumentException("Session not found or already closed");
        return Map.of("message", "Session closed; additional attendance marking is blocked");
    }

    private String csv(Object input) {
        String text = Objects.toString(input, "");
        if (text.matches("^[=+@\\-].*")) text = "'" + text;
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }

    @GetMapping(value = "/sessions/{sessionId}/export", produces = "text/csv")
    public ResponseEntity<byte[]> export(@PathVariable String sessionId) {
        @SuppressWarnings("unchecked")
        Map<String, Object> report = (Map<String, Object>) sessionReport(sessionId);
        @SuppressWarnings("unchecked")
        var roster = (List<Map<String, Object>>) report.get("roster");
        @SuppressWarnings("unchecked")
        var session = (Map<String, Object>) report.get("session");
        StringBuilder out = new StringBuilder("Classroom,Section,Subject,Date,Period,Roll,Student,Status,Time,Method\r\n");
        for (var row : roster) {
            out.append(String.join(",", List.of(
                csv(session.get("classroom_name")), csv(session.get("classroom_section")),
                csv(session.get("subject_code")), csv(session.get("session_date")),
                csv(session.get("period_number")), csv(row.get("roll")), csv(row.get("name")),
                csv(row.get("attendance_status")), csv(row.get("marked_at")), csv(row.get("method"))
            ))).append("\r\n");
        }
        String name = "class_attendance_" + session.get("session_date") + "_P" + session.get("period_number") + ".csv";
        byte[] result = ("\uFEFF" + out).getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"")
            .body(result);
    }

    @ExceptionHandler({IllegalArgumentException.class, DataIntegrityViolationException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Object badInput(Exception error) {
        return Map.of("message", error instanceof IllegalArgumentException ? error.getMessage()
            : "Duplicate classroom/subject/session, invalid student, or database constraint violation");
    }
}
