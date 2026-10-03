package com.example.staticrules;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SqlController {
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    // EXPECT: no SEC-001 (numeric typed input cannot carry an injection payload)
    public Object numeric(@PathVariable Long id) {
        return jdbcTemplate.query("select * from t where id=" + id, (rs, i) -> rs.getString(1));
    }

    // EXPECT: no SEC-001 (parsed into a numeric local)
    public Object parsed(@RequestParam String raw) {
        long id = Long.parseLong(raw);
        return jdbcTemplate.query("select * from t where id=" + id, (rs, i) -> rs.getString(1));
    }

    // EXPECT: SEC-001 (string input concatenated into SQL)
    public Object unsafe(@RequestParam String name) {
        return jdbcTemplate.query("select * from t where name='" + name + "'", (rs, i) -> rs.getString(1));
    }
}
