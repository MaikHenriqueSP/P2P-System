
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.DatagramSocket;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import javax.xml.crypto.Data;

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

    // @TODO: remove it and receive the path to the client's file folder
    private String nomeCliente;

    private static final String CAMINHO_BASE_DOWNLOAD = "client/resource/";
    private String caminhoPastaDownloadsCliente;
    private List<String> arquivosDisponiveis;
    
    /**
     *  Define o tamanho padrão dos pacotes para transferência, sendo padronizados em 8 kbytes.
     */
    public static final int TAMANHO_PACOTES_TRANSFERENCIA = 1024 * 8; 
    
    private final Thread servidorThread = new Thread(() -> iniciarServidorCompartilhamento());
    private final Thread clienteThread = new Thread(() -> iniciarDownloader());    

    private final BufferedReader leitorInputTeclado;
    private String enderecoEscuta;    

    /**
     * Cada Peer se torna disponível para compartilhamento de seus arquivos e para efetuar downloads somente
     * quando sua participação no sistema P2P é confirmada pelo Servidor.
     * 
     * @param porta
     * @param enderecoIp
     * @param nomeCliente
     * @throws IOException
     */
    public Peer(int porta, String enderecoIp, String nomeCliente) throws IOException {
        this.servidor = new ServerSocket(porta);
        this.porta = porta;
        this.enderecoIp = enderecoIp;
        this.nomeCliente = nomeCliente.toLowerCase();
        this.caminhoPastaDownloadsCliente = CAMINHO_BASE_DOWNLOAD + this.nomeCliente + "/";

        File clienteFile = new File(caminhoPastaDownloadsCliente);
        criarPastaSeNaoExistir(clienteFile);
        this.arquivosDisponiveis = getListaNomesArquivosDeVideo(clienteFile);
        
        this.leitorInputTeclado = new BufferedReader(new InputStreamReader(System.in));

        this.enderecoEscuta = enderecoIp + ":" + porta;

        joinServidor();
    }


    /**
     * Inicializa uma thread para atuar como escuta por conexões TCP para a trasferencia de arquivos para outros Peers.
     */
    public void iniciarThreadServidorCompartilhamento() {
        servidorThread.start();
    }
    
    /**
     * Inicializa uma thread exclusiva para lidar com requisições de download de arquivos pelo usuário.
     */
    public void iniciarThreadDownloader() {
        clienteThread.start();
    }  

    /**
     * Efetua requisição JOIN ao servidor e aguarda por uma resposta.
     */
    private void joinServidor() {
        Mensagem mensagem = new Mensagem("JOIN");

        mensagem.adicionarMensagem("arquivos", this.arquivosDisponiveis);
        mensagem.adicionarMensagem("endereco", this.enderecoEscuta);

        try (DatagramSocket socketUDP = new DatagramSocket()){
            Mensagem.enviarMensagemUDP(mensagem, Servidor.ENDERECO_SERVIDOR, Servidor.PORTA_SOCKET_RECEPTOR, socketUDP);
            Mensagem respostaServidor = Mensagem.receberMensagemUDP(socketUDP);

            if (respostaServidor.getTitulo().equals("JOIN_OK")) {
                System.out.println(String.format("Sou o peer %s com os arquivos: \n %s", this.enderecoEscuta, this.arquivosDisponiveis));
            }
        } catch (SocketException e) {
            e.printStackTrace();            
        }
    }

    /**
     * Ouvinte de conexões TCP, assim quando uma conexão é estabelecida, delega uma nova thread para lidar com o compartilhamento de arquivos.
     */
    private void iniciarServidorCompartilhamento() {
        while (true) {
            try {
                Socket client = servidor.accept();    
                new FileServerThread(client).start();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
    
    /**
     * A cada arquivo que é solicitado pelo usuário, delega uma nova thread responsável por lidar com o download do arquivo. 
     */
    private void iniciarDownloader() {
        while (true) {
            String arquivoAlvo = getNomeArquivoAlvo();
            if (arquivoAlvo != null) {
                new FileClientThread(arquivoAlvo).start();
            }
        }
    }

    private List<String> getListaNomesArquivosDeVideo(File clientFile) {
        return Arrays.stream(clientFile.list()).filter(fileName -> fileName.endsWith(".mp4")).collect(Collectors.toList());
    }
    
    private void criarPastaSeNaoExistir(File clienteFile) {
        clienteFile.mkdirs();
    }

    private String getNomeArquivoAlvo() {
        System.out.println("Digite o nome do arquivo (com extensão) que desaja baixar:");
                        
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
    }

    /**
     * Atua como uma thread responsável pelo download de um arquivo.
     */
    class FileClientThread extends Thread {
        private Socket socket;
        private InputStream inputStream;
        private OutputStream outputStream;
        private DatagramSocket socketUDP;
        private String arquivoAlvo;

        public FileClientThread(String arquivoAlvo) {
            this.arquivoAlvo = arquivoAlvo;
        }

        /**
         * Controla o fluxo de execução da thread. Inicia requisitando uma lista de Peers com o arquivo de interesse e
         * então estabelece conexão TCP com um dos Peers, combinando então qual o arquivo que será baixado e por fim
         * requisita o download de fato.
         */
        @Override
        public void run() {
            Set<String> peersComAOrquivoAlvo = getPeersComArquivo(this.arquivoAlvo);
            System.out.println(String.format("Peers com o arquivo solicitado:\n %s", peersComAOrquivoAlvo));
            estabelecerConexao(peersComAOrquivoAlvo);
            combinarArquivoParaDownload();            
            downloadArquivo();
        }

        /**
         * Estabelece conexão TCP com um Peer e instancia os streams necessários para a transferência de dados.        
         * 
         * @param peersComAOrquivoAlvo conjunto de endereços com os peers que possuem o arquivo desejado.
         */
        private void estabelecerConexao(Set<String> peersComAOrquivoAlvo) {
            try {
                String[] peerInfo = peersComAOrquivoAlvo.stream().findFirst().get().split(":");
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

        private void criarArquivoSeNaoExistir(File file) {
            try {
                file.createNewFile();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        /**
         * Orquestra o download do arquivo, recebendo os bytes em pacotes e os escrevendo no arquivo.
         */
        private void downloadArquivo() {
            String caminhoEscritaArquivo = caminhoPastaDownloadsCliente + this.arquivoAlvo;            
            File file = new File(caminhoEscritaArquivo);
            criarArquivoSeNaoExistir(file);

            try (BufferedInputStream arquivoLeitor = new BufferedInputStream(inputStream);
                BufferedOutputStream escritorStream = new BufferedOutputStream(new FileOutputStream(file));){

                byte[] data = new byte[TAMANHO_PACOTES_TRANSFERENCIA];
                
                while (arquivoLeitor.read(data) != -1) {
                    escritorStream.write(data);
                    escritorStream.flush();
                } 
                
                System.out.println(String.format("Arquivo %s baixado com sucesso na pasta %s", this.arquivoAlvo, caminhoPastaDownloadsCliente));
                enviarRequisicaoUpdate();
                
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        /**
         * Faz requisição UPDATE ao servidor, para atualizar os arquivos que possuí e está disposto a compartilhar.
         */
        private void enviarRequisicaoUpdate() {
            Mensagem update = new Mensagem("UPDATE");
            update.adicionarMensagem("arquivo", arquivoAlvo);
            update.adicionarMensagem("endereco", enderecoEscuta);
            Mensagem.enviarMensagemUDP(update, Servidor.ENDERECO_SERVIDOR, Servidor.PORTA_SOCKET_RECEPTOR, socketUDP);
            
            Mensagem updateOk = Mensagem.receberMensagemUDP(socketUDP);
        }
    }

    //@TODO: FAZER AS MESMAS TAREFAS DO CONSTRUTOR, pastas etc
    private void tratarRequisicaoJoin() {
        try {
            System.out.println("Digite o IP:");
            String enderecoIp = leitorInputTeclado.readLine();
            this.enderecoIp = enderecoIp;

            System.out.println("Digite a porta:");
            int porta = Integer.parseInt(leitorInputTeclado.readLine());
            this.porta = porta;            

            System.out.println("Digite a pasta do arquivo:");
            String pastaArquivos = leitorInputTeclado.readLine();
            this.nomeCliente = pastaArquivos.toLowerCase();

            //@TODO: refactor
            this.servidor = new ServerSocket(porta); 
            this.caminhoPastaDownloadsCliente = CAMINHO_BASE_DOWNLOAD + this.nomeCliente + "/";
            File clienteFile = new File(caminhoPastaDownloadsCliente);
            criarPastaSeNaoExistir(clienteFile);
            this.arquivosDisponiveis = getListaNomesArquivosDeVideo(clienteFile); 
            this.enderecoEscuta = enderecoIp + ":" + porta;           
            
            joinServidor();
        } catch (IOException e) {
            System.err.println("Erro na captura, tente novamente");
        }
    }
    
    private void tratarRequisicaoSearch() {
        System.out.println("Digite o nome do arquivo que está procurando:");
        try {
            String arquivoAlvo = leitorInputTeclado.readLine();
            while (!arquivoAlvo.endsWith(".mp4")) {
                System.out.println("Só são permitidos arquivos de extensão .mp4");
                arquivoAlvo = leitorInputTeclado.readLine();
            }
            Set<String> peersComArquivo = getPeersComArquivo(arquivoAlvo);
            System.out.println(String.format("Peers com arquivo solicitado: \n%s", peersComArquivo));

        } catch (IOException e) {
            System.err.println("Erro na captura, tente novamente");
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
                break;
            default:
                System.out.println("Opção não disponível");
                break;
        }
    }

    public void rodarMenuIterativo() {
        while (true) {
            System.out.println("Escolha uma das opções");
            System.out.println("JOIN \nSEARCH \nDOWNLOAD");
            try {
                String escolhaUsuario = leitorInputTeclado.readLine();
                direcionarEscolhaUsuario(escolhaUsuario);
            } catch (IOException e) {
                System.err.println("Erro na captura da opção, tente novamente");
            }
        }
    }    

    public static void main(String[] args) throws IOException {
        System.out.println("Digite o número da porta em que serão RECEBIDAS requisições:");

        BufferedReader leitor = new BufferedReader(new InputStreamReader(System.in));
        
        int porta = Integer.parseInt(leitor.readLine());
        
        System.out.println("Digite o endereço de IP em que serão RECEBIDAS requisições");
        String enderecoIP = leitor.readLine();

        System.out.println("Digite o nome do servidor:");
        String nomeCliente = leitor.readLine();



        Peer peer = new Peer(porta, enderecoIP, nomeCliente);
        peer.iniciarThreadServidorCompartilhamento();
        peer.iniciarThreadDownloader();;
    }
}