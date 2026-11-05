package org.example.music.user.domain;
import java.time.Instant;
public record PasswordResetAdmission(Instant admittedAt,Instant expiresAt) {}
