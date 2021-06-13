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
                    atualizarPeer(mensagem);
                    break;
                case "ALIVE_OK":                    
                    break;    
                default:
                    System.err.println("NOT AVAILABLE" + requisicao);
            }
        }

        private void atualizarPeer(Mensagem mensagem) {
            Map<String, Object> mensagens = mensagem.getMensagens();
            String arquivo = (String) mensagens.get("arquivo");
            String endereco = (String) mensagens.get("endereco");

            adicionarMapeamentoPeerParaArquivos(arquivo, endereco);
            adicionarMapeamentoArquivoParaPeers(arquivo, endereco);

            Mensagem updateOk = new Mensagem("UPDATE_OK");
            Mensagem.enviarMensagemUDP(updateOk, "localhost", receivedPacket.getPort(), socketReceptor);
        }

        private void adicionarMapeamentoPeerParaArquivos(String arquivo, String endereco) {
            Set<String> arquivosPorPeer = mapPeerAddressToFiles.getOrDefault(endereco, new HashSet<>());
            arquivosPorPeer.add(arquivo);
            mapPeerAddressToFiles.put(endereco, arquivosPorPeer);
        }

        private void adicionarMapeamentoArquivoParaPeers(String arquivo, String endereco) {
            Set<String> peersPorArquivo = mapFilesToPeersAddress.getOrDefault(arquivo, new HashSet<>());
            peersPorArquivo.add(endereco);
            mapFilesToPeersAddress.put(arquivo, peersPorArquivo);
        }

        private void removerPeer() {
        }

        private void procurarArquivo(Mensagem mensagem) {
            Map<String, Object> mensagens = mensagem.getMensagens();
                        
            if (mensagens.get("arquivo_requistado") instanceof String ) {
                String arquivoRequisitado = (String) mensagens.get("arquivo_requistado");
                String enderecoEscutaPeer = (String) mensagens.get("endereco");
                Set<String> peersPorArquivoRequisitado = mapFilesToPeersAddress.get(arquivoRequisitado);

                System.out.println(String.format("Peer %s solicitou o arquivo %s", enderecoEscutaPeer, arquivoRequisitado));

                Mensagem mensagemResposta = new Mensagem("SEARCH_OK");
                mensagemResposta.adicionarMensagem("lista_peers", peersPorArquivoRequisitado);
                Mensagem.enviarMensagemUDP(mensagemResposta, "localhost", receivedPacket.getPort(), socketReceptor);
            }

        }
        
        private void adicionarPeer(Mensagem mensagem) {
            Map<String, Object> mensagens = mensagem.getMensagens();
            String identidadePeer = (String) mensagens.get("endereco");
            
            Set<String> videos = getVideosPeer(mensagem);
            
            if ( videos != null && identidadePeer != null ) {
                mapPeerAddressToFiles.put(identidadePeer, videos);
                mapearVideoParaPeer(identidadePeer, videos);

                System.out.println(String.format("Peer %s adicionado com os arquivos: \n%s", identidadePeer, videos));

                Mensagem mensagemResposta = new Mensagem("JOIN_OK");
                Mensagem.enviarMensagemUDP(mensagemResposta, "localhost", receivedPacket.getPort(), socketReceptor);
            }
        }

        private void mapearVideoParaPeer(String endereco, Set<String> videos) {
            videos.parallelStream().forEach(video -> {
                    adicionarMapeamentoArquivoParaPeers(video, endereco);
            });
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
    }

    public static void main(String[] args) {
        try (Servidor servidor = new Servidor()){            
            servidor.ligarServidor();
        } catch (Exception e) {
            e.printStackTrace();
        } 
    }


}
