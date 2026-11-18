package local.attendance;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Accounts are still indexed by their internal usernames; login uses unique email. */
@Service
public class EmailOtpService implements ApplicationRunner {
    private static final Logger LOG = LoggerFactory.getLogger(EmailOtpService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final long OTP_LIFETIME = 300L;
    private final JdbcTemplate db;
    private final BCryptPasswordEncoder encoder;
    private final JavaMailSender mail;
    private final byte[] hmacKey;
    private final String sender;
    private final String dummyPasswordHash;

    public record Account(String username, String email, String role, boolean active,
                          String passwordHash, int version) {}

    public EmailOtpService(JdbcTemplate db, BCryptPasswordEncoder encoder, JavaMailSender mail) {
        this.db = db;
        this.encoder = encoder;
        this.mail = mail;
        String secret = System.getenv("AUTH_OTP_PEPPER");
        if (secret == null || secret.length() < 32)
            throw new IllegalStateException("Set AUTH_OTP_PEPPER to a random secret of at least 32 characters");
        this.hmacKey = secret.getBytes(StandardCharsets.UTF_8);
        String from = System.getenv("AUTH_MAIL_FROM");
        if (from == null || !validEmail(from))
            throw new IllegalStateException("Set AUTH_MAIL_FROM to the verified SMTP sender email");
        this.sender = normalizedEmail(from);
        this.dummyPasswordHash = encoder.encode("non-existent-password-not-used");
    }

    @Override public void run(ApplicationArguments args) {
        bootstrap("teacher", "FACULTY", "AUTH_TEACHER_EMAIL", "ADMIN_PASSWORD");
        bootstrap("admin", "ADMIN", "AUTH_ADMIN_EMAIL", "FACEATTEND_ADMIN_PASSWORD");
        db.update("DELETE FROM email_auth_codes WHERE issued_at<?", now() - (7L * 24 * 3600));
    }

    private void bootstrap(String username, String role, String emailVariable, String passwordVariable) {
        String rawEmail = System.getenv(emailVariable);
        String password = System.getenv(passwordVariable);
        if (!validEmail(rawEmail) || password == null || password.length() < 12 ||
            password.getBytes(StandardCharsets.UTF_8).length > 72)
            throw new IllegalStateException("Set " + emailVariable + " and " + passwordVariable + " (12-72 UTF-8 bytes) to initialize " + role);
        if ("admin".equals(username) && password.equals(System.getenv("ADMIN_PASSWORD")))
            throw new IllegalStateException("Teacher and Admin bootstrap passwords must be different");
        String email = normalizedEmail(rawEmail);
        List<Account> existing = byUsernameList(username);
        if (existing.isEmpty()) {
            db.update("INSERT INTO app_users(username,password_hash,account_role,active,student_id,created_at,email,auth_version) " +
                "VALUES(?,?,?,TRUE,NULL,?,?,0)", username, encoder.encode(password), role,
                OffsetDateTime.now().toString(), email);
        } else {
            Account a = existing.get(0);
            if (!role.equals(a.role())) throw new IllegalStateException("Bootstrap username has unexpected role: " + username);
            if (a.email() == null || a.email().isBlank()) {
                db.update("UPDATE app_users SET email=? WHERE username=? AND email IS NULL", email, username);
            } else if (!a.email().equals(email)) {
                LOG.warn("{} already has its own registered email; {} will NOT overwrite it", username, emailVariable);
            }
        }
    }

    private static long now() { return Instant.now().getEpochSecond(); }
    public static String normalizedEmail(String email) {
        if (!validEmail(email)) throw new IllegalArgumentException("Enter a valid registered email address");
        return email.trim().toLowerCase(Locale.ROOT);
    }
    public static boolean validEmail(String email) {
        return email != null && email.length() <= 254 &&
            email.trim().matches("(?i)^[a-z0-9._%+\\-]+@[a-z0-9.-]+\\.[a-z]{2,}$");
    }
    public static void validatePassword(String password) {
        if (password == null || password.length() < 12 ||
            password.getBytes(StandardCharsets.UTF_8).length > 72)
            throw new IllegalArgumentException("Password must be at least 12 characters and at most 72 UTF-8 bytes");
    }
    private List<Account> byUsernameList(String name) {
        return db.query("SELECT username,email,account_role,active,password_hash,auth_version FROM app_users WHERE username=?",
            (rs,i)->new Account(rs.getString("username"),rs.getString("email"),rs.getString("account_role"),
                rs.getBoolean("active"),rs.getString("password_hash"),rs.getInt("auth_version")),name);
    }
    public Account byUsername(String name) {
        var rows=byUsernameList(name);
        return rows.size()==1 ? rows.get(0) : null;
    }
    public Account byEmail(String input) {
        if (!validEmail(input)) return null;
        var rows=db.query("SELECT username,email,account_role,active,password_hash,auth_version FROM app_users WHERE email=?",
            (rs,i)->new Account(rs.getString("username"),rs.getString("email"),rs.getString("account_role"),
                rs.getBoolean("active"),rs.getString("password_hash"),rs.getInt("auth_version")),normalizedEmail(input));
        return rows.size()==1 ? rows.get(0) : null;
    }
    public boolean passwordMatches(Account a, String password) {
        // Always run a BCrypt comparison, even for an unknown or disabled email.
        boolean match = encoder.matches(password == null ? "" : password,
            a == null || !a.active() ? dummyPasswordHash : a.passwordHash());
        return a != null && a.active() && match;
    }

    private String signedCode(String id, String username, String email, String purpose, String code) {
        try {
            Mac mac=Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(hmacKey, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(
                (id+":"+username+":"+email+":"+purpose+":"+code).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new IllegalStateException("OTP signature unavailable",e); }
    }
    /** Limit 1 email / minute, at most 5 codes / hour per account and purpose. */
    public String sendCode(Account account, String purpose) {
        if (account == null || !account.active() || account.email() == null ||
            !("LOGIN".equals(purpose) || "RESET".equals(purpose)))
            throw new IllegalArgumentException("Account not ready for email verification");
        long t=now();
        Long last=db.queryForObject("SELECT MAX(issued_at) FROM email_auth_codes WHERE username=? AND purpose=?",
            Long.class,account.username(),purpose);
        Integer count=db.queryForObject("SELECT COUNT(*) FROM email_auth_codes WHERE username=? AND purpose=? AND issued_at>?",
            Integer.class,account.username(),purpose,t-3600);
        if (last != null && last > t-60) throw new IllegalStateException("Wait 60 seconds before requesting another code");
        if (count != null && count >= 5) throw new IllegalStateException("Hourly OTP limit reached");
        String challenge=UUID.randomUUID().toString();
        String code=String.format(Locale.ROOT,"%06d",RANDOM.nextInt(1_000_000));
        db.update("UPDATE email_auth_codes SET consumed=TRUE WHERE username=? AND purpose=? AND consumed=FALSE",
            account.username(),purpose);
        db.update("INSERT INTO email_auth_codes(id,username,email,purpose,code_hash,issued_at,expires_at,failed_attempts,consumed) " +
                  "VALUES(?,?,?,?,?,?,?,0,FALSE)",challenge,account.username(),account.email(),purpose,
                  signedCode(challenge,account.username(),account.email(),purpose,code),t,t+OTP_LIFETIME);
        SimpleMailMessage message=new SimpleMailMessage();
        message.setFrom(sender);
        message.setTo(account.email());
        message.setSubject("FaceAttend | " + ("LOGIN".equals(purpose)?"Sign-in":"Password reset") + " code");
        message.setText("Your FaceAttend verification code is: "+code+"\n\nIt expires in 5 minutes. " +
            "Do not share it with anyone. If you did not request this code, ignore this email.");
        try { mail.send(message); }
        catch (RuntimeException problem) {
            db.update("UPDATE email_auth_codes SET consumed=TRUE WHERE id=?",challenge);
            LOG.warn("SMTP sending failed for {} challenge (no OTP is logged): {}",purpose,problem.getClass().getSimpleName());
            throw new IllegalStateException("Email could not be delivered; check SMTP credentials and connectivity");
        }
        return challenge;
    }
    @Transactional
    public boolean consume(String challenge, Account current, String purpose, String code) {
        if (challenge == null || current == null || !current.active() || code == null || !code.matches("[0-9]{6}"))
            return false;
        String expected=signedCode(challenge,current.username(),current.email(),purpose,code);
        int success=db.update("UPDATE email_auth_codes SET consumed=TRUE WHERE id=? AND username=? AND email=? AND purpose=? " +
                "AND code_hash=? AND consumed=FALSE AND failed_attempts<5 AND expires_at>?",
                challenge,current.username(),current.email(),purpose,expected,now());
        if (success==1) return true;
        db.update("UPDATE email_auth_codes SET failed_attempts=failed_attempts+1 WHERE id=? AND username=? " +
                  "AND purpose=? AND consumed=FALSE AND failed_attempts<5 AND expires_at>?",
            challenge,current.username(),purpose,now());
        return false;
    }
    @Transactional
    public boolean resetPassword(String challenge, Account account, String otp, String newPassword) {
        validatePassword(newPassword);
        if (!consume(challenge, account, "RESET", otp)) return false;
        return db.update("UPDATE app_users SET password_hash=?,auth_version=auth_version+1 WHERE username=? " +
            "AND email=? AND active=TRUE",encoder.encode(newPassword),account.username(),account.email())==1;
    }

    private static String digestKey(String email, String source) {
        try {
            byte[] hash=MessageDigest.getInstance("SHA-256")
                .digest((email+"|"+source).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
    public boolean tooManyPasswords(String email, String ip) {
        String key=digestKey(email,ip);long t=now();
        List<int[]> rows=db.query("SELECT attempts,lock_until FROM email_auth_failures WHERE throttle_key=?",
            (rs,i)->new int[]{rs.getInt(1),(int)Math.min(Integer.MAX_VALUE,rs.getLong(2)-t)},key);
        return !rows.isEmpty() && rows.get(0)[0]>=5 && rows.get(0)[1]>0;
    }
    public void failedPassword(String email, String ip) {
        String key=digestKey(email,ip);long t=now();
        // Release expired lockouts, including old attempt counts.
        db.update("DELETE FROM email_auth_failures WHERE throttle_key=? AND lock_until<=?",key,t);
        int changed=db.update("UPDATE email_auth_failures SET attempts=attempts+1, " +
            "lock_until=CASE WHEN attempts+1>=5 THEN ? ELSE lock_until END WHERE throttle_key=?",t+900,key);
        if(changed==0) {
            try {db.update("INSERT INTO email_auth_failures(throttle_key,attempts,lock_until) VALUES(?,1,?)",key,t+900);}
            catch (org.springframework.dao.DuplicateKeyException ignored) { /* concurrent attempt */ }
        }
    }
    public void clearFailures(String email, String ip) {
        db.update("DELETE FROM email_auth_failures WHERE throttle_key=?",digestKey(email,ip));
    }
}
