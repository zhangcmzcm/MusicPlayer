package org.example.music.email.service;

public enum EmailSendRateLimitScope {
    EMAIL_COOLDOWN,
    EMAIL_DAILY_LIMIT,
    IP_RATE_LIMIT
}