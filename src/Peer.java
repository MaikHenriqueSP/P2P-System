
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
    private String enderecoOuvinteRequisicoesTCP;
    private final Console leitorInputTeclado;

    private int porta;
    private boolean isCompartilhandoArquivos;

    private String caminhoAbsolutoPastaCliente;
    private List<String> arquivosDisponiveis;
    
    /**
     *  Define o tamanho padrão dos pacotes para transferência, sendo padronizados em 8 kbytes.
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
     * 
     * @throws IOException caso a configuração do Peer não seja bem sucedida, lança uma exceção de modo que deve ser interrompida a criação do Peer.
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
     * Efetua requisição JOIN ao servidor e caso receba JOIN_OK inicializa a thread para a atuação do Peer como um servidor de compartilhamento
     * de arquivos.
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
     * Método para realização do item 5.g, em que se requere o envio contínuo de mensagens enquanto o servidor não responder.
     * Faz 5 tentativas de comunicação com o servidor, enviando mensagens UDP em um intervalo de 1 segundo entre cada uma delas.
     * 
     * @param mensagem mensgem que se deseja encaminhar.
     * @param socketUDP socket com a configuração do destinatário.
     * @return mensagem resposta do servidor ou nulo caso nenhuma resposta seja obtiva.
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
     * Ouvinte de conexões TCP. Assim, quando uma conexão é estabelecida, delega uma nova thread para lidar com o compartilhamento de arquivos.
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
     * Inicializa uma Thread e um ServerSocket, que terá a função de lidar com requisições DOWNLOAD vindas de outros Peers.
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
     * Inicializa uma Thread responsável por executar uma requisição DOWNLOAD a outros Peers em busca de um arquivo alvo.
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
     * Orquestra a requisição SEARCH com o servidor, criando um socket UDP para o envio da mensagem e posteriormente
     * esperando pela resposta do servidor pelo conjunto de Peers com o arquivo.
     * 
     * @TODO: Lida com edge cases, como quando o servidor não responde
     * 
     * @param arquivoAlvo
     * @return conjunto de endereço dos Peers com o arquivo requerido ou nulo caso o servidor não responda.
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
     * Constrói e retorna uma mensagem SEARCH que será usado para solicitar ao SERVIDOR o conjunto de Peers que possuem o arquivo alvo.
     * @param arquivoAlvo nome do arquivo de vídeo que será requisitado ao servidor
     * @param socketUDP instância de um socket UDP para envio da mensagem
     */
    private Mensagem mensagemSearchPorPeers(String arquivoAlvo, DatagramSocket socketUDP) {
        Mensagem requisicaoPeers = new Mensagem("SEARCH");
        requisicaoPeers.adicionarMensagem("arquivo_requistado", arquivoAlvo);
        requisicaoPeers.adicionarMensagem("endereco", enderecoOuvinteRequisicoesTCP);
        return requisicaoPeers;
    }

    /**
     * A partir de uma mensagem constrói e retorna o conjunto de peers com o arquivo alvo.
     * 
     * @param mensagemPeersComOArquivo 
     * @return Conjunto vazio ou não com os endereços dos Peers.
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
     * Classe representante da thread que atua como compartilhadora de arquivos para outros Peers, atuando de forma concorrente.
     * Assim, cada requisição de DOWNLOAD é tratada por esta classe, desde o handshake de qual arquivo será disponibilizado até
     * a execução da transferência de fato.
     */
    class ServidorArquivosThread extends Thread {
        private Socket socket;
        private OutputStream outputStream;
        private InputStream inputStream;

        /**
         * @param socket socket construído após o aceite de conexão pelo método ouvinte.
         * @throws IOException
         */
        public ServidorArquivosThread(Socket socket) throws IOException {
            this.socket = socket;
            this.outputStream = socket.getOutputStream();
            this.inputStream = socket.getInputStream();
        }

        /**
         * Controla o fluxo de execução da thread. Iniciando pela espera de uma mensagem TCP para fazer um 
         * hand-shake de qual arquivo o cliente deseja, após o recebimento da mensagem, decide se nega o DOWNLOAD ou não 
         * de forma aleatória, caso o download seja aceito, inicia a transferência.
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
         * Responsável por ler o arquivo em disco, e efetuar a transferência em pacotes de bytes via canal estabelecido.
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
     * Thread responsável pelo recebimento e escrita em disco de um arquivo vindo de outro Peer.
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
         * Controla o fluxo de execução da thread.
         * Para cada um dos Peers, faz uma requisição de DOWNLOAD, se aceito, da prosseguimento a transferência, senão, refaz a requisição
         * a outro Peer em um intervalo de 5 segundos entre um Peer e outro.
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
         * Faz uma requisição TCP para o Peer cuja a conexão TCP foi estabelecida.
         */
        private void combinarArquivoParaDownload() {
            Mensagem arquivoRequerido = new Mensagem("DOWNLOAD");
            arquivoRequerido.adicionarMensagem("arquivo_solicitado", this.arquivoAlvo);
            Mensagem.enviarMensagemTCP(outputStream, arquivoRequerido);
        }

         /**
          * Orquestra o DOWNLOAD do arquivo, primeiro checa se o download foi negado tentando converter o pacote recebido em uma instância
          * de Mensagem e caso o download não tenha sido negado prossegue com o download.
          *
          * @return se o download foi efetuado ou não.
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
         * Executa a transferência de fato, escrevendo o arquivo em disco.
         * 
         * @param arquivoLeitor stream estabelecida com o Peer provedor do arquivo.
         * @param data pacote de bytes inicial que foi utilizado decidir se o download foi negado ou não.
         * @return true se o download foi bem sucedido e false caso contrário.
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
         *  Decide se o download foi negado, tenta converter o pacote recebido para uma instância de Mensagem e se mal sucedido já
         * retorna falso.
         * 
         * @param data pacote de bytes recebido.
         * @return true se o download foi negado e false caso contrário.
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
         * Faz uma requisição UPDATE ao servidor se estiver atuando como compartilhador de arquivos, visando atualizar os arquivos que possuí e está disposto a compartilhar.
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
     * Implementação genérica do código necessário para fechar qualquer instância que implemente AutoCloseable,
     * fazendo as validações necessárias.
     * 
     * @param <T>
     * @param closeableInstance
     * @return true se a conexão foi fechada com sucesso e false caso contrário.
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
     * Orquestra a requisição JOIN ao servidor.
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
     * Orquestra a requisição SEARCH, requisitando ao servidor o conjunto de Peers com o arquivo alvo,
     * para então atualizar o conjunto a nível do Peer.
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
     * Orquestra requisições DOWNLOAD, fazendo as validações necessárias.
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
     * Orquestra a requisição LEAVE, enviando a mensagem UDP ao servidor e desligando o compartilhamento caso
     * bem sucedido.
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
     * Faz o direcionamento para os métodos adequados de acordo com a requisição do usuário.
     * @param escolhaUsuario qual ação o usuário decidiu tomar.
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