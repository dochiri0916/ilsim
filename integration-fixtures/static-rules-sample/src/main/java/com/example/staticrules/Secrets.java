package com.example.staticrules;

public class Secrets {
    // EXPECT: SEC-005 (a real secret that merely contains "test" must not be treated as a placeholder)
    private String apiKey = "Xk29test81Qm45zz";
    // EXPECT: no SEC-005 (credential-free URL, name refers to an endpoint)
    private String tokenUrl = "https://auth.internal/token";
    // EXPECT: no SEC-005 (placeholder)
    private String password = "change_me";
    // EXPECT: no SEC-005 (identifier names a request parameter, not a credential)
    private String passwordParam = "password_param_x";
}
