package local.attendance;

import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class IdentityApi {
    @GetMapping("/api/me") public Object me(Authentication auth) {
        String role=auth.getAuthorities().stream().map(g->g.getAuthority())
                .filter(g->g.startsWith("ROLE_")).findFirst().orElse("ROLE_USER").substring(5);
        return Map.of("username",auth.getName(),"role",role,
                "administrator",auth.getAuthorities().stream().anyMatch(g->g.getAuthority().equals("ROLE_ADMIN")));
    }
}
