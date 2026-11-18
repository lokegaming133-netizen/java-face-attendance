package local.attendance;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * A roll is unique INSIDE one teacher's private student workspace.
 * Existing student ids, face embeddings, attendance records, and account links
 * are retained. Runs after Spring's schema.sql / schema-postgresql.sql.
 * Supports both PostgreSQL and H2-generated constraint names.
 */
@Component
public final class TeacherRollUniquenessMigration implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(TeacherRollUniquenessMigration.class);
    private final JdbcTemplate db;

    public TeacherRollUniquenessMigration(JdbcTemplate db) { this.db = db; }

    @Override
    public void run(ApplicationArguments args) {
        // Backfill ONLY missing owners. Never transfer existing students automatically.
        db.update("UPDATE students SET owner_username='teacher' WHERE owner_username IS NULL");

        // Catch any accidental same-teacher duplicates BEFORE changing constraints.
        Integer clashes = db.queryForObject(
            "SELECT COUNT(*) FROM (SELECT owner_username, roll FROM students " +
            "GROUP BY owner_username, roll HAVING COUNT(*) > 1) duplicates", Integer.class);
        if (clashes != null && clashes > 0) {
            throw new IllegalStateException("Duplicate student rolls already exist within one teacher; " +
                "resolve them before applying the per-teacher uniqueness migration");
        }

        // Both H2 and PostgreSQL expose UNIQUE constraints through standard information_schema.
        // Filter by column list: never drop the primary key or other multi-column constraints.
        String lookup = "SELECT tc.constraint_name, kcu.column_name " +
            "FROM information_schema.table_constraints tc " +
            "JOIN information_schema.key_column_usage kcu ON " +
            "tc.constraint_catalog=kcu.constraint_catalog AND " +
            "tc.constraint_schema=kcu.constraint_schema AND " +
            "tc.constraint_name=kcu.constraint_name AND " +
            "tc.table_schema=kcu.table_schema AND tc.table_name=kcu.table_name " +
            "WHERE LOWER(tc.table_name)='students' AND tc.constraint_type='UNIQUE' " +
            "ORDER BY tc.constraint_name, kcu.ordinal_position";
        Map<String, Set<String>> byConstraint = new LinkedHashMap<>();
        db.query(lookup, rs -> {
            byConstraint.computeIfAbsent(rs.getString(1), ignored -> new LinkedHashSet<>())
                .add(rs.getString(2).toLowerCase(java.util.Locale.ROOT));
        });

        // The desired constraint is (owner_username, roll), not (roll) globally.
        // Create new protection first; then remove ONLY the old roll-only UNIQUE constraint.
        db.execute("CREATE UNIQUE INDEX IF NOT EXISTS uq_students_owner_roll " +
            "ON students(owner_username, roll)");
        for (Map.Entry<String, Set<String>> entry : byConstraint.entrySet()) {
            if (entry.getValue().size() == 1 && entry.getValue().contains("roll")) {
                String safe = entry.getKey().replace("\"", "\"\"");
                db.execute("ALTER TABLE students DROP CONSTRAINT \"" + safe + "\"");
                log.info("Upgraded old global student-roll constraint to per-teacher uniqueness");
            }
        }
        log.info("Teacher-specific student roll uniqueness is ready; existing student records preserved");
    }
}
