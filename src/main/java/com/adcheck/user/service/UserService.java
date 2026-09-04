package com.adcheck.user.service;

import com.adcheck.global.exception.UserNotFoundException;
import com.adcheck.user.dto.UserResponse;
import com.adcheck.user.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class UserService {

    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public UserResponse getCurrentUser(Long userId) {
        return userRepository.findById(userId)
                .map(UserResponse::from)
                .orElseThrow(UserNotFoundException::new);
    }
}
