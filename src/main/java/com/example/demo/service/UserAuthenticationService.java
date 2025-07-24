package com.example.demo.service;

import com.example.demo.dto.Credentials;
import com.example.demo.model.User;
import com.example.demo.repository.UserRepository;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
@AllArgsConstructor
public class UserAuthenticationService {

    UserRepository userRepository;

    public Optional<User> authenticateUser(Credentials userLoginDetails) {
        return userRepository.findByUserName(userLoginDetails.userName);
    }
}
