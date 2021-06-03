import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;

public class Client {


    public static void main(String[] args) throws FileNotFoundException {
        String path = "src/client/c1/video-test.mp4";
        int bufferSize = 8 * 1024;
        try ( InputStream reader = new BufferedInputStream(new FileInputStream(path), bufferSize) ) {
            byte[] data = new byte[bufferSize];
            
            while (reader.read(data, 0, data.length) != -1) {


            }
        } catch (Exception e) {
            e.printStackTrace();
        }        
    }
}