
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

    private ServerSocket server;
    private String enderecoIp;

    // @TODO: remove it and receive the path to the client's file folder
    private String clientName;

    private static final String BASE_CLIENT_FOLDER_PATH = "client/resource/";
    private String clientResourcesFilePath;
    private List<String> arquivosDisponiveis;
    public static final int FILE_TRANSFER_PACKET_SIZE = 1024 * 8;
    
    private final Thread serverThread = new Thread(() -> runFilesShareServer());
    private final Thread clientConsumerThread = new Thread(() -> runFileClientDownloader());    

    private final BufferedReader userInputReader;
    private final String enderecoEscuta;
    

    public Peer(int port, String enderecoIp, String clientName) throws IOException {
        this.server = new ServerSocket(port);
        this.enderecoIp = enderecoIp;
        this.clientName = clientName.toLowerCase();
        this.clientResourcesFilePath = BASE_CLIENT_FOLDER_PATH + this.clientName + "/";

        File clientFile = new File(clientResourcesFilePath);
        createClientFolderIfNotExists(clientFile);
        this.arquivosDisponiveis = getListaNomeArquivosDeVideo(clientFile);
        
        this.userInputReader = new BufferedReader(new InputStreamReader(System.in));

        this.enderecoEscuta = enderecoIp + ":" + port;

        joinServidor();
    }

    private void joinServidor() {
        Mensagem mensagem = new Mensagem("JOIN");

        mensagem.adicionarMensagem("arquivos", this.arquivosDisponiveis);
        mensagem.adicionarMensagem("endereco", this.enderecoEscuta);

        try (DatagramSocket datagramSocket = new DatagramSocket()){
            Mensagem.enviarMensagemUDP(mensagem, Servidor.ENDERECO_SERVIDOR, Servidor.PORTA_SOCKET_RECEPTOR, datagramSocket);
            Mensagem respostaServidor = Mensagem.receberMensagemUDP(datagramSocket);

            if (respostaServidor.getTitulo().equals("JOIN_OK")) {
                System.out.println(String.format("Sou o peer %s com os arquivos: \n %s", this.enderecoEscuta, this.arquivosDisponiveis));
            }
        } catch (SocketException e) {
            e.printStackTrace();
            
        }
    }

    private List<String> getListaNomeArquivosDeVideo(File clientFile) {
        return Arrays.stream(clientFile.list()).filter(fileName -> fileName.endsWith(".mp4")).collect(Collectors.toList());
    }
    
    private void createClientFolderIfNotExists(File clientFile) {
        clientFile.mkdirs();
    }
    
    public void startServer() {
        serverThread.start();
    }
    
    public void startClientConsumer() {
        clientConsumerThread.start();
    }  
    
    private void runFilesShareServer() {
        while (true) {
            try {
                Socket client = server.accept();    
                new FileServerThread(client).start();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private String getNomeArquivoAlvo() {
        System.out.println("Digite o nome do arquivo (com extensão) que desaja baixar:");
                        
        try {
            String nomeArquivo =  this.userInputReader.readLine();
            
            while (!nomeArquivo.endsWith(".mp4")) {
                System.out.println("Somente são aceitos arquivos de extensão .mp4");
                nomeArquivo = this.userInputReader.readLine();
            }
            
            return nomeArquivo;
        } catch (IOException e1) {
            System.out.println("Ocorreu um erro durante a leitura, tente novamente!");
            return null;
        }        
    }
    
    private void runFileClientDownloader() {
        while (true) {
            String arquivoAlvo = getNomeArquivoAlvo();
            if (arquivoAlvo != null) {
                new FileClientThread(arquivoAlvo).start();
            }
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
                String caminhoArquivoRequisitado = clientResourcesFilePath + nomeArquivo;
                transferirArquivo(caminhoArquivoRequisitado); 
            }
        }

        private void transferirArquivo(String caminhoArquivoRequisitado) {
            try (BufferedOutputStream fileWriter = new BufferedOutputStream(outputStream);
                BufferedInputStream fileReader = new BufferedInputStream(new FileInputStream(caminhoArquivoRequisitado));){
                byte[] packet = new byte[FILE_TRANSFER_PACKET_SIZE];
                while (fileReader.read(packet) != -1) {
                    fileWriter.write(packet);
                    fileWriter.flush();
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
        private DatagramSocket datagramSocket;
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

        private void creatFileIfNotExists(File file) {
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
            String writingFilePath = clientResourcesFilePath + this.arquivoAlvo;            
            File file = new File(writingFilePath);
            creatFileIfNotExists(file);

            try (BufferedInputStream fileReader = new BufferedInputStream(inputStream);
                BufferedOutputStream fileWriter = new BufferedOutputStream(new FileOutputStream(file));){

                byte[] data = new byte[FILE_TRANSFER_PACKET_SIZE];
                
                while (fileReader.read(data) != -1) {
                    fileWriter.write(data);
                    fileWriter.flush();
                } 
                
                System.out.println(String.format("Arquivo %s baixado com sucesso na pasta %s", this.arquivoAlvo, clientResourcesFilePath));
                enviarRequisicaoUpdate();
                
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        private void enviarRequisicaoUpdate() {
            Mensagem update = new Mensagem("UPDATE");
            update.adicionarMensagem("arquivo", arquivoAlvo);
            update.adicionarMensagem("endereco", enderecoEscuta);
            Mensagem.enviarMensagemUDP(update, Servidor.ENDERECO_SERVIDOR, Servidor.PORTA_SOCKET_RECEPTOR, datagramSocket);
            
            Mensagem updateOk = Mensagem.receberMensagemUDP(datagramSocket);
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
                this.datagramSocket = new DatagramSocket();
                requisicaoSearchPorPeers(arquivoAlvo);
                Mensagem mensagemPeersComOArquivo = receberPeersComArquivo();
    
                return getDadosPeer(mensagemPeersComOArquivo);
            } catch (SocketException e) {
                e.printStackTrace();
            }

            return null;

        }

        private Mensagem receberPeersComArquivo() {
            Mensagem mensagemPeersComOArquivo = Mensagem.receberMensagemUDP(datagramSocket);
            return mensagemPeersComOArquivo;
        }

        private void requisicaoSearchPorPeers(String arquivoAlvo) {
            Mensagem requisicaoPeers = new Mensagem("SEARCH");
            requisicaoPeers.adicionarMensagem("arquivo_requistado", arquivoAlvo);
            requisicaoPeers.adicionarMensagem("endereco", enderecoEscuta);
            Mensagem.enviarMensagemUDP(requisicaoPeers, Servidor.ENDERECO_SERVIDOR, Servidor.PORTA_SOCKET_RECEPTOR, datagramSocket);
        }
    }

    public static void main(String[] args) throws IOException {
        System.out.println("Digite o número da porta em que serão RECEBIDAS requisições:");

        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
        
        int port = Integer.parseInt(reader.readLine());
        
        System.out.println("Digite o endereço de IP em que serão RECEBIDAS requisições");
        String ipAddress = reader.readLine();

        System.out.println("Digite o nome do servidor:");
        String clientName = reader.readLine();


        Peer peer = new Peer(port, ipAddress, clientName);
        peer.startServer();
        peer.startClientConsumer();;
    }
}