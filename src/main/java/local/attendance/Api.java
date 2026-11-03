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
    private final AttendanceTextService attendanceText;

    public Api(JdbcTemplate db, FaceService faces, AttendanceTextService attendanceText) {
        this.db = db;
        this.faces = faces;
        this.attendanceText = attendanceText;
    }

    @GetMapping("/csrf")
    public CsrfToken csrf(CsrfToken token) { return token; }

    @GetMapping("/students")
    public Object students() {
        return db.queryForList("SELECT s.*, (SELECT COUNT(*) FROM face_embeddings f WHERE f.student=s.id) AS samples, " +
            "(SELECT COUNT(*) FROM samples old WHERE old.student=s.id) AS legacy_samples FROM students s ORDER BY id");
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
        String roll = body.getOrDefault("roll", "").trim();
        String name = body.getOrDefault("name", "").trim();
        if (!roll.matches("[A-Za-z0-9_-]{1,50}") || name.isBlank() || name.length() > 100
                || !"true".equals(body.get("consent"))) {
            throw new IllegalArgumentException("Name, valid roll number and consent required");
        }
        db.update("INSERT INTO students(roll,name,gender) VALUES(?,?,?)", roll, name, gender(body.get("gender")));
        return Map.of("message", "Student created. Add at least 5 face samples.");
    }

    /* Update an existing student's self-reported gender without deleting their samples. */
    @PutMapping("/students/{id}/gender")
    public Object updateGender(@PathVariable int id, @RequestBody Map<String, String> body) {
        int changed = db.update("UPDATE students SET gender=? WHERE id=?", gender(body.get("gender")), id);
        if (changed == 0) throw new IllegalArgumentException("Student not found");
        return Map.of("message", "Student gender updated");
    }

    @PostMapping("/students/{id}/samples")
    @Transactional
    public Object sample(@PathVariable int id, @RequestParam MultipartFile image) throws Exception {
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
        int id = faces.identify(image.getBytes());
        if (id < 0) return Map.of("message", "Unknown face. Try again or mark manually.");
        return Map.of("message", "Candidate found — teacher must verify before marking", "candidate", id);
    }

    /* Does not save frames or attendance; returns rectangles and enrolled profile data only. */
    @PostMapping("/live")
    public Object live(@RequestParam MultipartFile image) throws Exception {
        FaceService.ScanResult result = faces.scan(image.getBytes());
        List<Map<String, Object>> boxes = new ArrayList<>();
        for (FaceService.FaceBox face : result.faces()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("x", face.x());
            item.put("y", face.y());
            item.put("width", face.width());
            item.put("height", face.height());
            if (face.studentId() != null) {
                var profile = db.queryForList("SELECT name, roll, gender FROM students WHERE id=?", face.studentId());
                if (!profile.isEmpty()) {
                    item.put("student", face.studentId());
                    item.put("name", profile.get(0).get("NAME"));
                    item.put("roll", profile.get(0).get("ROLL"));
                    item.put("gender", profile.get(0).get("GENDER"));
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
        return db.queryForList("SELECT a.*,s.name,s.roll,s.gender FROM attendance a JOIN students s ON s.id=a.student ORDER BY marked_at DESC");
    }

    @DeleteMapping("/attendance")
    public void remove(@RequestParam int student, @RequestParam String session) {
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
        StringBuilder b = new StringBuilder("Roll,Name,Gender,Session,Time,Method\n");
        for (var r : db.queryForList("SELECT s.roll,s.name,s.gender,a.session_name,a.marked_at,a.method "
                + "FROM attendance a JOIN students s ON s.id=a.student ORDER BY marked_at DESC")) {
            b.append(String.join(",", List.of(
                csv(r.get("ROLL")), csv(r.get("NAME")), csv(r.get("GENDER")),
                csv(r.get("SESSION_NAME")), csv(r.get("MARKED_AT")), csv(r.get("METHOD"))
            ))).append("\n");
        }
        return ResponseEntity.ok().header("Content-Disposition", "attachment; filename=attendance.csv").body(b.toString());
    }

    // Download the current human-readable table, not another new archive file.
    @GetMapping(value = "/export-text", produces = "text/plain;charset=UTF-8")
    public ResponseEntity<byte[]> exportText() throws IOException {
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType("text/plain;charset=UTF-8"))
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=attendance_register.txt")
            .body(attendanceText.refresh());
    }

    @ExceptionHandler({IllegalArgumentException.class, org.springframework.dao.DataIntegrityViolationException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Object error(Exception e) {
        return Map.of("message", e instanceof IllegalArgumentException ? e.getMessage()
            : "Duplicate roll number or invalid student reference");
    }
}
