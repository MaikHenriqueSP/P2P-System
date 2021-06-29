
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.DatagramSocket;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Classe atua como distribuídora dos arquivos que possuí e também realiza downloads de arquivos
 * conforme requisições do usuário.
 * 
 * @author Maik Henrique
 */
public class Peer {

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

    public Peer() throws IOException {
        this.leitorInputTeclado = new BufferedReader(new InputStreamReader(System.in));
        this.isCompartilhandoArquivos = false;
        configurarPeer();
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
        Mensagem mensagem = new Mensagem("JOIN");

        mensagem.adicionarMensagem("arquivos", this.arquivosDisponiveis);
        mensagem.adicionarMensagem("endereco", this.enderecoEscuta);

        try (DatagramSocket socketUDP = new DatagramSocket()){
            Mensagem.enviarMensagemUDP(mensagem, Servidor.ENDERECO_SERVIDOR, Servidor.PORTA_SOCKET_RECEPTOR, socketUDP);
            Mensagem respostaServidor = Mensagem.receberMensagemUDP(socketUDP);
            if (respostaServidor.getTitulo().equals("JOIN_OK")) {
                System.out.println(String.format("Sou o peer %s com os arquivos: \n%s", this.enderecoEscuta, this.arquivosDisponiveis));
                iniciarServidorOuvinteDeCompartilhamento();
                this.isCompartilhandoArquivos = true;
            }
        } catch (SocketException e) {
            e.printStackTrace();            
        }
    }

    /**
     * Ouvinte de conexões TCP, assim quando uma conexão é estabelecida, delega uma nova thread para lidar com o compartilhamento de arquivos.
     */
    public class ServidorCompartilhamentOuvinte extends Thread {

        @Override
        public void run() {
            while (isCompartilhandoArquivos) {
                try {
                    Socket client = servidor.accept();    
                    new FileServerThread(client).start();
                } catch (SocketException e) {
                    System.out.println("Desligando servidor de compartilhamento de arquivos");
                } catch (IOException  e) {
                    e.printStackTrace();
                    break;
                }
            }
        }       
    }

    /**
     * Inicializa uma Thread que será ouvinte por download de arquivos de forma concorrente.
     */
    private void iniciarServidorOuvinteDeCompartilhamento() {
        new ServidorCompartilhamentOuvinte().start();
    }
   
    /**
     * A cada arquivo que é solicitado pelo usuário, delega uma nova thread responsável por lidar com o download do arquivo. 
     */
    private void iniciarDownloader(String enderecoPeerPrioritario) {
        new FileClientThread(this.ultimoArquivoPesquisado, this.peersComArquivos, enderecoPeerPrioritario).start();            
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
            requisicaoSearchPorPeers(arquivoAlvo, socketUDP);
            Mensagem mensagemPeersComOArquivo = Mensagem.receberMensagemUDP(socketUDP);
    
            return getDadosPeer(mensagemPeersComOArquivo);
        } catch (SocketException e) {
            System.err.println(String.format("Ocorreu um erro ao tentar obter a lista de Peers com %s", arquivoAlvo));
            return null;
        }
    }

    /**
     * Constrói e encaminha uma requisição SEARCH ao servidor via mensagem UDP, solicitando os Peers que possuem o arquivo alvo.
     * @param arquivoAlvo nome do arquivo de vídeo que será requisitado ao servidor
     * @param socketUDP instância de um socket UDP para envio da mensagem
     */
    private void requisicaoSearchPorPeers(String arquivoAlvo, DatagramSocket socketUDP) {
        Mensagem requisicaoPeers = new Mensagem("SEARCH");
        requisicaoPeers.adicionarMensagem("arquivo_requistado", arquivoAlvo);
        requisicaoPeers.adicionarMensagem("endereco", enderecoEscuta);
        Mensagem.enviarMensagemUDP(requisicaoPeers, Servidor.ENDERECO_SERVIDOR, Servidor.PORTA_SOCKET_RECEPTOR, socketUDP);
    }

    private Set<String> getDadosPeer(Mensagem mensagemPeersComOArquivo) {
        Map<String, Object> mensagensArquivosPeer = mensagemPeersComOArquivo.getMensagens();
        String tituloRespostaPeersComOArquivo = mensagemPeersComOArquivo.getTitulo();
        
        if (tituloRespostaPeersComOArquivo.equals("SEARCH_OK") && mensagensArquivosPeer.get("lista_peers") instanceof Set<?>) {
            @SuppressWarnings("unchecked")
            Set<String> conjuntPeersComArquivo = (Set<String>) mensagensArquivosPeer.get("lista_peers");
            return conjuntPeersComArquivo;
        }
        return null;
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
            
            System.out.println(String.format("Arquivo %s baixado com sucesso na pasta %s", this.arquivoAlvo, caminhoPastaDownloadsCliente));
        }
        
        private void pausarExecucao() {
            try {
                TimeUnit.SECONDS.sleep(5);
            } catch (InterruptedException e) {
                System.out.println("Ocorreu um erro durante o intervalo");
                Thread.currentThread().interrupt();
            }
        }

        private void desligarSocket()  {
            if (this.socket != null && this.socket.isConnected()) {
                try {
                    this.socket.close();                
                } catch (IOException e) {                    
                    System.out.println("Ocorreu um erro durante o download, finalizando execução do Downloader.");
                    e.printStackTrace();              
                    Thread.currentThread().interrupt();
                }
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
                    System.out.println("Download negado");
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
        try {
            if (!this.isCompartilhandoArquivos) {
                //@TODO: refactor
                this.servidor = new ServerSocket(porta); 
                File clienteFile = new File(caminhoPastaDownloadsCliente);
                this.arquivosDisponiveis = getListaNomesArquivosDeVideo(clienteFile); 
                
                joinServidor();
            } else {
                System.out.println("Já foi efetuado o JOIN ao servidor anteriormente!");
            }
        } catch (IOException e) {
            System.err.println("Erro na captura, tente novamente");
        }
    }
    
    private void tratarRequisicaoSearch() {
        String arquivoAlvo = getNomeArquivoAlvo();        
        Set<String> peersComArquivo = getPeersComArquivo(arquivoAlvo);
        this.ultimoArquivoPesquisado = arquivoAlvo;

        if (peersComArquivo.size() > 0){
            this.peersComArquivos = peersComArquivo;
            System.out.println(String.format("Peers com arquivo solicitado: \n%s", peersComArquivo));
        } else {
            System.out.println( String.format("Nenhum Peer foi encotrado com o arquivo %s", arquivoAlvo));
        }
    }

    private void tratarRequisicaoDownload() {
        try {            
            if (this.ultimoArquivoPesquisado != null) {
                System.out.println("Digite o ip do Peer:");
                String enderecoIp = this.leitorInputTeclado.readLine();
                System.out.println("Digite a porta do Peer:");
                String porta = this.leitorInputTeclado.readLine();
                String enderecoPeerPrioritario = enderecoIp + ":" + porta;
                
                iniciarDownloader(enderecoPeerPrioritario);
            } else {
                System.out.println("É necessário fazer uma requisição SEARCH antes de efetuar um DOWNLOAD");
            }
        } catch (IOException e) {
            System.err.println("Ocorreu um erro durante a requisição de download, tente novamente.");
        }
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
        try (DatagramSocket socketUDP = new DatagramSocket()){
            Mensagem leave = new Mensagem("LEAVE");
            leave.adicionarMensagem("endereco", this.enderecoEscuta);

            Mensagem.enviarMensagemUDP(leave, Servidor.ENDERECO_SERVIDOR, Servidor.PORTA_SOCKET_RECEPTOR, socketUDP);
            Mensagem respostaServidor = Mensagem.receberMensagemUDP(socketUDP);
            
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

    public static void main(String[] args) {
        try {
            Peer peer = new Peer();
            peer.rodarMenuIterativo();        
        } catch (IOException e) {
            System.err.println("Ocorreu um erro durante a execuçaõ, inicie a execução novamente.");
        }
    }
}