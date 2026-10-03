package example;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RequestParam;

class OrderController {
    private final JdbcTemplate jdbcTemplate;

    OrderController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    void handle(@RequestParam String orderId) {
        save(orderId);
        String sql = "select * from orders where id = '" + orderId + "'";
        jdbcTemplate.execute(sql);
    }

    @Transactional
    void save(String orderId) {
    }
}
