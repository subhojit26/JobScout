package com.JobHunt.appJob.auth;

/** A registered account. The password hash never leaves the auth package. */
public record User(long id, String email, String name, boolean digestEnabled, String createdAt) {}
