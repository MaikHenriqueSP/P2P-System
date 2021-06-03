import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;

public class Client {


    public static void main(String[] args) throws FileNotFoundException {
        String path = "client/c1/video-test.mp4";
        int bufferSize = 8 * 1024;
        try ( InputStream reader = new BufferedInputStream(new FileInputStream(path), bufferSize) ) {
            

        } catch (Exception e) {
            e.printStackTrace();
        }        
    }
}