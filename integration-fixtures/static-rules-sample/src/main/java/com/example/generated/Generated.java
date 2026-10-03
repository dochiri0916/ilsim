package com.example.generated;

import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// EXPECT: SEC-001 is reported. A package named "generated" under src/main/java is source, not build output.
@RestController
public class Generated {
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    public Object find(@RequestParam String name) {
        return jdbcTemplate.query("select * from t where name='" + name + "'", (rs, i) -> rs.getString(1));
    }
}
