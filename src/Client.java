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

public class Client {
    private ServerSocket server;
    private String clientName;
    private static final String BASE_CLIENT_FOLDER_PATH = "src/client/resource/";
    private String clientResourcesFilePath;
    

    public Client(int port, String clientName) throws IOException {
        this.server = new ServerSocket(port);
        this.clientName = clientName;
        this.clientResourcesFilePath = BASE_CLIENT_FOLDER_PATH + clientName;
        createClientFolderIfNotExists();
    }

    private void createClientFolderIfNotExists() {
        File dirs = new File(clientResourcesFilePath);
        dirs.mkdirs();
    }

    /*
    * The class aims to enable multiple clients downloading from the same server concurrently on which
    * each thread is going to be responsible to talk to a client "privately"
    */
    class FileServerThread extends Thread {
        private Socket socket;

        public FileServerThread(Socket socket) {
            this.socket = socket;
        }

        @Override
        public void run() {
            super.run();
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