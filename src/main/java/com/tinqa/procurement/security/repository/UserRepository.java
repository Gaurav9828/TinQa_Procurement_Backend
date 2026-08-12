package com.tinqa.procurement.security.repository;

import com.tinqa.procurement.security.model.AuthClient;
import com.tinqa.procurement.security.model.Role;
import com.tinqa.procurement.security.model.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByUsernameAndAuthClient(String username, AuthClient authClient);

    Optional<User> findByEmailAndAuthClient(String email, AuthClient authClient);

    Optional<User> findByUsername(String username);

    boolean existsByUsername(String username);

    boolean existsByEmail(String email);

    boolean existsByRole(Role role);
}