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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class Servidor implements AutoCloseable {

    private final DatagramSocket socketReceptor;
    private static final int PORTA_SOCKET_RECEPTOR = 10098;
    private final Map<String, Set<String>> mapPeerAddressToFiles;
    
    public Servidor() throws IOException {
        this.socketReceptor = new DatagramSocket(PORTA_SOCKET_RECEPTOR);
        this.mapPeerAddressToFiles = new HashMap<>();
    }

    public void ligarServidor() throws IOException, ClassNotFoundException {
        while (true) {
            byte[] receivedBytes = new byte[8 * 1024];
            DatagramPacket packet = new DatagramPacket(receivedBytes, receivedBytes.length);
            socketReceptor.receive(packet);
            new RequisicaoClienteThread(packet).start();
        }
    }

    @Override
    public void close() throws Exception {
        socketReceptor.close();        
    }

    class RequisicaoClienteThread extends Thread {
        private DatagramPacket receivedPacket;

        public RequisicaoClienteThread(DatagramPacket receivedPacket) {
            this.receivedPacket = receivedPacket;
        }

        @Override
        public void run() {
            Mensagem mensageDoCliente = lerMensagemDoCliente();
            tratarRequisicao(mensageDoCliente);
            
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

        public void tratarRequisicao(Mensagem mensagem) {
            String requisicao = mensagem.getTitulo();   
            
            switch (requisicao) {
                case "JOIN":
                    adicionarPeer(mensagem);
                    break;
                case "SEARCH":
                    procurarArquivo(mensagem);                
                    break;
                case "LEAVE":
                    removerPeer();
                    break;
                case "UPDATE":
                    atualizarPeer();
                    break;
                case "ALIVE_OK":                    
                    break;    
                default:
                    System.err.println("NOT AVAILABLE");
            }
        }

        private void atualizarPeer() {
        }

        private void removerPeer() {
        }

        private void procurarArquivo(Mensagem mensagem) {
        }

        
        private void adicionarPeer(Mensagem mensagem) {
            String peerIdentity = getIdentidadePeer(mensagem);
            
            if ( getVideosPeer(mensagem) != null && peerIdentity != null ) {
                Set<String> videos = getVideosPeer(mensagem);
                mapPeerAddressToFiles.put(peerIdentity, videos);
            }
        }

        private String getIdentidadePeer(Mensagem mensagem) {
            Map<String, Object> messagesBody = mensagem.getMensagens();
            
            if (messagesBody.containsKey("address") && messagesBody.containsKey("port")) {
                String address = (String) messagesBody.get("address");
                String port = (String) messagesBody.get("port");
                return address + "_" + port;
            }
            return null;
        }

        private Set<String> getVideosPeer(Mensagem mensagem) {
            Map<String, Object> mensagens = mensagem.getMensagens();

            if ( mensagens.get("arquivos") instanceof List<?>) {
                @SuppressWarnings("unchecked")
                List<String> nomeArquivos = (List<String>) mensagens.get("arquivos");
                Set<String> videos = nomeArquivos.parallelStream().filter(arquivo -> arquivo.endsWith(".mp4")).collect(Collectors.toSet());
                return videos;
            }
            return null;
        }

        private void enviarMensagemAoCliente(Mensagem mensagemParaCliente) {
            InetAddress clienteEndereco = receivedPacket.getAddress();    
            int clientePort = receivedPacket.getPort();

            ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
            try {
                ObjectOutputStream objectOutputStream = new ObjectOutputStream(new BufferedOutputStream(byteArrayOutputStream));
                objectOutputStream.writeObject(mensagemParaCliente);
                objectOutputStream.flush();
   
                byte[] byteMessage = byteArrayOutputStream.toByteArray();
   
                DatagramPacket packet = new DatagramPacket(byteMessage, byteMessage.length, clienteEndereco, clientePort);

                socketReceptor.send(packet);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }       
    }


    public static void main(String[] args) {
        try (Servidor servidor = new Servidor()){            
            System.out.println("INICIALIZANDO SERVIDOR");
            servidor.ligarServidor();
        } catch (Exception e) {
            e.printStackTrace();
        } 
    }


}
