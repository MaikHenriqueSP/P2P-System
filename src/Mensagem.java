import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.HashMap;
import java.util.Map;

public class Mensagem implements Serializable {

    private static final long serialVersionUID = -3969352858203924755L;

    private final String titulo;
    private final Map<String, Object> mensagens;
    
    public Mensagem(String titulo) {
        this.titulo = titulo;
        this.mensagens = new HashMap<>();
    }

    public void adicionarMensagem(String titulo, Object corpoMensagem) {
        mensagens.put(titulo, corpoMensagem);
    }
    
    public String getTitulo() {
        return titulo;
    }

    public Map<String, Object> getMensagens() {
        return mensagens;
    }


    public static Mensagem receberMensagemTCP(InputStream inputStream) {
        try {
            ObjectInputStream objectInputStream = new ObjectInputStream(new BufferedInputStream(inputStream));
            return (Mensagem) objectInputStream.readObject();  
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    public static void enviarMensagemTCP(OutputStream outputStream, Mensagem mensagem) {
        try {
            ObjectOutputStream objectOutputStream = new ObjectOutputStream(new BufferedOutputStream(outputStream));
            objectOutputStream.writeObject(mensagem);
            objectOutputStream.flush();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void enviarMensagemUDP(Mensagem mensagem, String endereco, int port, DatagramSocket datagramSocket) {
        InetAddress hostEndereco;
        try {
            hostEndereco = InetAddress.getByName(endereco);
            ByteArrayOutputStream byteOutputStream = new ByteArrayOutputStream();
            ObjectOutputStream objectOutputStream = new ObjectOutputStream(new BufferedOutputStream(byteOutputStream));
            
            objectOutputStream.writeObject(mensagem);
            objectOutputStream.flush();
            byte[] messageInBytesArray = byteOutputStream.toByteArray();
    
            DatagramPacket packet = new DatagramPacket(messageInBytesArray, messageInBytesArray.length, hostEndereco, port);
            datagramSocket.send(packet);
        } catch (IOException e) {
            e.printStackTrace();
        }        
    }

    public static Mensagem receberMensagemUDP(DatagramSocket datagramSocket) {
        byte[] receivedBytes = new byte[8 * 1024];
        DatagramPacket spacket = new DatagramPacket(receivedBytes, receivedBytes.length);
        try {
            datagramSocket.receive(spacket);
        } catch (IOException e1) {
            // TODO Auto-generated catch block
            e1.printStackTrace();
        }
        
        try (ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(spacket.getData());
            ObjectInputStream inputObject = new ObjectInputStream(new BufferedInputStream(byteArrayInputStream));) {            
            return (Mensagem) inputObject.readObject();
        } catch (ClassNotFoundException | IOException e) {
            e.printStackTrace();
        }

        return null;
    }

    @Override
    public String toString() {
        return "Mensagem [mensagens=" + mensagens + ", titulo=" + titulo + "]";
    }

}