
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.DatagramSocket;
import java.net.InetAddress;
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
    private String ipAddress;

    // @TODO: remove it and receive the path to the client's file folder
    private String clientName;

    private static final String BASE_CLIENT_FOLDER_PATH = "client/resource/";
    private String clientResourcesFilePath;
    private List<String> filesAvailable;
    public static final int FILE_TRANSFER_PACKET_SIZE = 1024 * 8;
    
    private final Thread serverThread = new Thread(() -> runFilesShareServer());
    private final Thread clientConsumerThread = new Thread(() -> runFileClientDownloader());    

    public Peer(int port, String ipAddress, String clientName) throws IOException {
        this.server = new ServerSocket(port);
        this.ipAddress = ipAddress;
        this.clientName = clientName.toLowerCase();
        this.clientResourcesFilePath = BASE_CLIENT_FOLDER_PATH + this.clientName + "/";

        File clientFile = new File(clientResourcesFilePath);
        createClientFolderIfNotExists(clientFile);
        this.filesAvailable = getListaNomeArquivosDeVideo(clientFile);
        joinServidor();
    }

    private void joinServidor() {
        Mensagem mensagem = new Mensagem("JOIN");

        mensagem.adicionarMensagem("arquivos", filesAvailable);
        mensagem.adicionarMensagem("address", ipAddress);
        mensagem.adicionarMensagem("port", String.valueOf(server.getLocalPort()));

        try (DatagramSocket datagramSocket = new DatagramSocket()){
            Mensagem.enviarMensagemUDP(mensagem, Servidor.ENDERECO_SERVIDOR, Servidor.PORTA_SOCKET_RECEPTOR, datagramSocket);
            Mensagem respostaServidor = Mensagem.receberMensagemUDP(datagramSocket);
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
                System.out.println("-- ESPERANDO POR REQUISIÇÕES DE ARQUIVOS");
                Socket client = server.accept();    
                System.out.println("REQUISIÇÃO DE CONEXÃO RECEBIDA");
                new FileServerThread(client).start();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
    
    private void runFileClientDownloader() {
        //while (true) {
            new FileClientThread().start();
       // }
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
            System.out.println("-- AGUARDANDO POR QUAL ARQUIVO SERÁ REQUERIDO POR: " + this.socket.getPort());

            Mensagem mensagem = Mensagem.receberMensagemTCP(this.inputStream);
            System.out.println("ARQUIVO REQUERIDO:" + mensagem);

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
                System.out.println("------ TRANSFERÊNCIA DO ARQUIVO FINALIZADO COM SUCESSO");
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

        public void estabelecerConexao(String host, int port) {
            try {
                this.socket = new Socket("localhost", port);
                this.inputStream = socket.getInputStream();
                this.outputStream = socket.getOutputStream();
                this.datagramSocket = new DatagramSocket();
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

        @Override
        public void run() {
            String nomeArquivo = getArquivoNomeArquivoRequerido();
            Mensagem mensagemPeersComOArquivo = getPeersComArquivo(nomeArquivo);
            
            String[] peerInfo = getDadosPeer(mensagemPeersComOArquivo);
            String peerEndereco = peerInfo[0];
            int peerPorta = Integer.parseInt(peerInfo[1]);

            estabelecerConexao(peerEndereco, peerPorta);
            combinarArquivoParaDownload(nomeArquivo);            
            downloadArquivo(nomeArquivo);
            System.out.println("FIM DOWNLOAD");
        }

        private void combinarArquivoParaDownload(String nomArquivo) {
            System.out.println("HANDSHAKE - ARQUIVO DE TRANSFERENCIA");
            Mensagem arquivoRequerido = new Mensagem("DOWNLOAD");
            arquivoRequerido.adicionarMensagem("arquivo_solicitado", nomArquivo);
            Mensagem.enviarMensagemTCP(outputStream, arquivoRequerido);
            System.out.println("MENSAGEM ENVIADA COM SUCESSO!");
        }

        private void downloadArquivo(String nomArquivo) {
            String writingFilePath = clientResourcesFilePath + nomArquivo;            
            File file = new File(writingFilePath);

            creatFileIfNotExists(file);            

            System.out.println("-- COMEÇANDO TRANSFERÊNCIA");
            Long bytesTransfered = 0L;

            try (BufferedInputStream fileReader = new BufferedInputStream(inputStream);
                BufferedOutputStream fileWriter = new BufferedOutputStream(new FileOutputStream(file));){

                byte[] data = new byte[FILE_TRANSFER_PACKET_SIZE];
                
                while (fileReader.read(data) != -1) {
                    fileWriter.write(data);
                    fileWriter.flush();
                    bytesTransfered += FILE_TRANSFER_PACKET_SIZE;
                    System.out.println("+ KBYTES RECEIVED: " + bytesTransfered);
                } 
                
                System.out.println("-- DOWNLOAD FINALIZADO COM SUCESSO");
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        private String[] getDadosPeer(Mensagem mensagemPeersComOArquivo) {
            Map<String, Object> mensagensArquivosPeer = mensagemPeersComOArquivo.getMensagens();
            String tituloRespostaPeersComOArquivo = mensagemPeersComOArquivo.getTitulo();

            String[] peerInfo = null;
            
            if (tituloRespostaPeersComOArquivo.equals("SEARCH_OK") && mensagensArquivosPeer.get("lista_peers") instanceof Set<?>) {
                @SuppressWarnings("unchecked")
                Set<String> listaPeers = (Set<String>) mensagensArquivosPeer.get("lista_peers");
                peerInfo = listaPeers.stream().findFirst().get().split("_");
            }
            return peerInfo;
        }

        private Mensagem getPeersComArquivo(String nomArquivo) {
            try {
                this.datagramSocket = new DatagramSocket();
            } catch (SocketException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
            //ENVIO DA PERGUNTA AO SERVIDOR SOBRE QUAIS PEERS TEM O ARQUIVO
            Mensagem requisicaoPeers = new Mensagem("SEARCH");
            requisicaoPeers.adicionarMensagem("arquivo_requistado", nomArquivo);
            
            System.out.println("REQUISITANDO AO SERVIDOR A LISTA DE PEERS QUE POSSUEM O ARQUIVO:" + nomArquivo);
            Mensagem.enviarMensagemUDP(requisicaoPeers, Servidor.ENDERECO_SERVIDOR, Servidor.PORTA_SOCKET_RECEPTOR, datagramSocket);


            //RECEBIMENTO DO SERVIDOR DA LISTA DE PEERS COM O ARQUIVO
            System.out.println("AGUARDANDO LISTA DE PEERS COM O ARQUIVO:");
            Mensagem mensagemPeersComOArquivo = Mensagem.receberMensagemUDP(datagramSocket);
            System.out.println("PEERS COM O ARQUIVO:" + mensagemPeersComOArquivo);
            return mensagemPeersComOArquivo;
        }

        private String getArquivoNomeArquivoRequerido() {
            //INPUT DO USUARIO SOBRE QUAL ARQUIVO QUER BAIXAR
            System.out.println("Digite o nome do arquivo (com extensão) que desaja baixar:");
            BufferedReader inputUsuario = new BufferedReader(new InputStreamReader(System.in));
            String nomArquivo = "";
            
            try {
                nomArquivo = inputUsuario.readLine();
            } catch (IOException e1) {

                e1.printStackTrace();
            }
            return nomArquivo;
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
        // client.startServer();
        peer.startClientConsumer();;
    }
}