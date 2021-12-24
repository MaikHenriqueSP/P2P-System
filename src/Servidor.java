import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.nio.charset.Charset;

/**
 * Coordinates the file sharing among Peers.
 * @author Maik Henrique
 */
public class Servidor implements AutoCloseable {

    private final DatagramSocket socketUDP;
    public static final int PORTA_SOCKET_RECEPTOR = 10098;
    public static final String ENDERECO_SERVIDOR = "localhost";
    public final String ENDERECO_SERVIDOR_FICTICIO;
    private final Map<String, Set<String>> mapaEnderecoPeersParaArquivos;
    private final Map<String, Set<String>> mapaArquivosParaEnderecoPeers;
    private static final int TAMANHO_PACOTES_TRANSFERENCIA = 8 * 1024;
    
    public Servidor(String enderecoServidor) throws IOException {
        this.socketUDP = new DatagramSocket(PORTA_SOCKET_RECEPTOR);
        this.mapaEnderecoPeersParaArquivos = new ConcurrentHashMap<>();
        this.mapaArquivosParaEnderecoPeers = new ConcurrentHashMap<>();
        this.ENDERECO_SERVIDOR_FICTICIO = enderecoServidor;
    }

    /**
     * Server listener, which means it remains blocked waiting on UDP messages.
     * On every message received it starts a thread responsible for dealing with the request, allowing the server to handle multiple requests.
     * 
     * @throws IOException
     * @throws ClassNotFoundException
     */
    public void ligarServidor() throws IOException, ClassNotFoundException {
        while (true) {
            byte[] bytesRecebidos = new byte[TAMANHO_PACOTES_TRANSFERENCIA];
            DatagramPacket pacote = new DatagramPacket(bytesRecebidos, bytesRecebidos.length);
            socketUDP.receive(pacote);
            new RequisicaoClienteThread(pacote).start();
        }
    }

    @Override
    public void close() throws Exception {
        socketUDP.close();        
    }

    /**
     * Handles client requests, handling the UDP packet received and perform concurrent modifications on the server state,
     * adding, removing and updating the Peers information.
     */
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

        /**
         * Treats the client request, performing the redirecting to the proper method handler.
         * 
         * @param mensagem message received from the client
         */
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
                    removerPeer(mensagem);
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

        /**
         * Deals with JOIN requests, adding maps peer -> files and files -> peer
         * 
         * @param mensagem message received from the JOIN request
         */
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

        /**
         * Handles JOIN requests, finding the list of Peers that own the files and sends it to the client
         * 
         * @param mensagem message received on the JOIN request
         */
        private void procurarArquivo(Mensagem mensagem) {
            Map<String, Object> mensagens = mensagem.getMensagens();
                        
            if (mensagens.get("arquivo_requistado") instanceof String ) {
                String arquivoRequisitado = (String) mensagens.get("arquivo_requistado");
                String enderecoEscutaPeer = (String) mensagens.get("endereco");
                
                Set<String> peersPorArquivoRequisitado = mapaArquivosParaEnderecoPeers.getOrDefault(arquivoRequisitado,  new HashSet<>());
                
                System.out.println(String.format("Peer %s solicitou o arquivo %s", enderecoEscutaPeer, arquivoRequisitado));

                Mensagem mensagemResposta = new Mensagem("SEARCH_OK");

                mensagemResposta.adicionarMensagem("lista_peers", peersPorArquivoRequisitado);
                Mensagem.enviarMensagemUDP(mensagemResposta, "localhost", pacoteRecebido.getPort(), socketUDP);
            }
        }

        /**
         * Handles the LEAVE request, removing the mapping from the Peer and of the Files.
         * 
         * @param mensagem message received from the Peer that wants to leave the system
         */
        private void removerPeer(Mensagem mensagem) {
            Map<String, Object> mensagens = mensagem.getMensagens();

            if (mensagens.get("endereco") instanceof String) {
                String endereco = (String) mensagens.get("endereco");
                
                removerArquivosDoPeer(endereco);                
                mapaEnderecoPeersParaArquivos.remove(endereco);

                Mensagem leaveOK = new Mensagem("LEAVE_OK");
                Mensagem.enviarMensagemUDP(leaveOK, "localhost", pacoteRecebido.getPort(), socketUDP);
            }
        }

        private void removerArquivosDoPeer(String endereco) {
            Set<String> arquivosDoPeer = mapaEnderecoPeersParaArquivos.get(endereco);
            if (arquivosDoPeer == null){
                return;
            }

            arquivosDoPeer.parallelStream().forEach(arquivoDoPeer -> {
                Set<String> enderecosPorArquivo = mapaArquivosParaEnderecoPeers.get(arquivoDoPeer);
                enderecosPorArquivo.remove(endereco);

                if (enderecosPorArquivo.size() == 0) {
                    mapaArquivosParaEnderecoPeers.remove(arquivoDoPeer);
                } else {
                    mapaArquivosParaEnderecoPeers.put(arquivoDoPeer, enderecosPorArquivo);
                }
            });            
        }

        /**
         * Handles UPDATE requests, which performs modifications to the files list that the Peer owns.
         * 
         * @param mensagem message received from the Peer
         */
        private void atualizarPeer(Mensagem mensagem) {
            Map<String, Object> mensagens = mensagem.getMensagens();
            String arquivo = (String) mensagens.get("arquivo");
            String endereco = (String) mensagens.get("endereco");

            adicionarMapeamentoPeerParaArquivos(arquivo, endereco);
            adicionarMapeamentoArquivoParaPeers(arquivo, endereco);

            Mensagem updateOK = new Mensagem("UPDATE_OK");
            Mensagem.enviarMensagemUDP(updateOK, "localhost", pacoteRecebido.getPort(), socketUDP);
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


    private static String lerEnderecoServidor() {
        System.out.println("IP do servidor:");        
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, Charset.defaultCharset()));){
            return reader.readLine();
        } catch (IOException e) {
            return "127.0.0.1";
        }
    }

    public static void main(String[] args) {
        String enderecoServidor = lerEnderecoServidor();
        
        try (Servidor servidor = new Servidor(enderecoServidor)){            
            servidor.ligarServidor();
        } catch (Exception e) {
            e.printStackTrace();
        } 
    }



}
