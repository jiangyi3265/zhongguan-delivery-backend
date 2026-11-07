import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
public class PasswordHash {
    public static void main(String[] args) throws Exception {
        String password = new java.io.BufferedReader(new java.io.InputStreamReader(System.in)).readLine();
        System.out.print(new BCryptPasswordEncoder(12).encode(password));
    }
}
