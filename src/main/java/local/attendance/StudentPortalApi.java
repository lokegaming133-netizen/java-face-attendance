package local.attendance;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Student ID is resolved from the logged-in account, never from the request URL. */
@RestController
@RequestMapping("/api/portal")
public class StudentPortalApi {
    private final JdbcTemplate db;
    private final RoleAccess access;
    public StudentPortalApi(JdbcTemplate db, RoleAccess access) { this.db=db; this.access=access; }
    @GetMapping("/attendance") public Object attendance() {
        var link = db.queryForList("SELECT s.id,s.name,s.roll FROM app_users u JOIN students s ON s.id=u.student_id " +
                "WHERE u.username=? AND u.active=TRUE AND u.account_role='STUDENT'", access.username());
        if (link.size()!=1) throw new AccessDeniedException("Student account has no enrolled profile");
        Map<String,Object> student = new LinkedHashMap<>();
        link.get(0).forEach((k,v)->student.put(k.toLowerCase(Locale.ROOT),v));
        int studentId = ((Number)student.get("id")).intValue();
        var rows = db.queryForList("SELECT x.id,x.session_date,x.period_number,x.status, " +
                "c.name AS classroom_name,c.section,s.code AS subject_code,s.title AS subject_title,a.marked_at " +
                "FROM class_session_students css JOIN class_sessions x ON x.id=css.session_id " +
                "JOIN classrooms c ON c.id=x.classroom_id JOIN subjects s ON s.id=x.subject_id " +
                "LEFT JOIN attendance a ON a.student=css.student_id AND a.session_name=x.session_key " +
                "WHERE css.student_id=? ORDER BY x.session_date DESC,x.period_number DESC",studentId);
        List<Map<String,Object>> sessions = new ArrayList<>();
        Map<String,Map<String,Object>> summary = new LinkedHashMap<>();
        for(var raw: rows) {
            Map<String,Object> r = new LinkedHashMap<>(); raw.forEach((k,v)->r.put(k.toLowerCase(Locale.ROOT),v));
            boolean closed = "CLOSED".equalsIgnoreCase(String.valueOf(r.get("status")));
            boolean present = r.get("marked_at")!=null;
            r.put("attendance_status",present?"PRESENT":closed?"NOT_MARKED":"IN_PROGRESS");
            sessions.add(r);
            String code = String.valueOf(r.get("subject_code")) + " · " + String.valueOf(r.get("classroom_name"));
            Map<String,Object> aggregate = summary.computeIfAbsent(code,k->{
                Map<String,Object> m=new LinkedHashMap<>(); m.put("code",code);m.put("title",r.get("subject_title"));
                m.put("held",0);m.put("present",0);return m;
            });
            // Only CLOSED sessions count toward the final attendance denominator.
            if(closed) {
                aggregate.put("held",((Integer)aggregate.get("held"))+1);
                if(present) aggregate.put("present",((Integer)aggregate.get("present"))+1);
            }
        }
        for(var entry: summary.values()) {
            int held=(Integer)entry.get("held"), present=(Integer)entry.get("present");
            entry.put("percentage",held==0?null:Math.round(present*10000.0/held)/100.0);
        }
        return Map.of("student",student,"subjects",new ArrayList<>(summary.values()),"sessions",sessions);
    }
}
