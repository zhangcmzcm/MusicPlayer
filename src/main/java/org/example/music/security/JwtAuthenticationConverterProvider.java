package org.example.music.security;

import java.util.Collection;
import java.util.Collections;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;

public class JwtAuthenticationConverterProvider {

    public JwtAuthenticationConverter get() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(
                (Converter<Jwt, Collection<GrantedAuthority>>) jwt -> Collections.emptyList()
        );
        return converter;
    }
}
