package com.traceability.identity;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Spring Security principal carrying the three claims extracted from the JWT:
 * user_id, tenant_id, and role. Stored in SecurityContextHolder for the
 * duration of the request; TenantContextFilter reads tenant_id from here.
 */
public record CustomUserDetails(UUID userId, UUID tenantId, String role)
        implements UserDetails {

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.toUpperCase()));
    }

    @Override public String getPassword()  { return null; }
    @Override public String getUsername()  { return userId.toString(); }
    @Override public boolean isAccountNonExpired()   { return true; }
    @Override public boolean isAccountNonLocked()    { return true; }
    @Override public boolean isCredentialsNonExpired() { return true; }
    @Override public boolean isEnabled()             { return true; }
}
