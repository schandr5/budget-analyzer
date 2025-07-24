package com.example.demo.resolver;

import com.example.demo.dto.UserDetails;
import com.example.demo.dto.Credentials;
import com.example.demo.model.User;
import com.example.demo.dto.UserInput;
import com.example.demo.service.UserAuthenticationService;
import com.example.demo.service.UserRegistrationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;

import java.util.Optional;

@Controller
@Slf4j
public class UserResolver {

    @Autowired
    private UserRegistrationService userRegistrationService;

    @Autowired
    private UserAuthenticationService userAuthenticationService;

    // User registration mutation
    @MutationMapping
    public UserDetails addUser(@Argument("newUser") UserInput newUser) {
        User user = new User(null, newUser.getName(), newUser.getUserName(), newUser.getPassword());
        log.info("Received user data: {}", user.getUserName());
        User savedUser = userRegistrationService.addUser(user);
        return new UserDetails(savedUser.getId(), savedUser.getUserName());
    }

    // Login user query
    @QueryMapping
    public UserDetails authenticateUser(@Argument("credentials") Credentials credentials) {
        log.info("Authenticating user: {}", credentials.getUserName());
        Optional<User> user = userAuthenticationService.authenticateUser(credentials);
        if (user.isPresent())
        {
            log.info("Required user found: {}", user.get().name);
            return new UserDetails(user.get().id, user.get().name);
        }
        else {
            throw new RuntimeException("User not found");
        }
    }
}
