package local.attendance;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/** Form-only browser flow; password alone never creates an authenticated session. */
@Controller
public class EmailAuthController {
    private final EmailOtpService accounts;
    private final AccountService users;
    private final SecurityContextRepository contexts;

    public EmailAuthController(EmailOtpService accounts, AccountService users, SecurityContextRepository contexts) {
        this.accounts=accounts;this.users=users;this.contexts=contexts;
    }
    private static String role(String input) {
        if (input==null) return "FACULTY";
        return switch (input.toUpperCase(Locale.ROOT)) {
            case "ADMIN" -> "ADMIN";
            case "STUDENT" -> "STUDENT";
            default -> "FACULTY";
        };
    }
    private static String roleName(String role) {
        return switch (role) {case "ADMIN"->"admin";case "STUDENT"->"student";default->"teacher";};
    }
    private static String destination(String role) {
        return switch (role) {case "ADMIN"->"/admin.html";case "STUDENT"->"/portal.html";default->"/";};
    }
    private static void forget(HttpSession session,String prefix) {
        for (String part : new String[]{"User","Email","Role","Code","Version"}) session.removeAttribute(prefix+part);
    }
    private static String get(HttpSession s,String key) {
        return s == null || !(s.getAttribute(key) instanceof String) ? null : (String)s.getAttribute(key);
    }
    private static String safeHtml(String x) {
        return x==null?"":x.replace("&","&amp;").replace("<","&lt;")
            .replace(">","&gt;").replace("\"","&quot;").replace("'","&#39;");
    }

    @PostMapping("/auth/start")
    String start(@RequestParam(name="username") String email, @RequestParam String password,
                 @RequestParam(required=false) String loginRole, HttpServletRequest req) {
        String requested=role(loginRole);
        String cleaned=EmailOtpService.validEmail(email)?EmailOtpService.normalizedEmail(email):"invalid-email";
        String fail="redirect:/login/"+roleName(requested)+"?error";
        if (accounts.tooManyPasswords(cleaned,req.getRemoteAddr())) return fail;
        EmailOtpService.Account account=accounts.byEmail(cleaned);
        if (!accounts.passwordMatches(account,password) || account==null || !requested.equals(account.role())) {
            accounts.failedPassword(cleaned,req.getRemoteAddr());
            return fail;
        }
        accounts.clearFailures(cleaned,req.getRemoteAddr());
        String challenge;
        try {challenge=accounts.sendCode(account,"LOGIN");}
        catch (RuntimeException issue) {return "redirect:/login/"+roleName(requested)+"?mailerror";}
        HttpSession session=req.getSession(true);
        req.changeSessionId();
        forget(session,"login");
        session.setAttribute("loginUser",account.username());
        session.setAttribute("loginEmail",account.email());
        session.setAttribute("loginRole",account.role());
        session.setAttribute("loginCode",challenge);
        session.setAttribute("loginVersion",account.version());
        return "redirect:/auth/otp";
    }

    @PostMapping("/auth/verify")
    String verify(@RequestParam String code, HttpServletRequest req, HttpServletResponse response) {
        HttpSession session=req.getSession(false);
        String username=get(session,"loginUser"), email=get(session,"loginEmail"),
               r=get(session,"loginRole"), challenge=get(session,"loginCode");
        if(username==null || email==null || r==null || challenge==null) return "redirect:/login?expired";
        EmailOtpService.Account current=accounts.byUsername(username);
        Object oldVersion=session.getAttribute("loginVersion");
        if (current==null || !current.active() || !email.equals(current.email()) ||
            !r.equals(current.role()) || !(oldVersion instanceof Number) ||
            current.version()!=((Number)oldVersion).intValue()) {
            forget(session,"login");return "redirect:/login?expired";
        }
        if (!accounts.consume(challenge,current,"LOGIN",code)) return "redirect:/auth/otp?invalid";
        UserDetails principal=users.loadUserByUsername(username);
        SecurityContext context=SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(principal,null,principal.getAuthorities()));
        SecurityContextHolder.setContext(context);
        req.changeSessionId();
        session.setAttribute("FACEATTEND_AUTH_VERSION",current.version());
        forget(session,"login");
        contexts.saveContext(context,req,response);  // explicit persistence after successful OTP only
        return "redirect:"+destination(r);
    }

    @PostMapping("/auth/resend")
    String resend(HttpServletRequest req) {
        HttpSession session=req.getSession(false);
        String username=get(session,"loginUser"), email=get(session,"loginEmail");
        if(username==null || email==null) return "redirect:/login?expired";
        EmailOtpService.Account current=accounts.byUsername(username);
        if(current==null || !current.active() || !email.equals(current.email())) return "redirect:/login?expired";
        try {
            String code=accounts.sendCode(current,"LOGIN");
            session.setAttribute("loginCode",code);
            session.setAttribute("loginVersion",current.version());
            return "redirect:/auth/otp?resent";
        } catch(RuntimeException e) {return "redirect:/auth/otp?wait";}
    }

    @PostMapping("/auth/forgot")
    String requestReset(@RequestParam String email, @RequestParam(required=false) String loginRole,
                       HttpServletRequest req) {
        String r=role(loginRole);
        String normalized=EmailOtpService.validEmail(email)?EmailOtpService.normalizedEmail(email):"";
        EmailOtpService.Account candidate=accounts.byEmail(normalized);
        String challenge=UUID.randomUUID().toString(); // indistinguishable pending state for unknown email
        if(candidate!=null && candidate.active() && r.equals(candidate.role())) {
            try {challenge=accounts.sendCode(candidate,"RESET");}
            catch(RuntimeException ignored) { /* do not disclose whether a mailbox exists */ }
        }
        HttpSession session=req.getSession(true);
        forget(session,"reset");
        session.setAttribute("resetEmail",normalized);
        session.setAttribute("resetRole",r);
        session.setAttribute("resetCode",challenge);
        return "redirect:/auth/reset?sent";
    }

    @PostMapping("/auth/reset")
    String reset(@RequestParam String code, @RequestParam String newPassword,
                 @RequestParam String confirmPassword, HttpServletRequest req) {
        HttpSession session=req.getSession(false);
        String email=get(session,"resetEmail"),r=get(session,"resetRole"),challenge=get(session,"resetCode");
        if(email==null || r==null || challenge==null) return "redirect:/auth/forgot?expired";
        if(!newPassword.equals(confirmPassword)) return "redirect:/auth/reset?mismatch";
        try {EmailOtpService.validatePassword(newPassword);}
        catch(IllegalArgumentException issue) {return "redirect:/auth/reset?weak";}
        EmailOtpService.Account account=accounts.byEmail(email);
        if(account==null || !account.active() || !r.equals(account.role()) ||
            !accounts.resetPassword(challenge,account,code,newPassword))
            return "redirect:/auth/reset?invalid";
        forget(session,"reset");
        session.invalidate(); // old CSRF token and any old session are discarded
        return "redirect:/login/"+roleName(r)+"?resetSuccess";
    }

    @GetMapping({"/auth/otp","/auth/forgot","/auth/reset"})
    ResponseEntity<String> page(HttpServletRequest req,
          @RequestParam(required=false) String role) throws IOException {
        String path=req.getRequestURI();
        HttpSession session=req.getSession(false);
        if(path.endsWith("/otp") && get(session,"loginUser")==null)
            return ResponseEntity.status(302).header("Location","/login?expired").build();
        if(path.endsWith("/reset") && get(session,"resetCode")==null)
            return ResponseEntity.status(302).header("Location","/auth/forgot?expired").build();
        String name=path.endsWith("/otp")?"otp":path.endsWith("/forgot")?"forgot":"reset";
        String assignedRole=path.endsWith("/otp")?get(session,"loginRole"):
            path.endsWith("/reset")?get(session,"resetRole"):role(role);
        assignedRole=role(assignedRole);
        String label="ADMIN".equals(assignedRole)?"Admin":"STUDENT".equals(assignedRole)?"Student":"Teacher";
        String email=path.endsWith("/otp")?get(session,"loginEmail"):
            path.endsWith("/reset")?get(session,"resetEmail"):"";
        CsrfToken csrf=(CsrfToken)req.getAttribute(CsrfToken.class.getName());
        if(csrf==null) throw new IllegalStateException("CSRF form token unavailable");
        String field="<input type=\"hidden\" name=\""+safeHtml(csrf.getParameterName())+
            "\" value=\""+safeHtml(csrf.getToken())+"\">";
        String html;
        try (var in=new ClassPathResource("static/auth-"+name+".html").getInputStream()) {
            html=new String(in.readAllBytes(),StandardCharsets.UTF_8);
        }
        String masked=email==null||email.isBlank()?"the registered email":
            email.replaceFirst("(^.).*(@.*$)","$1***$2");
        html=html.replace("<!--LOGIN_CSRF_FIELD-->",field)
            .replace("__ROLE__",safeHtml(assignedRole))
            .replace("__ROLE_NAME__",safeHtml(label))
            .replace("__ROLE_ROUTE__",roleName(assignedRole))
            .replace("__MASKED_EMAIL__",safeHtml(masked));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .contentType(MediaType.TEXT_HTML).body(html);
    }
}
