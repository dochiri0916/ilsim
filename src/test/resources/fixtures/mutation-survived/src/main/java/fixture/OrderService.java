package fixture;

public class OrderService {
    public boolean canOrder(int stock, int quantity) {
        if (stock >= quantity) return true;
        return false;
    }
}
