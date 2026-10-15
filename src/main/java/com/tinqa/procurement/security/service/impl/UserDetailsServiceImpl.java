package com.tinqa.procurement.security.service.impl;

import com.tinqa.procurement.security.access.AccessResolver;
import com.tinqa.procurement.security.access.Permission;
import com.tinqa.procurement.security.access.UserAccess;
import com.tinqa.procurement.security.model.User;
import com.tinqa.procurement.security.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class UserDetailsServiceImpl implements UserDetailsService {

    private final UserRepository userRepository;
    private final AccessResolver accessResolver;

    @Override
    public UserDetails loadUserByUsername(String username) {
        return userRepository.findByUsername(username)
                .map(user -> toUserDetails(user, accessResolver.resolve(user)))
                .orElseThrow(() -> new UsernameNotFoundException("User not found"));
    }

    // Spring Security's view of an account: the role becomes ROLE_<role>, each permission PERM_<permission>
    public static UserDetails toUserDetails(User user, UserAccess access) {
        List<GrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()));
        for (Permission permission : access.permissions()) {
            authorities.add(new SimpleGrantedAuthority(permission.authority()));
        }
        return org.springframework.security.core.userdetails.User.builder()
                .username(user.getUsername())
                .password(user.getPassword())
                .authorities(authorities)
                .disabled(!user.isEnabled())
                .accountLocked(!user.isAccountNonLocked())
                .build();
    }
}
