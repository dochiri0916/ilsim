package example;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

class SafeOrderRepository {
    void find(Connection connection, String orderId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "select * from orders where id = ?")) {
            statement.setString(1, orderId);
            statement.executeQuery();
        }
    }
}
