package local.attendance;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * One human-readable TXT register, regenerated from the H2 attendance table.
 * Rebuild rather than append, so corrections/deletions do not leave stale rows.
 */
@Service
public class AttendanceTextService {
    private static final Logger log = LoggerFactory.getLogger(AttendanceTextService.class);
    private static final String[] HEADERS = {
        "No", "Roll", "Student Name", "Gender", "Session", "Marked At", "Method"
    };
    private final JdbcTemplate db;
    private final Path output = Path.of("exports", "attendance_register.txt").toAbsolutePath().normalize();

    public AttendanceTextService(JdbcTemplate db) { this.db = db; }

    @EventListener(ApplicationReadyEvent.class)
    public void createAtStartup() { refreshSafely(); }

    /** Errors do not roll back already committed attendance in H2. */
    public boolean refreshSafely() {
        try { refresh(); return true; }
        catch (IOException | RuntimeException e) {
            log.warn("Attendance TXT file update failed: {}", output, e);
            return false;
        }
    }

    /** Always rewrites the same TXT file; returns exactly the saved bytes. */
    public synchronized byte[] refresh() throws IOException {
        List<String[]> rows = new ArrayList<>();
        db.query("SELECT s.roll, s.name, s.gender, a.session_name, a.marked_at, a.method " +
                "FROM attendance a JOIN students s ON s.id=a.student " +
                "ORDER BY a.marked_at, s.roll, a.session_name", rs -> {
            int n = rows.size() + 1;
            rows.add(new String[] {
                String.valueOf(n),
                clean(rs.getString(1)), clean(rs.getString(2)),
                clean(rs.getString(3)), clean(rs.getString(4)),
                clean(rs.getString(5)), clean(rs.getString(6))
            });
        });

        int[] widths = new int[HEADERS.length];
        for (int i=0; i<HEADERS.length; i++) widths[i] = HEADERS[i].length();
        for (String[] row : rows)
            for (int i=0; i<row.length; i++) widths[i] = Math.max(widths[i], row[i].length());

        String divider = divider(widths);
        StringBuilder out = new StringBuilder();
        out.append("JAVA FACE ATTENDANCE - REGISTER\r\n");
        out.append("Updated: ")
           .append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
           .append("\r\n");
        out.append("Total attendance records: ").append(rows.size()).append("\r\n\r\n");
        out.append(divider).append("\r\n");
        out.append(tableRow(HEADERS, widths)).append("\r\n");
        out.append(divider).append("\r\n");
        for (String[] row : rows) out.append(tableRow(row, widths)).append("\r\n");
        out.append(divider).append("\r\n");
        if (rows.isEmpty()) out.append("No attendance recorded yet.\r\n");

        // UTF-8 BOM ensures Hindi/Telugu/other Unicode names show in Notepad.
        byte[] bytes = ("\uFEFF" + out).getBytes(StandardCharsets.UTF_8);
        Files.createDirectories(output.getParent());
        Path temp = Files.createTempFile(output.getParent(), "attendance_", ".tmp");
        try {
            Files.write(temp, bytes, StandardOpenOption.TRUNCATE_EXISTING);
            try {
                Files.move(temp, output, StandardCopyOption.ATOMIC_MOVE,
                           StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, output, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
        return bytes;
    }

    private static String clean(String s) {
        if (s == null) return "";
        return s.replaceAll("[\\p{Cntrl}]", " ").replace('|', '/').trim();
    }

    private static String divider(int[] widths) {
        StringBuilder b = new StringBuilder("+");
        for (int width : widths) b.append("-".repeat(width + 2)).append('+');
        return b.toString();
    }

    private static String tableRow(String[] values, int[] widths) {
        StringBuilder b = new StringBuilder("|");
        for (int i=0; i<values.length; i++) {
            String value = Objects.toString(values[i], "");
            b.append(' ').append(value).append(" ".repeat(widths[i] - value.length() + 1)).append('|');
        }
        return b.toString();
    }
}
