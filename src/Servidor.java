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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class Servidor implements AutoCloseable {

    private final DatagramSocket socketReceptor;
    public static final int PORTA_SOCKET_RECEPTOR = 10098;
    public static final String ENDERECO_SERVIDOR = "localhost";
    private final Map<String, Set<String>> mapPeerAddressToFiles;
    private final Map<String, Set<String>> mapFilesToPeersAddress;
    
    public Servidor() throws IOException {
        this.socketReceptor = new DatagramSocket(PORTA_SOCKET_RECEPTOR);
        this.mapPeerAddressToFiles = new HashMap<>();
        this.mapFilesToPeersAddress = new HashMap<>();
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
            
            
            try (ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(receivedData);
                ObjectInputStream inputObject = new ObjectInputStream(new BufferedInputStream(byteArrayInputStream));
            ) {                
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
                    System.err.println("NOT AVAILABLE" + requisicao);
            }
        }

        private void atualizarPeer() {
        }

        private void removerPeer() {
        }

        private void procurarArquivo(Mensagem mensagem) {
            Map<String, Object> mensagens = mensagem.getMensagens();
                        
            if (mensagens.get("arquivo_requistado") instanceof String ) {
                String arquivoRequisitado = (String) mensagens.get("arquivo_requistado");
                String enderecoEscutaPeer = (String) mensagens.get("endereco");
                Set<String> peersPorArquivoRequisitado = mapFilesToPeersAddress.get(arquivoRequisitado);

                System.out.println(String.format("Peer %s solicitou arquivo %s", enderecoEscutaPeer, arquivoRequisitado));

                Mensagem mensagemResposta = new Mensagem("SEARCH_OK");
                mensagemResposta.adicionarMensagem("lista_peers", peersPorArquivoRequisitado);
                enviarMensagemAoCliente(mensagemResposta);
            }

        }
        
        private void adicionarPeer(Mensagem mensagem) {
            String peerIdentity = getIdentidadePeer(mensagem);
            Set<String> videos = getVideosPeer(mensagem);
            
            if ( videos != null && peerIdentity != null ) {
                mapPeerAddressToFiles.put(peerIdentity, videos);
                mapearVideoParaPeer(peerIdentity, videos);

                System.out.println(String.format("Peer %s adicionado com arquivos: \n%s", peerIdentity, videos));

                Mensagem mensagemResposta = new Mensagem("JOIN_OK");
                enviarMensagemAoCliente(mensagemResposta);
            }
        }

        private void mapearVideoParaPeer(String peerIdentity, Set<String> videos) {
            videos.parallelStream().forEach(video -> {
                    Set<String> peers = mapFilesToPeersAddress.getOrDefault(video, new HashSet<>());
                    peers.add(peerIdentity);
                    mapFilesToPeersAddress.put(video, peers); 
            });
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

            
            try (ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
                ObjectOutputStream objectOutputStream = new ObjectOutputStream(new BufferedOutputStream(byteArrayOutputStream));
            ) {                
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
            servidor.ligarServidor();
        } catch (Exception e) {
            e.printStackTrace();
        } 
    }


}
