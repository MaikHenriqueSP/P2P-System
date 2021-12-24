
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Act as a downloader and uploader of the files that it allows to be shared.
 * 
 * @author Maik Henrique
 */
public class Peer implements AutoCloseable {

    private ServerSocket servidor;
    private String enderecoOuvinteRequisicoesTCP;
    private final Console leitorInputTeclado;

    private int porta;
    private boolean isCompartilhandoArquivos;

    private String caminhoAbsolutoPastaCliente;
    private List<String> arquivosDisponiveis;
    
    /**
     *  Sets the default package size for transfer, which is by default 8 Kb.
     */
    public static final int TAMANHO_PACOTES_TRANSFERENCIA = 1024 * 8;

    private Set<String> peersComUltimoArquivoPesquisado;
    private String ultimoArquivoPesquisado;

    private static int TEMPO_ESPERA_RESPOSTA_UDP = (int)TimeUnit.SECONDS.toMillis(2);
    private static int NUMERO_MAXIMO_REQUISICOES_AO_SERVIDOR = 5;
    

    public Peer() throws IOException {
        this.leitorInputTeclado = System.console();
        this.isCompartilhandoArquivos = false;
        configurarPeer();
    }

    @Override
    public void close() throws IOException {
        if (this.servidor != null) {
            this.servidor.close();
        }        
    }    

    /**
     * @throws IOException If the setup of the Peer fails, it throws and exception, stopping the Peer registration.
     */
    private void configurarPeer() throws IOException {
        System.out.println("Digite o IP:");
        String enderecoIp = leitorInputTeclado.readLine();

        System.out.println("Digite a porta:");
        int porta = Integer.parseInt(leitorInputTeclado.readLine());
        this.porta = porta;            

        System.out.println("Digite o caminho absoluto da pasta em que deseja baixar arquivos e/ou compartilhar arquivos:");
        this.caminhoAbsolutoPastaCliente = leitorInputTeclado.readLine();
        this.enderecoOuvinteRequisicoesTCP = enderecoIp + ":" + porta;

        File clienteFile = new File(caminhoAbsolutoPastaCliente);
        criarPastaSeNaoExistir(clienteFile);
    }

    /**
     * Sends the JOIN request to the server, if it receives a JOIN_OK back, then it will starts a thread to allow the Peer to act server that
     * shares its files.
     */
    private void joinServidor() {
        Mensagem mensagemJoin = new Mensagem("JOIN");
        mensagemJoin.adicionarMensagem("arquivos", this.arquivosDisponiveis);
        mensagemJoin.adicionarMensagem("endereco", this.enderecoOuvinteRequisicoesTCP);

        try (DatagramSocket socketUDP = new DatagramSocket()){
            socketUDP.setSoTimeout(Peer.TEMPO_ESPERA_RESPOSTA_UDP);

            Mensagem respostaServidor = controlarRecebimentoMensagemUDP(mensagemJoin, socketUDP);

            if (respostaServidor == null) {
                System.out.println("Não se obteve sucesso durante as requisições ao servidor, tente novamente mais tarde.");
                return;
            }

            if (respostaServidor.getTitulo().equals("JOIN_OK")) {
                System.out.println(String.format("Sou o peer %s com os arquivos: \n%s", this.enderecoOuvinteRequisicoesTCP, this.arquivosDisponiveis));
                this.isCompartilhandoArquivos = true;
                iniciarServidorOuvinteDeCompartilhamento();
            } 
        } catch (SocketException e) {
            e.printStackTrace();            
        }
    }

    /**     
     * Implements a polling logic aiming the server, by default it tries 5 times, sending UDP messages with an interval of 1 second
     * between each message.
     * 
     * @param mensagem message to be sent
     * @param socketUDP socket with the destination details configure
     * @return nulls if the contact with the server fails or the response received
     */
    public Mensagem controlarRecebimentoMensagemUDP(Mensagem mensagem, DatagramSocket socketUDP) {
        Mensagem respostaServidor = null;
        int contadorRequisicoes = 0;

        while (respostaServidor == null && contadorRequisicoes != NUMERO_MAXIMO_REQUISICOES_AO_SERVIDOR) {
            Mensagem.enviarMensagemUDP(mensagem, Servidor.ENDERECO_SERVIDOR, Servidor.PORTA_SOCKET_RECEPTOR, socketUDP);                
            respostaServidor = Mensagem.receberMensagemUDP(socketUDP);                
            contadorRequisicoes++;
        }

        if (respostaServidor == null && contadorRequisicoes == NUMERO_MAXIMO_REQUISICOES_AO_SERVIDOR) {
            return null;
        }

        return respostaServidor;
    }
    
    /**
     * TCP connection listener. If a connection is established, it delegates a new thread to deal with the file sharing.     
     */
    public class ServidorCompartilhamentOuvinte extends Thread {
        @Override
        public void run() {
            try {
                while (isCompartilhandoArquivos) {
                    Socket client = servidor.accept();    
                    new ServidorArquivosThread(client).start();
                }
            } catch (IOException e) {                
                System.out.println("Desligando servidor de compartilhamento de arquivos");
            } 
        }            
    }       
    
    /**
     * Starts a thread and a ServerSocket instance, which is going to be responsible for handling DOWNLOAD request comming from other Peers.
     */
    private void iniciarServidorOuvinteDeCompartilhamento() {
        try {
            if (this.servidor == null || this.servidor.isClosed()) {
                this.servidor = new ServerSocket(this.porta);
            }
            new ServidorCompartilhamentOuvinte().start();
        } catch (IOException e) {
            System.err.println("Não foi possível inicializar o servidor ouvinte");
        }
    }
   
    /**
     * Initializes a Thread responsible for executing a DOWNLOAD request to other Peers requesting the target file.
     */
    private void iniciarDownloader(String enderecoPeerPrioritario) {
        if (!enderecoPeerPrioritario.isEmpty()) {
            new ClienteArquivosThread(this.ultimoArquivoPesquisado, this.peersComUltimoArquivoPesquisado, enderecoPeerPrioritario).start();            
        }
     }
    

    private List<String> getListaNomesArquivosDeVideo(File clientFile) {
        return Arrays.stream(clientFile.list()).filter(fileName -> fileName.endsWith(".mp4")).collect(Collectors.toList());
    }
    
    private void criarPastaSeNaoExistir(File clienteFile) {
        clienteFile.mkdirs();
    }

    private String getNomeArquivoAlvo() {
        System.out.println("Digite o nome do arquivo que está procurando:");
                        
        try {
            String nomeArquivo =  this.leitorInputTeclado.readLine();
            
            while (!nomeArquivo.endsWith(".mp4")) {
                System.out.println("Somente são aceitos arquivos de extensão .mp4");
                nomeArquivo = this.leitorInputTeclado.readLine();
            }
            
            return nomeArquivo;
        } catch (IOError e1) {
            System.out.println("Ocorreu um erro durante a leitura, tente novamente!");
            return null;
        }        
    }
       
    /**     
     * Orchestrates the SEARCH request to the server, creating a UDP socket to send the message and the it waits for the server
     * response for the list of Peers that owns the file.
     * @TODO: Deal with edge cases, like when the server does not answer the request
     * 
     * @param arquivoAlvo file that the Peer wants to download
     * @return set of Peers addresses that owns the target file or an empty set if the server does not answer
     */
    private Set<String> getPeersComArquivo(String arquivoAlvo) {
        try (DatagramSocket socketUDP = new DatagramSocket()){
            socketUDP.setSoTimeout(Peer.TEMPO_ESPERA_RESPOSTA_UDP);

            Mensagem mensagemSearch = mensagemSearchPorPeers(arquivoAlvo, socketUDP);
            Mensagem mensagemPeersComOArquivo = controlarRecebimentoMensagemUDP(mensagemSearch, socketUDP);
    
            return mensagemPeersComOArquivo == null ? null : getDadosPeer(mensagemPeersComOArquivo);
        } catch (SocketException e) {
            return new HashSet<>();
        }
    }

    /**
     * @param arquivoAlvo 
     * @param socketUDP 
     */
    private Mensagem mensagemSearchPorPeers(String arquivoAlvo, DatagramSocket socketUDP) {
        Mensagem requisicaoPeers = new Mensagem("SEARCH");
        requisicaoPeers.adicionarMensagem("arquivo_requistado", arquivoAlvo);
        requisicaoPeers.adicionarMensagem("endereco", enderecoOuvinteRequisicoesTCP);
        return requisicaoPeers;
    }

    /**
     * Process the a message that contains the Peers list
     * 
     * @param mensagemPeersComOArquivo 
     * @return Set of filtered adresses of Peers
     */
    private Set<String> getDadosPeer(Mensagem mensagemPeersComOArquivo) {
        Map<String, Object> mensagensArquivosPeer = mensagemPeersComOArquivo.getMensagens();
        String tituloRespostaPeersComOArquivo = mensagemPeersComOArquivo.getTitulo();
        
        if (tituloRespostaPeersComOArquivo.equals("SEARCH_OK") && mensagensArquivosPeer.get("lista_peers") instanceof Set<?>) {
            @SuppressWarnings("unchecked")
            Set<String> conjuntPeersComArquivo = (Set<String>) mensagensArquivosPeer.get("lista_peers");
            return conjuntPeersComArquivo;
        }
        return new HashSet<>();
    }

    /**
     * Represents the thread that acts as a file sharer to other Peers, working concurrently.
     * For each DOWNLOAD request, it performs a handshake on which is the file that is going to be sent and triggers the 
     * transfer as well.
     */
    class ServidorArquivosThread extends Thread {
        private Socket socket;
        private OutputStream outputStream;
        private InputStream inputStream;

        /**
         * @param socket Socket with the connection configured with another Peer
         * @throws IOException
         */
        public ServidorArquivosThread(Socket socket) throws IOException {
            this.socket = socket;
            this.outputStream = socket.getOutputStream();
            this.inputStream = socket.getInputStream();
        }

        /**
         * Handles the execution flow of the thread. It starts by waiting a TCP message and then performs a handshake of the file to
         * be shared, after receiving the message, it randomly decides on sharing the file or not.
         */
        @Override
        public void run() {
            try {
                Mensagem mensagem = Mensagem.receberMensagemTCP(this.inputStream);
                Map<String, Object> mensagens = mensagem.getMensagens();
                String titulo = mensagem.getTitulo();
                
                if (Math.random() > 0.5) {
                    negarDownload();
                    return;
                }
                
                if (titulo.equals("DOWNLOAD") && mensagens.get("arquivo_solicitado") instanceof String) {
                    String nomeArquivo = (String) mensagens.get("arquivo_solicitado");
                    File caminhoArquivoRequisitado = new File(caminhoAbsolutoPastaCliente, nomeArquivo);
                    transferirArquivo(caminhoArquivoRequisitado); 
                }
            } finally {
                Peer.fecharConexao(this.socket);
            }
        }

        /**
         * Reads the file on disk, transfering it in packets through the connection established.
         * 
         * @param caminhoArquivoRequisitado
         */
        private void transferirArquivo(File caminhoArquivoRequisitado) {
            try (BufferedOutputStream escritorStream = new BufferedOutputStream(outputStream);
                BufferedInputStream leitorArquivo = new BufferedInputStream(new FileInputStream(caminhoArquivoRequisitado));){
                byte[] packet = new byte[TAMANHO_PACOTES_TRANSFERENCIA];
                int quantidadeBytesNoBuffer = 0;                

                while ( (quantidadeBytesNoBuffer = leitorArquivo.read(packet)) != -1) {
                    escritorStream.write(packet, 0, quantidadeBytesNoBuffer);
                }

                escritorStream.flush();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        private void negarDownload() {
            Mensagem downloadNegado = new Mensagem("DOWNLOAD_NEGADO");
            Mensagem.enviarMensagemTCP(outputStream, downloadNegado);
        }
    }

    /**
     * Receives the file from the other Peer and writes it on disk.
     */
    class ClienteArquivosThread extends Thread {
        private Socket socket;
        private InputStream inputStream;
        private OutputStream outputStream;
        private String arquivoAlvo;
        private List<String> listaPeersComArquivoAlvo;
        private final int REQUISICOES_POR_PEER = 4;
        private final int TOTAL_REQUISICOES;

        public ClienteArquivosThread(String arquivoAlvo, Set<String> peersComAOrquivoAlvo, String enderecoPeerPrioritario) {
            this.arquivoAlvo = arquivoAlvo;
            this.listaPeersComArquivoAlvo = new ArrayList<>(peersComAOrquivoAlvo);

            if (!listaPeersComArquivoAlvo.get(0).equals(enderecoPeerPrioritario)) {
                listaPeersComArquivoAlvo.add(0, enderecoPeerPrioritario);
            }

            this.TOTAL_REQUISICOES = REQUISICOES_POR_PEER * peersComAOrquivoAlvo.size();
        }

        /**
         * For each Peer, it performs a DOWNLOAD request, if accepted, it proceeds to the transfer, if not, performs the request to
         * another Peer in a interval of 5 seconds.
         */
        @Override
        public void run() {
            boolean isDownloadBemSucedido = false;
            
            try {
                for (int i = 0, totalRequisicoes = 0; !isDownloadBemSucedido && totalRequisicoes < TOTAL_REQUISICOES ; i++, totalRequisicoes++) {
                    i = i % this.listaPeersComArquivoAlvo.size();
                    
                    String enderecoPeerAlvo = listaPeersComArquivoAlvo.get(i);
                    System.out.println(String.format("Pedindo agora para o peer %s.", enderecoPeerAlvo));

                    estabelecerConexao(enderecoPeerAlvo);
                    combinarArquivoParaDownload();            
                    isDownloadBemSucedido = downloadArquivo();

                    if(!isDownloadBemSucedido) {
                        desligarSocket();
                        pausarExecucao();
                            
                        System.out.print(String.format("Peer %s negou o download. ", enderecoPeerAlvo));
                    }            
                }   
            } catch (InterruptedException | IOException e) {
                System.out.println("Ocorreu um erro durante o download, finalizando execução do Downloader.");
            } finally {
                Peer.fecharConexao(this.socket);               
            }
            
            if (isDownloadBemSucedido) {
                System.out.println(String.format("Arquivo %s baixado com sucesso na pasta %s", this.arquivoAlvo, caminhoAbsolutoPastaCliente));
            } else {
                System.out.println("\nO download falhou, tente novamente mais tarde.");
            }
        }
        
        private void pausarExecucao() throws InterruptedException {
            TimeUnit.SECONDS.sleep(5);
        }

        private void desligarSocket() throws IOException  {
            if (this.socket != null && this.socket.isConnected()) {
                this.socket.close();                
            }
        }

        /**
         * Establishes a TCP connection with another Peer and creates a stream for transfering the file.
         */
        private void estabelecerConexao(String enderecoPeer) {
            try {
                String[] peerInfo = enderecoPeer.split(":");
                int porta = Integer.parseInt(peerInfo[1]);

                this.socket = new Socket("localhost", porta);
                this.inputStream = socket.getInputStream();
                this.outputStream = socket.getOutputStream();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        /**
         * Performs a DOWNLOAD request to another Peer.         
         */
        private void combinarArquivoParaDownload() {
            Mensagem arquivoRequerido = new Mensagem("DOWNLOAD");
            arquivoRequerido.adicionarMensagem("arquivo_solicitado", this.arquivoAlvo);
            Mensagem.enviarMensagemTCP(outputStream, arquivoRequerido);
        }

         /**
          * Orchestrates the file download.
          *
          * @return a boolean stating if the download was successful or not
          */
        private boolean downloadArquivo() {        
            boolean isTransferenciaBemSucedida = false;

            try (BufferedInputStream entradaStream = new BufferedInputStream(inputStream);){                
                byte[] data = new byte[TAMANHO_PACOTES_TRANSFERENCIA];                
                int quantidadeBytesLido = entradaStream.read(data);
                
                if (isDownloadNegado(data)) {
                    return false;
                }

                isTransferenciaBemSucedida = receberTransferenciaArquivo(entradaStream, data, quantidadeBytesLido);                
                enviarRequisicaoUpdate();
            } catch (IOException e) {
                System.out.println("Não foi possível efetuar o download, tente novamente");                
            } 

            return isTransferenciaBemSucedida;
        }

        /**
         * Executes the file transfer, writing it on disk.
         * 
         * @param arquivoLeitor stream established with the Peer
         * @param data Initial data packet received
         * @return true if the download was successful, false otherwise
         */
        private boolean receberTransferenciaArquivo(BufferedInputStream arquivoLeitor, byte[] data, int quantidadeBytesLido) {
            File file = new File(caminhoAbsolutoPastaCliente,  this.arquivoAlvo);

            try(BufferedOutputStream escritorStream = new BufferedOutputStream(new FileOutputStream(file))) {

                do {
                    escritorStream.write(data, 0, quantidadeBytesLido);
                    
                } while ( (quantidadeBytesLido = arquivoLeitor.read(data)) != -1);
                escritorStream.flush();
                
                return true;
            } catch(IOException e) {
                System.out.println("Ocorreu um erro durante o recebimento do arquivo.");
            }
            return false;
        }

        /**
         *  Decides if the download request was reject or not
         * 
         * @param data packet received from the other peer
         * @return true if the download request was reject, false otherwise
         */
        private boolean isDownloadNegado(byte[] data) {
            if (data.length == 0) {
                return true;
            }

            Mensagem mensagem;

            try {
                mensagem = Mensagem.deserializarBytes(data);
            } catch (Exception e) {
                return false;
            }

            return mensagem.getTitulo().equals("DOWNLOAD_NEGADO");
        }

        /**
         * Performs an UPDATE request to the server in order to update the information that server holds about the file that the Peer has.
         */
        private void enviarRequisicaoUpdate() {
            if (isCompartilhandoArquivos) {
                try (DatagramSocket socketUDP = new DatagramSocket()){
                    socketUDP.setSoTimeout(Peer.TEMPO_ESPERA_RESPOSTA_UDP);
                    Mensagem update = new Mensagem("UPDATE");
                    update.adicionarMensagem("arquivo", arquivoAlvo);
                    update.adicionarMensagem("endereco", enderecoOuvinteRequisicoesTCP);                    
                    Mensagem updateOk = controlarRecebimentoMensagemUDP(update, socketUDP);
                    if (updateOk == null) {
                        System.out.println("Não foi recebida resposta do servidor em relação a requisição UPDATE.");
                    }                    
                } catch (Exception e) {
                    System.err.println("Não foi possível enviar a requisição UPDATE ao servidor!");
                }
            }
        }
    }

    /**
     * Generic implementation to close any AutoCloseable instance
     * 
     * @param <T>
     * @param closeableInstance
     * @return true if the connection was successfully close, false otherwise
     */
    private static <T extends AutoCloseable> boolean  fecharConexao(T closeableInstance) {
        try {
            if (closeableInstance != null) {
                closeableInstance.close();
            }
            return true;
        } catch (Exception e) {
            return false;
        } 
    }

    private String getEnderecoPeerPrioritario() throws IOException {
        String enderecoPeerPrioritario = "";
        
        do {
            System.out.println("Entre o endereço de um peer que possui o arquivo alvo e que está na lista encontrada durante o SEARCH");
            
            System.out.println("Digite o ip do Peer:");
            String enderecoIp = this.leitorInputTeclado.readLine();
            
            System.out.println("Digite a porta do Peer:");
            String porta = this.leitorInputTeclado.readLine();

            enderecoPeerPrioritario = enderecoIp + ":" + porta;
        } while (!this.peersComUltimoArquivoPesquisado.contains(enderecoPeerPrioritario));

        return enderecoPeerPrioritario;
    }

    private void pararCompartilhamentoDeArquivos() {
        boolean isConexaoFechada = fecharConexao(this.servidor);
        if (isConexaoFechada) {
            this.isCompartilhandoArquivos = false;
        }
    }

    /**
     * Orchestrates the JOIN request to the server.
     */
    private void tratarRequisicaoJoin() {
        if (!this.isCompartilhandoArquivos) {
            File clienteFile = new File(caminhoAbsolutoPastaCliente);
            this.arquivosDisponiveis = getListaNomesArquivosDeVideo(clienteFile);             
            joinServidor();
        } else {
            System.out.println("Já foi efetuado o JOIN ao servidor anteriormente!");
        }
    }
    
    /**
     * Orchestrates the SEARCH request, asking the server the list of Peers that are available with the target file.
     */
    private void tratarRequisicaoSearch() {
        String arquivoAlvo = getNomeArquivoAlvo();        
        this.ultimoArquivoPesquisado = arquivoAlvo;
        Set<String> peersComArquivo = getPeersComArquivo(arquivoAlvo);

        if (peersComArquivo == null) {
            System.out.println("Não se obteve resposta do servidor, tente novamente mais tarde.");
            return;
        }

        if (peersComArquivo.size() > 0){
            this.peersComUltimoArquivoPesquisado = peersComArquivo;
            System.out.println(String.format("Peers com arquivo solicitado: \n%s", peersComArquivo));
        } else {
            System.out.println( String.format("Nenhum Peer foi encotrado com o arquivo %s", arquivoAlvo));
        }
    }

    /**
     * Orchestrates DOWNLOAD requests, performing the required validations.
     */
    private void tratarRequisicaoDownload() {        
        try {
            if (this.ultimoArquivoPesquisado == null) {
                System.out.println("É necessário fazer uma requisição SEARCH antes de efetuar um DOWNLOAD");
                return;
            }

            if (this.peersComUltimoArquivoPesquisado == null || this.peersComUltimoArquivoPesquisado.size() == 0) {
                System.out.println("Não há peers com o arquivo.");
                return;
            }
            
            String enderecoPeerPrioritario = getEnderecoPeerPrioritario();
            iniciarDownloader(enderecoPeerPrioritario);
        } catch (IOException e) {
            System.err.println("Ocorreu um erro durante a requisição de download, tente novamente.");
        }
    }

    /**
     * Orchestrates the LEAVE request, sending UDP messages to the server and turning off the file sharing mechanism.
     */
    private void tratarRequisicaoLeave() {
        if (!this.isCompartilhandoArquivos) {
            System.out.println("O compartilhamento de arquivo já está desativado.");
            return;
        }

        try (DatagramSocket socketUDP = new DatagramSocket()) {
            socketUDP.setSoTimeout(Peer.TEMPO_ESPERA_RESPOSTA_UDP);
            
            Mensagem mensagemLeave = new Mensagem("LEAVE");
            mensagemLeave.adicionarMensagem("endereco", this.enderecoOuvinteRequisicoesTCP);

            Mensagem respostaServidor = controlarRecebimentoMensagemUDP(mensagemLeave, socketUDP);
            
            if (respostaServidor == null) {
                System.out.println("Não foi obtida resposta do servidor, tente novamente mais tarde.");
                return;
            }
            
            if (respostaServidor.getTitulo().equals("LEAVE_OK")) {                       
                pararCompartilhamentoDeArquivos();
            }
        } catch (SocketException e) {
            System.err.println("Não foi possível executar a requisição LEAVE, tente novamente.");            
        }
    }

    /**
     * Redirects the user requests to the proper handlers.
     * @param escolhaUsuario user input
     */
    private void direcionarEscolhaUsuario(String escolhaUsuario) {
        switch (escolhaUsuario) {
            case "JOIN":
                tratarRequisicaoJoin();
                break;
            case "SEARCH":
                tratarRequisicaoSearch();
                break;
            case "DOWNLOAD":
                tratarRequisicaoDownload();
                break;
            case "LEAVE":
                tratarRequisicaoLeave();
                break;
            default:
                System.out.println("Opção não disponível");
                break;
        }
    }

    public void rodarMenuIterativo() {
        while (true) {
            System.out.println("Escolha uma das opções:");
            String opcaoServidor = this.isCompartilhandoArquivos ? "LEAVE" : "JOIN" ;
            System.out.println(String.format("%s\tSEARCH\tDOWNLOAD", opcaoServidor));

            try {
                String escolhaUsuario = leitorInputTeclado.readLine();
                direcionarEscolhaUsuario(escolhaUsuario);
            } catch (IOError e) {
                System.err.println("Erro na captura da opção, tente novamente");
            }
        }
    }    

    public static void main(String[] args) {
        try (Peer peer = new Peer();) {            
            peer.rodarMenuIterativo();        
        } catch (IOException e) {
            System.err.println("Ocorreu um erro durante a execução, inicie a execução novamente.");
        }
    }

}