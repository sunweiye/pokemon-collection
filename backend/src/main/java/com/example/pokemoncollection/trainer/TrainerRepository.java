package com.example.pokemoncollection.trainer;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TrainerRepository extends JpaRepository<Trainer, Long> {
    Optional<Trainer> findByUsername(String username);
    boolean existsByUsername(String username);
}
