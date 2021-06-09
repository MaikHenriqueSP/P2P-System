import java.io.IOException;
import java.net.ServerSocket;

public class Servidor {
    
    private final ServerSocket socketReceptor;
    private static final int PORTA_SOCKET_RECEPTOR = 10098;
    
    public Servidor(ServerSocket socketReceptor) throws IOException {
        this.socketReceptor = new ServerSocket(PORTA_SOCKET_RECEPTOR);
    }
}
