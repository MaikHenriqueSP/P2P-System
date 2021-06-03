import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class Client {


    public static void main(String[] args) throws IOException {
        String readingPath = "src/client/c1/video-test.mp4";
        String writingPathDirs = "src/client/c2/";
        File dirs = new File(writingPathDirs);
        dirs.mkdirs();
        
        String writingPathFile = writingPathDirs + "video-copied.mp4";
        File file = new File(writingPathFile);
        file.createNewFile();

        int bufferSize = 8 * 1024;
        try ( InputStream reader = new BufferedInputStream(new FileInputStream(readingPath), bufferSize);
            OutputStream writer = new BufferedOutputStream(new FileOutputStream(file, true))
        ) {
            byte[] data = new byte[bufferSize];
            
            while (reader.read(data, 0, data.length) != -1) {
                writer.write(data);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }        
    }
}