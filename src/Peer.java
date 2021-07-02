
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Classe atua como distribuídora dos arquivos que possuí e também realiza downloads de arquivos
 * conforme requisições do usuário.
 * 
 * @author Maik Henrique
 */
public class Peer implements AutoCloseable {

    private ServerSocket servidor;
    private String enderecoIp;
    private int porta;
    private boolean isCompartilhandoArquivos;

    // @TODO: remove it and receive the path to the client's file folder
    private String nomeCliente;

    private static final String CAMINHO_BASE_DOWNLOAD = "client/resource/";
    private String caminhoPastaDownloadsCliente;
    private List<String> arquivosDisponiveis;
    
    /**
     *  Define o tamanho padrão dos pacotes para transferência, sendo padronizados em 8 kbytes.
     */
    public static final int TAMANHO_PACOTES_TRANSFERENCIA = 1024 * 8;
    private Set<String> peersComArquivos;
    private String ultimoArquivoPesquisado;

    private final BufferedReader leitorInputTeclado;
    private String enderecoEscuta;

    private static int TEMPO_ESPERA_RESPOSTA_UDP = (int)TimeUnit.SECONDS.toMillis(2);
    private static int NUMERO_MAXIMO_REQUISICOES = 5;

    public Peer() throws IOException {
        this.leitorInputTeclado = new BufferedReader(new InputStreamReader(System.in));
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
     * 
     * @throws IOException caso a configuração do Peer não seja bem sucedida, lança uma exceção de modo que deve ser interrompida a criação do Peer.
     */
    private void configurarPeer() throws IOException {
        System.out.println("Digite o IP:");
        String enderecoIp = leitorInputTeclado.readLine();
        this.enderecoIp = enderecoIp;

        System.out.println("Digite a porta:");
        int porta = Integer.parseInt(leitorInputTeclado.readLine());
        this.porta = porta;            

        System.out.println("Digite o nome da pasta dos arquivos que deseja compartilhar:");
        String pastaArquivos = leitorInputTeclado.readLine();
        this.nomeCliente = pastaArquivos.toLowerCase();

        this.enderecoEscuta = enderecoIp + ":" + porta;
        this.caminhoPastaDownloadsCliente = CAMINHO_BASE_DOWNLOAD + this.nomeCliente + "/";
        File clienteFile = new File(caminhoPastaDownloadsCliente);
        criarPastaSeNaoExistir(clienteFile);
    }

    /**
     * Efetua requisição JOIN ao servidor e caso receba JOIN_OK inicializa a thread para a atuação do Peer como um servidor de compartilhamento
     * de arquivos.
     */
    private void joinServidor() {
        Mensagem mensagemJoin = new Mensagem("JOIN");
        mensagemJoin.adicionarMensagem("arquivos", this.arquivosDisponiveis);
        mensagemJoin.adicionarMensagem("endereco", this.enderecoEscuta);

        try (DatagramSocket socketUDP = new DatagramSocket()){
            socketUDP.setSoTimeout(Peer.TEMPO_ESPERA_RESPOSTA_UDP);

            Mensagem respostaServidor = controlarRecebimentoMensagemUDP(mensagemJoin, socketUDP);

            if (respostaServidor == null) {
                System.out.println("Não se obteve sucesso durante as requisições ao servidor, tente novamente mais tarde.");
                return;
            }

            if (respostaServidor.getTitulo().equals("JOIN_OK")) {
                System.out.println(String.format("Sou o peer %s com os arquivos: \n%s", this.enderecoEscuta, this.arquivosDisponiveis));
                this.isCompartilhandoArquivos = true;
                iniciarServidorOuvinteDeCompartilhamento();
            } 
        } catch (SocketException e) {
            e.printStackTrace();            
        }
    }

    public Mensagem controlarRecebimentoMensagemUDP(Mensagem mensagem, DatagramSocket socketUDP) {
        Mensagem respostaServidor = null;
        int contadorRequisicoes = 0;

        while (respostaServidor == null && contadorRequisicoes != NUMERO_MAXIMO_REQUISICOES) {
            Mensagem.enviarMensagemUDP(mensagem, Servidor.ENDERECO_SERVIDOR, Servidor.PORTA_SOCKET_RECEPTOR, socketUDP);                
            respostaServidor = Mensagem.receberMensagemUDP(socketUDP);                
            contadorRequisicoes++;
        }

        if (respostaServidor == null && contadorRequisicoes == NUMERO_MAXIMO_REQUISICOES) {
            return null;
        }

        return respostaServidor;
    }
    
    /**
     * Ouvinte de conexões TCP, assim quando uma conexão é estabelecida, delega uma nova thread para lidar com o compartilhamento de arquivos.
     */
    public class ServidorCompartilhamentOuvinte extends Thread {
        @Override
        public void run() {
            try {
                while (isCompartilhandoArquivos) {
                    Socket client = servidor.accept();    
                    new FileServerThread(client).start();
                }
            } catch (IOException e) {                
                System.out.println("Desligando servidor de compartilhamento de arquivos");
            } 
        }            
    }       
    

    /**
     * Inicializa uma Thread que será ouvinte por download de arquivos de forma concorrente.
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
     * A cada arquivo que é solicitado pelo usuário, delega uma nova thread responsável por lidar com o download do arquivo. 
     */
    private void iniciarDownloader(String enderecoPeerPrioritario) {
        if (!enderecoPeerPrioritario.isEmpty()) {
            new FileClientThread(this.ultimoArquivoPesquisado, this.peersComArquivos, enderecoPeerPrioritario).start();            
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
        } catch (IOException e1) {
            System.out.println("Ocorreu um erro durante a leitura, tente novamente!");
            return null;
        }        
    }
       
    /**
     * Orquestra a requisição SEARCH com o servidor, criando um socket UDP para o envio da mensagem e posteriormente
     * esperando pela resposta do servidor pelo conjunto de Peers com o arquivo.
     * 
     * @TODO: Lida com edge cases, como quando o servidor não responde
     * 
     * @param arquivoAlvo
     * @return conjunto de endereço dos Peers com o arquivo requerido.
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
     * Constrói e retorna uma mensagem SEARCH, solicitando os Peers que possuem o arquivo alvo.
     * @param arquivoAlvo nome do arquivo de vídeo que será requisitado ao servidor
     * @param socketUDP instância de um socket UDP para envio da mensagem
     */
    private Mensagem mensagemSearchPorPeers(String arquivoAlvo, DatagramSocket socketUDP) {
        Mensagem requisicaoPeers = new Mensagem("SEARCH");
        requisicaoPeers.adicionarMensagem("arquivo_requistado", arquivoAlvo);
        requisicaoPeers.adicionarMensagem("endereco", enderecoEscuta);
        return requisicaoPeers;
    }

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
     * Classe representante da thread que atua como compartilhadora de arquivos para outros Peers, atuando de forma concorrente.
     */
    class FileServerThread extends Thread {
        private Socket socket;
        private OutputStream outputStream;
        private InputStream inputStream;

        /**
         * @param socket socket construído após o aceite de conexão pelo método ouvinte.
         * @throws IOException
         */
        public FileServerThread(Socket socket) throws IOException {
            this.socket = socket;
            this.outputStream = socket.getOutputStream();
            this.inputStream = socket.getInputStream();
        }

        /**
         * Controla o fluxo de execução da thread. Iniciando pela espera de uma mensagem TCP para fazer um 
         * hand-shake de qual arquivo o cliente deseja, após o recebimento da mensagem, inicia a transferência do
         * arquivo para o cliente via o canal de comunicação estabelecido.
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
                    String caminhoArquivoRequisitado = caminhoPastaDownloadsCliente + nomeArquivo;
                    transferirArquivo(caminhoArquivoRequisitado); 
                }
            } finally {
                Peer.fecharSocket(this.socket);
            }
        }

        /**
         * Responsável por ler o arquivo em disco, e efetua a transferência em pacotes de bytes via canal estabelecido.
         * 
         * @param caminhoArquivoRequisitado
         */
        private void transferirArquivo(String caminhoArquivoRequisitado) {
            try (BufferedOutputStream escritorStream = new BufferedOutputStream(outputStream);
                BufferedInputStream leitorArquivo = new BufferedInputStream(new FileInputStream(caminhoArquivoRequisitado));){
                byte[] packet = new byte[TAMANHO_PACOTES_TRANSFERENCIA];
                
                while (leitorArquivo.read(packet) != -1) {
                    escritorStream.write(packet);
                    escritorStream.flush();
                }   
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
     * Atua como uma thread responsável pelo download de um arquivo.
     */
    class FileClientThread extends Thread {
        private Socket socket;
        private InputStream inputStream;
        private OutputStream outputStream;
        private String arquivoAlvo;
        private List<String> listaPeersComArquivoAlvo;
        private String enderecoPeerPrioritario;
        

        public FileClientThread(String arquivoAlvo, Set<String> peersComAOrquivoAlvo, String enderecoPeerPrioritario) {
            this.arquivoAlvo = arquivoAlvo;
            this.listaPeersComArquivoAlvo = new ArrayList<>(peersComAOrquivoAlvo);

            if (!listaPeersComArquivoAlvo.get(0).equals(enderecoPeerPrioritario)) {
                listaPeersComArquivoAlvo.add(0, enderecoPeerPrioritario);
            }

            this.enderecoPeerPrioritario = enderecoPeerPrioritario;
        }

        /**
         * Controla o fluxo de execução da thread. Inicia requisitando uma lista de Peers com o arquivo de interesse e
         * então estabelece conexão TCP com um dos Peers, combinando então qual o arquivo que será baixado e por fim
         * requisita o download de fato.
         */
        @Override
        public void run() {
            boolean isDownloadBemSucedido = false;
            
            try {
                for (int i = 0; !isDownloadBemSucedido; i++) {
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
                Peer.fecharSocket(this.socket);               
            }
            
            System.out.println(String.format("Arquivo %s baixado com sucesso na pasta %s", this.arquivoAlvo, caminhoPastaDownloadsCliente));
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
         * Estabelece conexão TCP com um Peer e instancia os streams necessários para a transferência de dados.        
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
         * Faz uma requisição UDP para o Peer cuja a conexão TCP foi estabelecida.
         */
        private void combinarArquivoParaDownload() {
            Mensagem arquivoRequerido = new Mensagem("DOWNLOAD");
            arquivoRequerido.adicionarMensagem("arquivo_solicitado", this.arquivoAlvo);
            Mensagem.enviarMensagemTCP(outputStream, arquivoRequerido);
        }

        /**
         * Orquestra o download do arquivo, recebendo os bytes em pacotes e os escrevendo no arquivo.
         */
        private boolean downloadArquivo() {        
            boolean isTransferenciaBemSucedida = false;

            try (BufferedInputStream entradaStream = new BufferedInputStream(inputStream);){                
                byte[] data = new byte[TAMANHO_PACOTES_TRANSFERENCIA];                
                entradaStream.read(data);
                
                if (IsDownloadNegado(data)) {
                    return false;
                }

                isTransferenciaBemSucedida = receberTransferenciaArquivo(entradaStream, data);                
                enviarRequisicaoUpdate();
            } catch (IOException e) {
                System.out.println("Não foi possível efetuar o download, tente novamente");                
            } 

            return isTransferenciaBemSucedida;
        }

        private boolean receberTransferenciaArquivo(BufferedInputStream arquivoLeitor, byte[] data) {
            String caminhoEscritaArquivo = caminhoPastaDownloadsCliente + this.arquivoAlvo;            
            File file = new File(caminhoEscritaArquivo);

            try(BufferedOutputStream escritorStream = new BufferedOutputStream(new FileOutputStream(file))) {

                do {
                    escritorStream.write(data);
                    escritorStream.flush();
                        
                } while (arquivoLeitor.read(data) != -1);

                return true;
            } catch(IOException e) {
                System.out.println("Ocorreu um erro durante o recebimento do arquivo.");
            }
            return false;
        }

        private boolean IsDownloadNegado(byte[] data) {
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
         * Faz requisição UPDATE ao servidor, para atualizar os arquivos que possuí e está disposto a compartilhar.
         */
        private void enviarRequisicaoUpdate() {
            if (isCompartilhandoArquivos) {
                try (DatagramSocket socketUDP = new DatagramSocket()){
                    Mensagem update = new Mensagem("UPDATE");
                    update.adicionarMensagem("arquivo", arquivoAlvo);
                    update.adicionarMensagem("endereco", enderecoEscuta);
                    Mensagem.enviarMensagemUDP(update, Servidor.ENDERECO_SERVIDOR, Servidor.PORTA_SOCKET_RECEPTOR, socketUDP);
                    
                    Mensagem updateOk = Mensagem.receberMensagemUDP(socketUDP);
                    
                } catch (Exception e) {
                    System.err.println("Não foi possível enviar a requisição UPDATE ao servidor!");
                }
            }
        }
    }

    private void tratarRequisicaoJoin() {
        if (!this.isCompartilhandoArquivos) {
            File clienteFile = new File(caminhoPastaDownloadsCliente);
            this.arquivosDisponiveis = getListaNomesArquivosDeVideo(clienteFile);             
            joinServidor();
        } else {
            System.out.println("Já foi efetuado o JOIN ao servidor anteriormente!");
        }
    }
    
    private void tratarRequisicaoSearch() {
        String arquivoAlvo = getNomeArquivoAlvo();        
        this.ultimoArquivoPesquisado = arquivoAlvo;
        Set<String> peersComArquivo = getPeersComArquivo(arquivoAlvo);

        if (peersComArquivo == null) {
            System.out.println("Não se obteve resposta do servidor, tente novamente mais tarde.");
            return;
        }

        if (peersComArquivo.size() > 0){
            this.peersComArquivos = peersComArquivo;
            System.out.println(String.format("Peers com arquivo solicitado: \n%s", peersComArquivo));
        } else {
            System.out.println( String.format("Nenhum Peer foi encotrado com o arquivo %s", arquivoAlvo));
        }
    }

    private void tratarRequisicaoDownload() {
        
        try {
            if (this.ultimoArquivoPesquisado == null) {
                System.out.println("É necessário fazer uma requisição SEARCH antes de efetuar um DOWNLOAD");
                return;
            }

            if (this.peersComArquivos == null || this.peersComArquivos.size() == 0) {
                System.out.println("Não há peers com o arquivo.");
                return;
            }
            
            String enderecoPeerPrioritario = getEnderecoPeerPrioritario();
            iniciarDownloader(enderecoPeerPrioritario);
        } catch (IOException e) {
            System.err.println("Ocorreu um erro durante a requisição de download, tente novamente.");
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
        } while (!this.peersComArquivos.contains(enderecoPeerPrioritario));

        return enderecoPeerPrioritario;
    }

    private void pararCompartilhamentoDeArquivos() {
        try {
            this.servidor.close();
            this.isCompartilhandoArquivos = false;
        } catch (IOException e) {
            System.out.println("Ocorreu um erro durante a tentativa de parada de compartilhamento de arquivos, tente novamente!");
        }
    }

    private void tratarRequisicaoLeave() {
        if (!this.isCompartilhandoArquivos) {
            System.out.println("O compartilhamento de arquivo já está desativado.");
            return;
        }

        try (DatagramSocket socketUDP = new DatagramSocket()) {
            socketUDP.setSoTimeout(Peer.TEMPO_ESPERA_RESPOSTA_UDP);
            
            Mensagem mensagemLeave = new Mensagem("LEAVE");
            mensagemLeave.adicionarMensagem("endereco", this.enderecoEscuta);

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
            } catch (IOException e) {
                System.err.println("Erro na captura da opção, tente novamente");
            }
        }
    }    

    private static void fecharSocket(Socket socket) {
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (IOException e) {
            System.out.println("Ocorreu um erro durante o desligamento do socket.");
        }
    }

    public static void main(String[] args) {
        try (Peer peer = new Peer();) {            
            peer.rodarMenuIterativo();        
        } catch (IOException e) {
            System.err.println("Ocorreu um erro durante a execuçaõ, inicie a execução novamente.");
        }
    }

}