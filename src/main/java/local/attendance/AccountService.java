package local.attendance;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/** Login email is resolved before this service loads the permanent internal username. */
@Service
public class AccountService implements UserDetailsService {
    private final JdbcTemplate db;
    public AccountService(JdbcTemplate db) { this.db = db; }

    @Override public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        List<UserDetails> rows = db.query(
                "SELECT username,password_hash,account_role,active FROM app_users WHERE username=?",
                (rs, row) -> User.withUsername(rs.getString("username"))
                    .password(rs.getString("password_hash"))
                    .roles(rs.getString("account_role"))
                    .disabled(!rs.getBoolean("active"))
                    .build(), username);
        if (rows.size() != 1) throw new UsernameNotFoundException("Account unavailable");
        return rows.get(0);
    }
}
