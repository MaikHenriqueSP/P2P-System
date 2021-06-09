import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
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
            new RequisicaoClienteThread(packet).start();
        }
    }

    public void tratarRequisicao(Mensagem mensagem, DatagramPacket receivedPacket) {
        String requisicao = mensagem.getTitulo();
        InetAddress clienteEndereco = receivedPacket.getAddress();

        int clientePort = receivedPacket.getPort();
        Mensagem mensagemParaCliente = new Mensagem("JOIN_OK");

        switch (requisicao) {
            case "JOIN":
                ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
                try {
                    ObjectOutputStream objectOutputStream = new ObjectOutputStream(new BufferedOutputStream(byteArrayOutputStream));
                    objectOutputStream.writeObject(mensagemParaCliente);
                    objectOutputStream.flush();

                    byte[] byteMessage = byteArrayOutputStream.toByteArray();

                    DatagramPacket packet = new DatagramPacket(byteMessage, byteMessage.length, clienteEndereco, clientePort);
                    DatagramSocket socket = new DatagramSocket();
                    socket.send(packet);
                    socket.close();
                    System.out.println("RESPOSTA ENVIADA AO CLIENTE");
                } catch (IOException e) {
                    e.printStackTrace();
                }


                break;

            default:
                System.err.println("NOT AVAILABLE");
        }

    }


    class RequisicaoClienteThread extends Thread {
        private DatagramPacket receivedPacket;

        public RequisicaoClienteThread(DatagramPacket receivedPacket) {
            this.receivedPacket = receivedPacket;
        }

        @Override
        public void run() {
            Mensagem mensageDoCliente = lerMensagemDoCliente();
            System.out.println(mensageDoCliente);
        }

        private Mensagem lerMensagemDoCliente() {
            byte[] receivedData = this.receivedPacket.getData();
            ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(receivedData);
            
            try {
                ObjectInputStream inputObject = new ObjectInputStream(new BufferedInputStream(byteArrayInputStream));
                return (Mensagem) inputObject.readObject();
            } catch (ClassNotFoundException | IOException e) {
                e.printStackTrace();
            }
            
            return null;
        }

        
    }


    public static void main(String[] args) {
        System.out.println("AA");
        try {
            System.out.println("INICIALIZANDO SERVIDOR");
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
