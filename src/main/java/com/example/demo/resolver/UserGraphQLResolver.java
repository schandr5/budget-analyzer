package com.example.demo.resolver;

import com.example.demo.dto.UserDetails;
import com.example.demo.model.User;
import com.example.demo.dto.UserInput;
import lombok.extern.slf4j.Slf4j;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.stereotype.Controller;

@Controller
@Slf4j
public class UserGraphQLResolver {

    @MutationMapping
    public UserDetails addUser(@Argument("newUser") UserInput newUser) {
        User user = new User(null, newUser.getName(), newUser.getUserName(), newUser.getPassword());
        log.info("Received user data: {}", user.getUserName());
        return new UserDetails(user.getId(), user.getUserName());
    }
}
