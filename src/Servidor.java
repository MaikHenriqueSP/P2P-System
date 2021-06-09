import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.ServerSocket;

public class Servidor {

    private final DatagramSocket socketReceptor;
    private static final int PORTA_SOCKET_RECEPTOR = 10098;
    
    public Servidor() throws IOException {
        this.socketReceptor = new DatagramSocket(PORTA_SOCKET_RECEPTOR);
    }

    public void iniciarServidor() throws IOException, ClassNotFoundException {
        while (true) {
            byte[] receivedBytes = new byte[8 * 1024];
            DatagramPacket packet = new DatagramPacket(receivedBytes, receivedBytes.length);
            socketReceptor.receive(packet);



            ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(packet.getData());
            ObjectInputStream inputObject = new ObjectInputStream(new BufferedInputStream(byteArrayInputStream));
            inputObject.close();
         
            Mensagem mensagem = (Mensagem) inputObject.readObject();
            String requisicao = mensagem.getTitulo();

            switch (requisicao) {
                case "a":
                    System.out.println("a");
                
            }
            
            
        }
    }


    public static void main(String[] args) {
        try {
            Servidor servidor = new Servidor();
            servidor.iniciarServidor();
        } catch (IOException e) {
            e.printStackTrace();
        } catch (ClassNotFoundException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }

    }
}
