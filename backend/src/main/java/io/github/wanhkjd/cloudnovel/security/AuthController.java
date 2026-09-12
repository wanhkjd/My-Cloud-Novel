package io.github.wanhkjd.cloudnovel.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.Map;

@RestController
public class AuthController {
    public static boolean isOwner(Authentication authentication) {
        return authentication != null && authentication.isAuthenticated()
            && authentication.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
    }
    @GetMapping("/api/auth/csrf") Map<String, String> csrf(CsrfToken token) {
        return Map.of("token", token.getToken(), "headerName", token.getHeaderName());
    }
    @GetMapping("/api/auth/me") Map<String, Object> me(Authentication authentication) {
        boolean owner = isOwner(authentication);
        return Map.of("authenticated", owner, "username", owner ? authentication.getName() : "");
    }
    @GetMapping("/api/health") Map<String, String> health() { return Map.of("status", "ok"); }
}
