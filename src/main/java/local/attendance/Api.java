package local.attendance;

import java.time.OffsetDateTime;
import java.io.IOException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.*;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api")
public class Api {
    private final JdbcTemplate db;
    private final FaceService faces;
    private final SchoolService school;
    private final RoleAccess access;
    private final TeacherPrivacyPolicy privacy;
    private final AttendanceTextService attendanceText;

    public Api(JdbcTemplate db, FaceService faces, AttendanceTextService attendanceText, SchoolService school, RoleAccess access,
               TeacherPrivacyPolicy privacy) {
        this.db = db;
        this.faces = faces;
        this.school = school;
        this.access = access;
        this.privacy = privacy;
        this.attendanceText = attendanceText;
    }

    @GetMapping("/csrf")
    public CsrfToken csrf(CsrfToken token) { return token; }

    @GetMapping("/students")
    public Object students() {
        String base = "SELECT s.*, (SELECT COUNT(*) FROM face_embeddings f WHERE f.student=s.id) AS samples, " +
            "(SELECT COUNT(*) FROM samples old WHERE old.student=s.id) AS legacy_samples FROM students s ";
        if (access.isAdmin()) return db.queryForList(base + "ORDER BY id");
        return db.queryForList(base + "WHERE s.owner_username=? ORDER BY s.id", access.username());
    }

    private String gender(String supplied) {
        String value = supplied == null || supplied.isBlank() ? "Not specified" : supplied.trim();
        if (!Set.of("Not specified", "Male", "Female", "Non-binary", "Prefer not to say").contains(value)) {
            throw new IllegalArgumentException("Select a valid self-reported gender value");
        }
        return value;
    }

    @PostMapping("/students")
    public Object student(@RequestBody Map<String, String> body) {
    access.studentManager();
        String roll = body.getOrDefault("roll", "").trim();
        String name = body.getOrDefault("name", "").trim();
        if (!roll.matches("[A-Za-z0-9_-]{1,50}") || name.isBlank() || name.length() > 100
                || !"true".equals(body.get("consent"))) {
            throw new IllegalArgumentException("Name, valid roll number and consent required");
        }
        db.update("INSERT INTO students(roll,name,gender,owner_username) VALUES(?,?,?,?)",
            roll, name, gender(body.get("gender")), access.username());
        return Map.of("message", "Student created. Add at least 5 face samples.");
    }

    /* Update an existing student's self-reported gender without deleting their samples. */
    @PutMapping("/students/{id}/gender")
    public Object updateGender(@PathVariable int id, @RequestBody Map<String, String> body) {
        access.studentManager();
        privacy.requireStudent(id);
        int changed = db.update("UPDATE students SET gender=? WHERE id=?", gender(body.get("gender")), id);
        if (changed == 0) throw new IllegalArgumentException("Student not found");
        return Map.of("message", "Student gender updated");
    }

    @PostMapping("/students/{id}/samples")
    @Transactional
    public Object sample(@PathVariable int id, @RequestParam MultipartFile image) throws Exception {
        access.studentManager();
        privacy.requireStudent(id);
        Integer count = db.queryForObject("SELECT COUNT(*) FROM face_embeddings WHERE student=?", Integer.class, id);
        if (count != null && count >= 20) {
            throw new IllegalArgumentException("Maximum 20 new-model samples per student");
        }
        byte[] vector = faces.enroll(image.getBytes());
        db.update("INSERT INTO face_embeddings(student,embedding) VALUES(?,?)", id, vector);
        faces.invalidateModel();
        return Map.of("message", "SFace sample saved. Capture 5-10 fresh angles for best recognition.");
    }

    @DeleteMapping("/students/{id}")
    @Transactional
    public void delete(@PathVariable int id) {
        access.studentManager();
        privacy.requireStudent(id);
        db.update("UPDATE app_users SET active=FALSE,auth_version=auth_version+1 WHERE student_id=?", id);
        db.update("DELETE FROM attendance WHERE student=?", id);
        db.update("DELETE FROM face_embeddings WHERE student=?", id);
        db.update("DELETE FROM samples WHERE student=?", id);
        db.update("DELETE FROM students WHERE id=?", id);
        faces.invalidateModel();
        // The H2 transaction must commit before exporting the new register.
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { attendanceText.refreshSafely(); }
            });
        } else {
            attendanceText.refreshSafely();
        }
    }

    private String session(String value) {
        if (value == null || !value.matches("[A-Za-z0-9 _.-]{1,100}")) {
            throw new IllegalArgumentException("Session required: use date + class, e.g. 2026-11-02-Java-AM");
        }
        return value;
    }

    private Object mark(int id, String s, String method) {
        session(s);
        privacy.requireStudent(id);
        school.validateMark(id, s);
        try {
            db.update("INSERT INTO attendance(student,session_name,marked_at,method) VALUES(?,?,?,?)",
                id, s, OffsetDateTime.now().toString(), method);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            return Map.of("message", "Already marked for this session");
        }
        boolean synced = attendanceText.refreshSafely();
        return Map.of("message", synced ? "Attendance saved" : "Attendance saved in H2, but TXT update failed; check server logs", "student", id);
    }

    @PostMapping("/recognize")
    public Object recognize(@RequestParam MultipartFile image, @RequestParam String session) throws Exception {
        session(session);
        int id = faces.identify(image.getBytes(), privacy.visibleStudentIds(session));
        if (id >= 0 && !access.canIdentify(id, session)) id = -1;
        if (id < 0) return Map.of("message", "Unknown face. Try again or mark manually.");
        return Map.of("message", "Candidate found — teacher must verify before marking", "candidate", id);
    }

    /* Does not save frames or attendance; returns rectangles and enrolled profile data only. */
    @PostMapping("/live")
    public Object live(@RequestParam MultipartFile image,
                       @RequestParam(required = false) String session) throws Exception {
        FaceService.ScanResult result = faces.scan(image.getBytes(), privacy.visibleStudentIds(session));
        List<Map<String, Object>> boxes = new ArrayList<>();
        for (FaceService.FaceBox face : result.faces()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("x", face.x());
            item.put("y", face.y());
            item.put("width", face.width());
            item.put("height", face.height());
            if (face.studentId() != null && access.canIdentify(face.studentId(), session)) {
                var profile = db.queryForList("SELECT name, roll, gender FROM students WHERE id=?", face.studentId());
                if (!profile.isEmpty()) {
                    item.put("student", face.studentId());
                    item.put("name", SchoolService.column(profile.get(0), "name"));
                    item.put("roll", SchoolService.column(profile.get(0), "roll"));
                    item.put("gender", SchoolService.column(profile.get(0), "gender"));
                }
            }
            boxes.add(item);
        }
        return Map.of("imageWidth", result.width(), "imageHeight", result.height(), "faces", boxes);
    }

    @PostMapping("/mark")
    public Object manual(@RequestBody Map<String, String> body) {
        return mark(Integer.parseInt(body.get("student")), body.get("session"), "TEACHER_VERIFIED");
    }

    @GetMapping("/attendance")
    public Object records() {
        String sql = "SELECT a.*,s.name,s.roll,s.gender FROM attendance a JOIN students s ON s.id=a.student ";
        if (access.isAdmin()) return db.queryForList(sql + "ORDER BY a.marked_at DESC");
        return db.queryForList(sql + "WHERE s.owner_username=? ORDER BY a.marked_at DESC", access.username());
    }

    @DeleteMapping("/attendance")
    public void remove(@RequestParam int student, @RequestParam String session) {
        access.studentManager();
        privacy.requireStudent(student);
        db.update("DELETE FROM attendance WHERE student=? AND session_name=?", student, session);
        attendanceText.refreshSafely();
    }

    private String csv(Object value) {
        String s = Objects.toString(value, "");
        if (s.matches("^[=+@\\-].*")) s = "'" + s;
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    @GetMapping(value = "/export", produces = "text/csv")
    public ResponseEntity<String> export() {
        // A faculty member may export only students from their own private workspace.
        // Admin sees the full institution register.
        access.studentManager();
        String sql = "SELECT s.roll,s.name,s.gender,a.session_name,a.marked_at,a.method "
                + "FROM attendance a JOIN students s ON s.id=a.student "
                + (access.isAdmin() ? "" : "WHERE s.owner_username=? ")
                + "ORDER BY a.marked_at DESC";
        List<Map<String,Object>> rows = access.isAdmin()
                ? db.queryForList(sql) : db.queryForList(sql, access.username());
        StringBuilder b = new StringBuilder("\uFEFFRoll,Name,Gender,Session,Time,Method\r\n");
        for (var r : rows) {
            b.append(String.join(",", List.of(
                csv(SchoolService.column(r,"roll")), csv(SchoolService.column(r,"name")),
                csv(SchoolService.column(r,"gender")), csv(SchoolService.column(r,"session_name")),
                csv(SchoolService.column(r,"marked_at")), csv(SchoolService.column(r,"method"))
            ))).append("\r\n");
        }
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"attendance.csv\"")
            .body(b.toString());
    }

    // TXT download is generated in memory for both Admin and faculty; no cloud disk dependency.
    @GetMapping(value = "/export-text", produces = "text/plain;charset=UTF-8")
    public ResponseEntity<byte[]> exportText() throws IOException {
        access.studentManager();
        String sql = "SELECT s.roll,s.name,s.gender,a.session_name,a.marked_at,a.method " +
            "FROM attendance a JOIN students s ON s.id=a.student " +
            (access.isAdmin() ? "" : "WHERE s.owner_username=? ") +
            "ORDER BY a.marked_at DESC";
        var records = access.isAdmin() ? db.queryForList(sql) : db.queryForList(sql, access.username());
        String[] header = {"No", "Roll", "Student Name", "Gender", "Session", "Marked At", "Method"};
        List<String[]> lines = new ArrayList<>();
        int rowNumber = 0;
        for (var r : records) {
            lines.add(new String[] {
                Integer.toString(++rowNumber),
                String.valueOf(SchoolService.column(r, "roll")),
                String.valueOf(SchoolService.column(r, "name")),
                String.valueOf(SchoolService.column(r, "gender")),
                String.valueOf(SchoolService.column(r, "session_name")),
                String.valueOf(SchoolService.column(r, "marked_at")),
                String.valueOf(SchoolService.column(r, "method"))
            });
        }
        int[] widths = new int[header.length];
        for (int i = 0; i < widths.length; i++) widths[i] = header[i].length();
        for (String[] line : lines)
            for (int i = 0; i < line.length; i++) {
                line[i] = line[i].replaceAll("[\\p{Cntrl}|]", " ");
                widths[i] = Math.max(widths[i], line[i].length());
            }
        StringBuilder output = new StringBuilder("JAVA FACE ATTENDANCE - ATTENDANCE REGISTER\r\n");
        output.append("Teacher: ").append(access.username()).append("\r\n");
        output.append("Total attendance records: ").append(lines.size()).append("\r\n\r\n");
        List<String[]> all = new ArrayList<>();
        all.add(header);
        all.addAll(lines);
        for (int row = 0; row < all.size(); row++) {
            String[] values = all.get(row);
            for (int i = 0; i < values.length; i++) {
                output.append("| ").append(values[i]);
                output.append(" ".repeat(widths[i] - values[i].length() + 1));
            }
            output.append("|\r\n");
            if (row == 0) output.append("-".repeat(
                java.util.Arrays.stream(widths).sum() + widths.length * 3 + 1)).append("\r\n");
        }
        if (lines.isEmpty()) output.append("No attendance records in this Teacher workspace.\r\n");
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType("text/plain;charset=UTF-8"))
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"attendance_register.txt\"")
            .body(("\uFEFF" + output).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }    @ExceptionHandler({IllegalArgumentException.class, org.springframework.dao.DataIntegrityViolationException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Object error(Exception e) {
        return Map.of("message", e instanceof IllegalArgumentException ? e.getMessage()
            : "Duplicate roll number or invalid student reference");
    }
}
