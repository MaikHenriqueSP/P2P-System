
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
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public class Client {

    private ServerSocket server;
    private String clientName;
    private static final String BASE_CLIENT_FOLDER_PATH = "client/resource/";
    private String clientResourcesFilePath;
    private List<String> filesAvailable;
    public static final int FILE_TRANSFER_PACKET_SIZE = 1024 * 8;
    
    private final Thread serverThread = new Thread(() -> runFilesShareServer());
    private final Thread clientConsumerThread = new Thread(() -> runFileClientDownloader());    

    public Client(int port, String clientName) throws IOException {
        this.server = new ServerSocket(port);
        this.clientName = clientName;
        this.clientResourcesFilePath = BASE_CLIENT_FOLDER_PATH + clientName + "/";

        File clientFile = new File(clientResourcesFilePath);

        createClientFolderIfNotExists(clientFile);

        this.filesAvailable = Arrays.stream(clientFile.list()).filter(fileName -> fileName.endsWith(".mp4")).collect(Collectors.toList());
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
    
    private void runFileClientDownloader() {
        while (true) {
            System.out.println("-- DO YOU WANT TO DOWNLOAD A FILE? (Y/N)");
            System.out.println("-- TYPE IN THE SERVER'S PORT THAT YOU WANT TO DOWNLOAD THE FILE FROM");
            BufferedReader inputReader = new BufferedReader(new InputStreamReader(System.in));

            try {
                int port = Integer.parseInt(inputReader.readLine());
                new FileClientThread("localhost", port).start();
            } catch (IOException e) {
                e.printStackTrace();
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

        public FileServerThread(Socket socket) throws IOException {
            this.socket = socket;
            this.outputStream = socket.getOutputStream();
        }

        @Override
        public void run() {
            System.out.println("-- SENDING THE LIST OF CLIENTS AVAILABLE TO THE CLIENT OF PORT: " + this.socket.getPort());
            //PrintStream messageWriter = new PrintStream(new BufferedOutputStream(outputStream));
            //messageWriter.println(filesAvailable);
            //messageWriter.flush();

            String dowloadFilePath = clientResourcesFilePath + "video-test.mp4";

            System.out.println("-- TRANSFERING FILE TO THE CLIENT");

            try (BufferedOutputStream fileWriter = new BufferedOutputStream(outputStream);
                BufferedInputStream fileReader = new BufferedInputStream(new FileInputStream(dowloadFilePath))
            ) {
                byte[] packet = new byte[FILE_TRANSFER_PACKET_SIZE];

                long bytesTransfered = 0L;
                while (fileReader.read(packet) != -1) {
                    fileWriter.write(packet);
                    bytesTransfered += FILE_TRANSFER_PACKET_SIZE;
                    System.out.println(bytesTransfered + " kb");
                }

                System.out.println("------ SUCCESSFULLY FINISHED TRANSFERING THE FILE");
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

        public FileClientThread (String host, int port) throws UnknownHostException, IOException {
            this.socket = new Socket(host, port);
            this.inputStream = socket.getInputStream();
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
            System.out.println("-- CONNECTED TO THE SERVER");
            
            String writingFilePath = clientResourcesFilePath + "test-video-received.mp4";            
            File file = new File(writingFilePath);
            creatFileIfNotExists(file);
            
            System.out.println("-- BEGINNING TRANSFER");
            Long bytesTransfered = 0L;
            try (                
                BufferedInputStream fileReader = new BufferedInputStream(inputStream);
                BufferedOutputStream fileWriter = new BufferedOutputStream(new FileOutputStream(file))
            ){
                byte[] data = new byte[FILE_TRANSFER_PACKET_SIZE];

                while (fileReader.read(data) != -1) {
                    fileWriter.write(data);
                    bytesTransfered += FILE_TRANSFER_PACKET_SIZE;
                    System.out.println("+ BYTES TRANSFERED: " + bytesTransfered);
                } 
                
                System.out.println("-- SUCCESSFULLY RECEIVED THE FILE FROM THE SERVER");
            } catch (IOException e) {
                e.printStackTrace();
            }

        }
    }

    public static void main(String[] args) throws IOException {
        System.out.println("Type the server/client's LISTENING port number:");
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
        int port = Integer.parseInt(reader.readLine());
        System.out.println("Type the server/client's name:");
        String clientName = reader.readLine();

        Client client = new Client(port, clientName);
        client.startServer();
        client.startClientConsumer();;
    }
}