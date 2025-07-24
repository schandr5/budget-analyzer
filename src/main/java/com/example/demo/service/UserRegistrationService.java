package com.example.demo.service;

import com.example.demo.model.User;
import com.example.demo.repository.UserRepository;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@AllArgsConstructor
public class UserRegistrationService {

    UserRepository userRepository;

    public User addUser(User newUser)
    {
        log.info("Saving user: {}", newUser.getUserName());
        return userRepository.save(newUser);
    }
}
