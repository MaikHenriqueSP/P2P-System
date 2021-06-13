
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

public class Peer {

    private ServerSocket servidor;
    private String enderecoIp;

    // @TODO: remove it and receive the path to the client's file folder
    private String nomeCliente;

    private static final String CAMINHO_BASE_DOWNLOAD = "client/resource/";
    private String caminhoPastaDownloadsCliente;
    private List<String> arquivosDisponiveis;
    public static final int TAMANHO_PACOTES_TRANSFERENCIA = 1024 * 8; //8 kbytes por pacote
    
    private final Thread servidorThread = new Thread(() -> iniciarServidorCompartilhamento());
    private final Thread clienteThread = new Thread(() -> iniciarDownloader());    

    private final BufferedReader leitorInputTeclado;
    private final String enderecoEscuta;    

    public Peer(int porta, String enderecoIp, String nomeCliente) throws IOException {
        this.servidor = new ServerSocket(porta);
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

    private List<String> getListaNomesArquivosDeVideo(File clientFile) {
        return Arrays.stream(clientFile.list()).filter(fileName -> fileName.endsWith(".mp4")).collect(Collectors.toList());
    }
    
    private void criarPastaSeNaoExistir(File clienteFile) {
        clienteFile.mkdirs();
    }
    
    public void iniciarThreadServidorCompartilhamento() {
        servidorThread.start();
    }
    
    public void iniciarThreadDownloader() {
        clienteThread.start();
    }  
    
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

    private void iniciarDownloader() {
        while (true) {
            String arquivoAlvo = getNomeArquivoAlvo();
            if (arquivoAlvo != null) {
                new FileClientThread(arquivoAlvo).start();
            }
        }
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
       
    /*
    * The class aims to enable multiple clients downloading from the same server concurrently on which
    * each thread is going to be responsible to talk to a client "privately"
    */
    class FileServerThread extends Thread {
        private Socket socket;
        private OutputStream outputStream;
        private InputStream inputStream;

        public FileServerThread(Socket socket) throws IOException {
            this.socket = socket;
            this.outputStream = socket.getOutputStream();
            this.inputStream = socket.getInputStream();
        }

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

    /*
    *  Intended to act as a thread which receives a single file per thread from the server, so its main purpose is to allows 
    *  donwloading files from multiple servers concurrently.
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

        @Override
        public void run() {
            Set<String> peersComAOrquivoAlvo = getPeersComArquivo(this.arquivoAlvo);
            System.out.println(String.format("Peers com o arquivo solicitado:\n %s", peersComAOrquivoAlvo));
            estabelecerConexao(peersComAOrquivoAlvo);
            combinarArquivoParaDownload();            
            downloadArquivo();
        }

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

        private void criarArquivoSeNaoExistir(File file) {
            try {
                file.createNewFile();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        private void combinarArquivoParaDownload() {
            Mensagem arquivoRequerido = new Mensagem("DOWNLOAD");
            arquivoRequerido.adicionarMensagem("arquivo_solicitado", this.arquivoAlvo);
            Mensagem.enviarMensagemTCP(outputStream, arquivoRequerido);
        }

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

        private void enviarRequisicaoUpdate() {
            Mensagem update = new Mensagem("UPDATE");
            update.adicionarMensagem("arquivo", arquivoAlvo);
            update.adicionarMensagem("endereco", enderecoEscuta);
            Mensagem.enviarMensagemUDP(update, Servidor.ENDERECO_SERVIDOR, Servidor.PORTA_SOCKET_RECEPTOR, socketUDP);
            
            Mensagem updateOk = Mensagem.receberMensagemUDP(socketUDP);
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

        private Set<String> getPeersComArquivo(String arquivoAlvo) {
            try {
                this.socketUDP = new DatagramSocket();
                requisicaoSearchPorPeers(arquivoAlvo);
                Mensagem mensagemPeersComOArquivo = receberPeersComArquivo();
    
                return getDadosPeer(mensagemPeersComOArquivo);
            } catch (SocketException e) {
                e.printStackTrace();
            }

            return null;

        }

        private Mensagem receberPeersComArquivo() {
            Mensagem mensagemPeersComOArquivo = Mensagem.receberMensagemUDP(socketUDP);
            return mensagemPeersComOArquivo;
        }

        private void requisicaoSearchPorPeers(String arquivoAlvo) {
            Mensagem requisicaoPeers = new Mensagem("SEARCH");
            requisicaoPeers.adicionarMensagem("arquivo_requistado", arquivoAlvo);
            requisicaoPeers.adicionarMensagem("endereco", enderecoEscuta);
            Mensagem.enviarMensagemUDP(requisicaoPeers, Servidor.ENDERECO_SERVIDOR, Servidor.PORTA_SOCKET_RECEPTOR, socketUDP);
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