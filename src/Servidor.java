import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class Servidor implements AutoCloseable {

    private final DatagramSocket socketUDP;
    public static final int PORTA_SOCKET_RECEPTOR = 10098;
    public static final String ENDERECO_SERVIDOR = "localhost";
    private final Map<String, Set<String>> mapaEnderecoPeersParaArquivos;
    private final Map<String, Set<String>> mapaArquivosParaEnderecoPeers;
    
    public Servidor() throws IOException {
        this.socketUDP = new DatagramSocket(PORTA_SOCKET_RECEPTOR);
        this.mapaEnderecoPeersParaArquivos = new HashMap<>();
        this.mapaArquivosParaEnderecoPeers = new HashMap<>();
    }

    public void ligarServidor() throws IOException, ClassNotFoundException {
        while (true) {
            byte[] bytesRecebidos = new byte[8 * 1024];
            DatagramPacket pacote = new DatagramPacket(bytesRecebidos, bytesRecebidos.length);
            socketUDP.receive(pacote);
            new RequisicaoClienteThread(pacote).start();
        }
    }

    @Override
    public void close() throws Exception {
        socketUDP.close();        
    }

    class RequisicaoClienteThread extends Thread {
        private DatagramPacket pacoteRecebido;

        public RequisicaoClienteThread(DatagramPacket pacoteRecebido) {
            this.pacoteRecebido = pacoteRecebido;
        }

        @Override
        public void run() {
            Mensagem mensageDoCliente = lerMensagemDoCliente();
            tratarRequisicao(mensageDoCliente);
            
        }

        private Mensagem lerMensagemDoCliente() {
            byte[] receivedData = this.pacoteRecebido.getData();
            
            
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

            Mensagem updateOK = new Mensagem("UPDATE_OK");
            Mensagem.enviarMensagemUDP(updateOK, "localhost", pacoteRecebido.getPort(), socketUDP);
        }

        private void adicionarMapeamentoPeerParaArquivos(String arquivo, String endereco) {
            Set<String> arquivosPorPeer = mapaEnderecoPeersParaArquivos.getOrDefault(endereco, new HashSet<>());
            arquivosPorPeer.add(arquivo);
            mapaEnderecoPeersParaArquivos.put(endereco, arquivosPorPeer);
        }

        private void adicionarMapeamentoArquivoParaPeers(String arquivo, String endereco) {
            Set<String> peersPorArquivo = mapaArquivosParaEnderecoPeers.getOrDefault(arquivo, new HashSet<>());
            peersPorArquivo.add(endereco);
            mapaArquivosParaEnderecoPeers.put(arquivo, peersPorArquivo);
        }

        private void removerPeer() {
        }

        private void procurarArquivo(Mensagem mensagem) {
            Map<String, Object> mensagens = mensagem.getMensagens();
                        
            if (mensagens.get("arquivo_requistado") instanceof String ) {
                String arquivoRequisitado = (String) mensagens.get("arquivo_requistado");
                String enderecoEscutaPeer = (String) mensagens.get("endereco");
                Set<String> peersPorArquivoRequisitado = mapaArquivosParaEnderecoPeers.get(arquivoRequisitado);

                System.out.println(String.format("Peer %s solicitou o arquivo %s", enderecoEscutaPeer, arquivoRequisitado));

                Mensagem mensagemResposta = new Mensagem("SEARCH_OK");
                mensagemResposta.adicionarMensagem("lista_peers", peersPorArquivoRequisitado);
                Mensagem.enviarMensagemUDP(mensagemResposta, "localhost", pacoteRecebido.getPort(), socketUDP);
            }

        }
        
        private void adicionarPeer(Mensagem mensagem) {
            Map<String, Object> mensagens = mensagem.getMensagens();
            String identidadePeer = (String) mensagens.get("endereco");
            
            Set<String> videos = getVideosPeer(mensagem);
            
            if ( videos != null && identidadePeer != null ) {
                mapaEnderecoPeersParaArquivos.put(identidadePeer, videos);
                mapearVideoParaPeer(identidadePeer, videos);

                System.out.println(String.format("Peer %s adicionado com os arquivos: \n%s", identidadePeer, videos));

                Mensagem mensagemResposta = new Mensagem("JOIN_OK");
                Mensagem.enviarMensagemUDP(mensagemResposta, "localhost", pacoteRecebido.getPort(), socketUDP);
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
