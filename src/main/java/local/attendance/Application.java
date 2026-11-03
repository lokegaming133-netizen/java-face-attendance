package local.attendance;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.*;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

@SpringBootApplication
public class Application {
 public static void main(String[] args){SpringApplication.run(Application.class,args);}
 @Bean UserDetailsService users(){String p=System.getenv("ADMIN_PASSWORD");if(p==null||p.length()<12)throw new IllegalStateException("Set ADMIN_PASSWORD to at least 12 characters");return new InMemoryUserDetailsManager(User.withUsername("teacher").password(new BCryptPasswordEncoder().encode(p)).roles("TEACHER").build());}
 @Bean BCryptPasswordEncoder encoder(){return new BCryptPasswordEncoder();}
 @Bean SecurityFilterChain security(HttpSecurity h)throws Exception{
  return h
   .authorizeHttpRequests(a->a
    .requestMatchers("/login","/login.html","/favicon.ico","/error").permitAll()
    .anyRequest().authenticated())
   .formLogin(f->f
    .loginPage("/login")
    .defaultSuccessUrl("/",true)
    .failureUrl("/login?error")
    .permitAll())
   .logout(l->l.logoutSuccessUrl("/login?logout").permitAll())
   .build();
 }
}
