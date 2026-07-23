import java.net.*;
public class TestConnect {
    public static void main(String[] args) throws Exception {
        String[] hosts = {"host.docker.internal", "localhost"};
        int[] ports = {8069, 8070};
        for (String h : hosts) {
            for (int p : ports) {
                try (var s = new Socket()) {
                    s.connect(new InetSocketAddress(h, p), 3000);
                    System.out.println(h + ":" + p + " OK");
                } catch (Exception e) {
                    System.out.println(h + ":" + p + " FAIL: " + e.getMessage());
                }
            }
        }
    }
}
