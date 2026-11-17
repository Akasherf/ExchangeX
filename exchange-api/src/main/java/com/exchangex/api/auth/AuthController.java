package com.exchangex.api.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
@ConditionalOnProperty(name = "exchangex.security.enabled", havingValue = "true", matchIfMissing = true)
public class AuthController {
    private final AuthService auth;
    public AuthController(AuthService auth) { this.auth = auth; }

    public record Credentials(@NotBlank @Email @Size(max=254) String email,
                              @NotBlank @Size(min=12, max=72) String password) {}
    public record RegisteredUser(long userId) {}

    @PostMapping("/register")
    public ResponseEntity<RegisteredUser> register(@Valid @RequestBody Credentials request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new RegisteredUser(auth.register(request.email(), request.password())));
    }

    @PostMapping("/login")
    public AuthService.TokenResponse login(@Valid @RequestBody Credentials request) {
        return auth.login(request.email(), request.password());
    }
}
