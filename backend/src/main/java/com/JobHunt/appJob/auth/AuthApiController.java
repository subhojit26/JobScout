package com.JobHunt.appJob.auth;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Account endpoints: sign up, log in, log out, current user, settings, password change. */
@RestController
@RequestMapping("/api/auth")
@ConditionalOnProperty(name = "jobwatch.dashboard.enabled", havingValue = "true")
public class AuthApiController {

    public record SignUpRequest(String name, String email, String password) {}
    public record LoginRequest(String email, String password) {}
    public record SettingsRequest(String name, Boolean digestEnabled) {}
    public record PasswordRequest(String currentPassword, String newPassword) {}

    private final AuthService auth;
    private final UserRepository users;

    public AuthApiController(AuthService auth, UserRepository users) {
        this.auth = auth;
        this.users = users;
    }

    @PostMapping("/signup")
    public ResponseEntity<AuthService.Session> signUp(@RequestBody SignUpRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(auth.signUp(body.name(), body.email(), body.password()));
    }

    @PostMapping("/login")
    public AuthService.Session login(@RequestBody LoginRequest body) {
        return auth.login(body.email(), body.password());
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        auth.logout(AuthInterceptor.bearerToken(request));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    public User me(@RequestAttribute(AuthInterceptor.USER_ID) long userId) {
        return users.findById(userId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not signed in."));
    }

    @PutMapping("/me")
    public User updateSettings(@RequestAttribute(AuthInterceptor.USER_ID) long userId,
                               @RequestBody SettingsRequest body) {
        User current = me(userId);
        String name = body.name() == null ? current.name() : body.name().trim();
        boolean digest = body.digestEnabled() == null ? current.digestEnabled() : body.digestEnabled();
        users.update(userId, name == null || name.isEmpty() ? null : name, digest);
        return me(userId);
    }

    /** Changes the password; returns a fresh session because all old sessions are signed out. */
    @PostMapping("/password")
    public AuthService.Session changePassword(@RequestAttribute(AuthInterceptor.USER_ID) long userId,
                                              @RequestBody PasswordRequest body) {
        return auth.changePassword(userId, body.currentPassword(), body.newPassword());
    }
}
