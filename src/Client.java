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

public class Client {
    private ServerSocket server;
    private String clientName;
    private static final String BASE_CLIENT_FOLDER_PATH = "src/client/resource/";
    

    public Client(int port, String clientName) throws IOException {
        this.server = new ServerSocket(port);
        this.clientName = clientName;
        createClientFolderIfNotExists();
    }

    private void createClientFolderIfNotExists() {
        String clientFolderPath = BASE_CLIENT_FOLDER_PATH + clientName.toLowerCase();
        File dirs = new File(clientFolderPath);
        dirs.mkdirs();
    }

    class FileServerThread extends Thread {
        private Socket client;

        public FileServerThread(Socket client) {
            this.client = client;
        }

        @Override
        public void run() {
            super.run();
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