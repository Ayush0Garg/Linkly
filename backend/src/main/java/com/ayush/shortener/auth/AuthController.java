package com.ayush.shortener.auth;

import com.ayush.shortener.auth.AuthDtos.GoogleLoginRequest;
import com.ayush.shortener.auth.AuthDtos.LoginRequest;
import com.ayush.shortener.auth.AuthDtos.RegisterRequest;
import com.ayush.shortener.auth.AuthDtos.UserResponse;
import com.ayush.shortener.auth.AuthService.Session;
import com.ayush.shortener.common.ApiException;
import com.ayush.shortener.ratelimit.Policies;
import com.ayush.shortener.ratelimit.RateLimited;
import com.ayush.shortener.ratelimit.RateLimiter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@Tag(name = "Auth", description = "Create an account, sign in (password or Google) and manage the session cookies")
class AuthController {

    private final AuthService auth;
    private final AuthCookies cookies;
    private final RateLimiter limiter;

    AuthController(AuthService auth, AuthCookies cookies, RateLimiter limiter) {
        this.auth = auth;
        this.cookies = cookies;
        this.limiter = limiter;
    }

    /** The SPA calls this once on load; reading the token makes Spring set the XSRF-TOKEN cookie. */
    @GetMapping("/csrf")
    @Operation(summary = "Get the CSRF cookie",
            description = "Sets the XSRF-TOKEN cookie. Call once before any POST or DELETE; browsers then echo it in the X-XSRF-TOKEN header.")
    @ApiResponse(responseCode = "204", description = "Cookie set")
    ResponseEntity<Void> csrf(@Parameter(hidden = true) CsrfToken token) {
        token.getToken();
        return ResponseEntity.noContent().build();
    }

    @RateLimited(policy = Policies.AUTH_IP)
    @ApiResponse(responseCode = "429", description = "Too many attempts from this address; see Retry-After")
    @PostMapping("/register")
    @Operation(summary = "Create an account with email and password", description = "Signs you in on success (sets the session cookies).")
    @ApiResponse(responseCode = "201", description = "Account created")
    @ApiResponse(responseCode = "400", description = "Invalid email, or password shorter than 10 characters / longer than 72 bytes")
    @ApiResponse(responseCode = "409", description = "Email already registered")
    ResponseEntity<UserResponse> register(@Valid @RequestBody RegisterRequest request) {
        return signedIn(HttpStatus.CREATED, auth.register(request.email(), request.password(), request.name()));
    }

    @RateLimited(policy = Policies.AUTH_IP)
    @ApiResponse(responseCode = "429", description = "Too many attempts from this address or for this account; see Retry-After")
    @PostMapping("/login")
    @Operation(summary = "Sign in with email and password")
    @ApiResponse(responseCode = "200", description = "Signed in (session cookies set)")
    @ApiResponse(responseCode = "401", description = "Wrong email or password (the same answer for both)")
    ResponseEntity<UserResponse> login(@Valid @RequestBody LoginRequest request) {
        // Per account as well as per address: credential stuffing comes from many addresses at one account.
        limiter.require(Policies.AUTH_EMAIL, request.email().trim().toLowerCase(java.util.Locale.ROOT));
        return signedIn(HttpStatus.OK, auth.login(request.email(), request.password()));
    }

    @RateLimited(policy = Policies.AUTH_IP)
    @ApiResponse(responseCode = "429", description = "Too many attempts from this address; see Retry-After")
    @PostMapping("/google")
    @Operation(summary = "Sign in with a Google ID token",
            description = "Send the ID token from Google's sign-in button. If the email already has a password account, the two are linked and the password is removed.")
    @ApiResponse(responseCode = "200", description = "Signed in")
    @ApiResponse(responseCode = "401", description = "Token invalid, expired, issued for another app, or email not verified")
    @ApiResponse(responseCode = "409", description = "Email already linked to a different Google account")
    @ApiResponse(responseCode = "503", description = "Google sign-in is not configured on this server")
    ResponseEntity<UserResponse> google(@Valid @RequestBody GoogleLoginRequest request) {
        return signedIn(HttpStatus.OK, auth.loginWithGoogle(request.idToken()));
    }

    @PostMapping("/refresh")
    @Operation(summary = "Renew the session", description = "Exchanges the refresh cookie for a new access token and a new refresh token. Reusing an old refresh token ends the whole login.")
    @ApiResponse(responseCode = "200", description = "Session renewed")
    @ApiResponse(responseCode = "401", description = "Missing, expired or already-used refresh token (cookies are cleared)")
    ResponseEntity<UserResponse> refresh(
            @Parameter(hidden = true) @CookieValue(name = AuthCookies.REFRESH, required = false) String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw ApiException.unauthorized("Not signed in");
        }
        try {
            return signedIn(HttpStatus.OK, auth.refresh(refreshToken));
        } catch (ApiException e) {
            // A dead session: also tell the browser to drop its cookies so it stops sending them.
            return ResponseEntity.status(e.status())
                    .header(HttpHeaders.SET_COOKIE, cookies.clearAccess().toString())
                    .header(HttpHeaders.SET_COOKIE, cookies.clearRefresh().toString())
                    .build();
        }
    }

    @PostMapping("/logout")
    @Operation(summary = "Sign out", description = "Revokes this login and clears the cookies.")
    @ApiResponse(responseCode = "204", description = "Signed out")
    ResponseEntity<Void> logout(
            @Parameter(hidden = true) @CookieValue(name = AuthCookies.REFRESH, required = false) String refreshToken) {
        auth.logout(refreshToken);
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, cookies.clearAccess().toString())
                .header(HttpHeaders.SET_COOKIE, cookies.clearRefresh().toString())
                .build();
    }

    @GetMapping("/me")
    @Operation(summary = "Who am I", description = "The signed-in user's profile.")
    @ApiResponse(responseCode = "401", description = "Not signed in")
    UserResponse me(@AuthenticationPrincipal Jwt jwt) {
        return UserResponse.from(auth.currentUser(Long.parseLong(jwt.getSubject())));
    }

    private ResponseEntity<UserResponse> signedIn(HttpStatus status, Session session) {
        return ResponseEntity.status(status)
                .header(HttpHeaders.SET_COOKIE, cookies.access(session.accessToken()).toString())
                .header(HttpHeaders.SET_COOKIE, cookies.refresh(session.refreshToken()).toString())
                .body(UserResponse.from(session.user()));
    }
}
