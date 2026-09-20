package com.traceability.demo;

/** Bare record, manual validation in {@link DemoStartService} — matches SignupRequest's convention. */
public record DemoStartRequest(String name, String email, String phone, boolean consent) {}
