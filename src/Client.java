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
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public class Client {
    private ServerSocket server;
    private String clientName;
    private static final String BASE_CLIENT_FOLDER_PATH = "src/client/resource/";
    private String clientResourcesFilePath;
    private List<String> filesAvailable;
    

    public Client(int port, String clientName) throws IOException {
        this.server = new ServerSocket(port);
        this.clientName = clientName;
        this.clientResourcesFilePath = BASE_CLIENT_FOLDER_PATH + clientName;

        File clientFile = new File(clientResourcesFilePath);

        createClientFolderIfNotExists(clientFile);

        this.filesAvailable = Arrays.stream(clientFile.list()).filter(fileName -> fileName.endsWith(".mp4")).collect(Collectors.toList());
    }

    private void createClientFolderIfNotExists(File clientFile) {

        clientFile.mkdirs();
    }

    /*
    * The class aims to enable multiple clients downloading from the same server concurrently on which
    * each thread is going to be responsible to talk to a client "privately"
    */
    class FileServerThread extends Thread {
        private Socket socket;

        private InputStream inputStream;
        private OutputStream outputStream;
        public static final int FILE_TRANSFER_BUFFER = 1024 * 8;


        public FileServerThread(Socket socket) throws IOException {
            this.socket = socket;
            this.inputStream = socket.getInputStream();
            this.outputStream = socket.getOutputStream();
        }

        @Override
        public void run() {
            System.out.println("-- SENDING THE LIST OF CLIENTS AVAILABLE TO THE CLIENT OF PORT: " + this.socket.getPort());
            PrintStream messageWriter = new PrintStream(new BufferedOutputStream(outputStream));
            messageWriter.println(filesAvailable);
            messageWriter.flush();

            String dowloadFilePath = BASE_CLIENT_FOLDER_PATH + "video-teste.mp4";


            try (BufferedOutputStream fileWriter = new BufferedOutputStream(new FileOutputStream(dowloadFilePath));
                BufferedInputStream fileReader = new BufferedInputStream(new FileInputStream(dowloadFilePath))
            ) {

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

        public FileClientThread (String host, int port) throws UnknownHostException, IOException {
            this.socket = new Socket(host, port);
        }

    }

    public void runFilesShareServer() throws IOException {
        while (true) {
            Socket client = server.accept();    
            new FileServerThread(client).start();
        }
    }

    public static void main(String[] args) throws IOException {
        System.out.println("Type the client's sharing port number:");
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
        int port = Integer.parseInt(reader.readLine());
        System.out.println("Type the client's name:");
        String clientName = reader.readLine();

        Client client = new Client(port, clientName);
        client.runFilesShareServer();
    }
}