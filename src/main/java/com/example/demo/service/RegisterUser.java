package com.example.demo.service;

import com.example.demo.model.User;
import com.example.demo.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class RegisterUser {

    UserRepository userRepository;

    public RegisterUser(UserRepository userRepository)
    {
        this.userRepository = userRepository;
    }

    public User addUser(User newUser)
    {
        log.info("Saving user: {}", newUser.getUserName());
        return userRepository.save(newUser);
    }
}
