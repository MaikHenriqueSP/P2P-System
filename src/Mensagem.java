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

/**
 * Defines a template for the message, with title and a body formed by a set of messages mapped in key-value fashion.
 * 
 * @author Maik Henrique
 */
public class Mensagem implements Serializable {

    private static final long serialVersionUID = -3969352858203924755L;

    private final String titulo;
    private final Map<String, Object> mensagens;    

    public Mensagem(String titulo) {
        this.titulo = titulo;
        this.mensagens = new HashMap<>();
    }

    /**
     * Adds a body value to the message
     * 
     * @param titulo title of the message
     * @param corpoMensagem message body
     */
    public void adicionarMensagem(String titulo, Object corpoMensagem) {
        mensagens.put(titulo, corpoMensagem);
    }  
 
    public String getTitulo() {
        return titulo;
    }

    public Map<String, Object> getMensagens() {
        return mensagens;
    }

    /**
     * Receives TCP messages
     * 
     * @param inputStream inputStream of the TCP connection of the pair
     * @return received message
     */
    public static Mensagem receberMensagemTCP(InputStream inputStream) {
        try {
            ObjectInputStream objectInputStream = new ObjectInputStream(new BufferedInputStream(inputStream));
            return (Mensagem) objectInputStream.readObject();  
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    /**
     * Sends TCP messages
     * 
     * @param outputStream stream of the TCP connection in which the message should be written
     * @param mensagem message to be sent
     */
    public static void enviarMensagemTCP(OutputStream outputStream, Mensagem mensagem) {
        try {
            ObjectOutputStream objectOutputStream = new ObjectOutputStream(new BufferedOutputStream(outputStream));
            objectOutputStream.writeObject(mensagem);
            objectOutputStream.flush();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Sends UDP messages
     * 
     * @param mensagem message to be sent
     * @param endereco IP address of the destination
     * @param porta port of the destination
     * @param socketUDP configured UDP socket
     */
    public static void enviarMensagemUDP(Mensagem mensagem, String endereco, int porta, DatagramSocket socketUDP) {
        InetAddress enderecoDestinatarioInet;        
        try {
            enderecoDestinatarioInet = InetAddress.getByName(endereco);
            ByteArrayOutputStream byteOutputStream = new ByteArrayOutputStream();
            ObjectOutputStream objectOutputStream = new ObjectOutputStream(new BufferedOutputStream(byteOutputStream));
            
            objectOutputStream.writeObject(mensagem);
            objectOutputStream.flush();
            byte[] mensagemEmBytes = byteOutputStream.toByteArray();
    
            DatagramPacket packet = new DatagramPacket(mensagemEmBytes, mensagemEmBytes.length, enderecoDestinatarioInet, porta);
            socketUDP.send(packet);
        } catch (IOException e) {
            e.printStackTrace();
        }        
    }

    /**
     * Receives UDP messages within size at most of 
     * 
     * @param socketUDP socket UDP utilitário para o recebimento de mensagem.
     * @return devolve uma instância construída da mensagem recebida.
     */
    public static Mensagem receberMensagemUDP(DatagramSocket socketUDP) {
        byte[] bytesRecebidos = new byte[8 * 1024];
        DatagramPacket pacote = new DatagramPacket(bytesRecebidos, bytesRecebidos.length);

        try {
            socketUDP.receive(pacote);
        } catch (IOException e1) {
            return null;
        }
        
        try (ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(pacote.getData());
            ObjectInputStream inputObject = new ObjectInputStream(new BufferedInputStream(byteArrayInputStream));) {            
            return (Mensagem) inputObject.readObject();
        } catch (ClassNotFoundException | IOException e) {
            return null;
        }
    }

    public static Mensagem deserializarBytes(byte[] bytes) throws IOException, ClassNotFoundException {
        ByteArrayInputStream byteStream = new ByteArrayInputStream(bytes);
        ObjectInputStream objectStream = new ObjectInputStream(byteStream);
        Mensagem mensagem =  (Mensagem) objectStream.readObject();
        objectStream.close();
        
        return mensagem;
    }

    @Override
    public String toString() {
        return "Mensagem [mensagens=" + mensagens + ", titulo=" + titulo + "]";
    }

}