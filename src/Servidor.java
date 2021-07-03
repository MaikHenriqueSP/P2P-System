import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.ObjectInputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.nio.charset.Charset;

/**
 * Classe responsável por coordenar o compartilhamento de arquivos entre Peers
 * 
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
     * Método usado como um listener do servidor, ou seja, em estado block aguardando o recebimento de mensagens via UDP.
     * Assim a cada mensagem recebida inicializa-se uma thread (RequisicaoClienteThread) que será responsável por lidar com a requisição,
     * possibilitando assim que o servidor lide com várias requisições de forma simultânea.
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
     * Classe de suporte, usada para atuar como uma thread lidando com requições do cliente.
     * Assim possui o pacote recebido via UDP e faz modificações concorrentes no estado do servidor,
     * adicionando, removendo e atualizando os peers e arquivos disponíveis.
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
         * Trata a requisição do cliente e fazendo os redirecionamentos aos métodos adequados.
         * 
         * @param mensagem mensagem de requisição recebida do cliente
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
         *  Lida com requisições JOIN, adicionando aos mapas peer -> arquivos e arquivo -> peers.
         * 
         * @param mensagem mensagem recebida na requisição JOIN
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
         * Lida com requisições SEARCH, encontrando a lista de peers que possuem o arquivo e os envia para o cliente
         * 
         * @param mensagem mensagem recebida na requisição JOIN
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
         *  Lida com requisições do tipo LEAVE, removendo o mapeamento do peer para seus arquivos e
         * dos arquivos do peer para seu endereço (mapeamento bi-direcional).
         * 
         * @param mensagem mensagem recebida do pear que deseja se desligar da rede
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
         * Lida com requisições UPDATE, que ocorrem após um Peer finalizar o download de arquivo e tornando-se assim
         * também um compartilhador do arquivo para outros peers.
         * 
         * @param mensagem mensagem recebida na requisição UPDATE
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
        System.out.println("Endereço IP do servidor:");        
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, Charset.forName("UTF-8")));){
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
