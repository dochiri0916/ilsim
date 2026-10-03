package fixture;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class OrderServiceTest {
    @Test
    public void allowsAnOrderWithSpareStock() {
        assertTrue(new OrderService().canOrder(3, 2));
    }
}
