package local.attendance;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

/**
 * Local: Gmail SMTP. Render Free: Brevo HTTPS API (no blocked SMTP ports).
 * Keys are read from environment only; do not commit them to Git.
 */
@Configuration
public class EmailOtpMailConfig {
    @Bean
    JavaMailSender faceAttendMailSender() {
        String brevoKey = System.getenv("BREVO_API_KEY");
        if (brevoKey != null && !brevoKey.isBlank()) {
            String from = System.getenv("AUTH_MAIL_FROM");
            if (from == null || !EmailOtpService.validEmail(from)) {
                throw new IllegalStateException("Set AUTH_MAIL_FROM to a verified Brevo sender address");
            }
            return new BrevoHttpsMailSender(brevoKey.trim(), from.trim());
        }

        String username = System.getenv("SMTP_USER");
        String password = System.getenv("SMTP_APP_PASSWORD");
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            throw new IllegalStateException("Set BREVO_API_KEY for HTTPS email, or SMTP_USER and SMTP_APP_PASSWORD for Gmail SMTP");
        }
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost("smtp.gmail.com");
        sender.setPort(587);
        sender.setUsername(username);
        sender.setPassword(password);
        sender.setDefaultEncoding("UTF-8");
        Properties props = sender.getJavaMailProperties();
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.starttls.enable", "true");
        props.put("mail.smtp.starttls.required", "true");
        props.put("mail.smtp.connectiontimeout", "8000");
        props.put("mail.smtp.timeout", "8000");
        props.put("mail.smtp.writetimeout", "8000");
        return sender;
    }

    private static final class BrevoHttpsMailSender extends JavaMailSenderImpl {
        private static final URI BREVO_URL = URI.create("https://api.brevo.com/v3/smtp/email");
        private final HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(8))
                .build();
        private final ObjectMapper json = new ObjectMapper();
        private final String apiKey;
        private final String verifiedSender;

        private BrevoHttpsMailSender(String apiKey, String verifiedSender) {
            this.apiKey = apiKey;
            this.verifiedSender = verifiedSender;
        }

        @Override
        public void send(SimpleMailMessage message) {
            if (message == null || message.getTo() == null || message.getTo().length == 0) {
                throw new MailSendException("OTP recipient missing");
            }
            List<Map<String, String>> recipients = new ArrayList<>();
            for (String recipient : message.getTo()) {
                if (!EmailOtpService.validEmail(recipient)) {
                    throw new MailSendException("OTP recipient email invalid");
                }
                recipients.add(Map.of("email", recipient));
            }
            try {
                String body = json.writeValueAsString(Map.of(
                        "sender", Map.of("email", verifiedSender, "name", "FaceAttend"),
                        "to", recipients,
                        "subject", message.getSubject() == null ? "FaceAttend verification" : message.getSubject(),
                        "textContent", message.getText() == null ? "" : message.getText()));
                HttpRequest request = HttpRequest.newBuilder(BREVO_URL)
                        .timeout(Duration.ofSeconds(15))
                        .header("api-key", apiKey)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    // Never log API keys, passwords, OTPs or recipient data.
                    throw new MailSendException("Brevo email API failed, HTTP " + response.statusCode());
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new MailSendException("Brevo email API interrupted", ex);
            } catch (IOException ex) {
                throw new MailSendException("Brevo HTTPS email network failure", ex);
            }
        }

        @Override
        public void send(SimpleMailMessage... messages) {
            for (SimpleMailMessage message : messages) send(message);
        }
    }
}
