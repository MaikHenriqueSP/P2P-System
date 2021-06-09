import java.io.Serializable;
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

}