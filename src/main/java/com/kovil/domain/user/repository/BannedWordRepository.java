package com.kovil.domain.user.repository;

import com.kovil.domain.user.entity.BannedWord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BannedWordRepository extends JpaRepository<BannedWord, Long> {

    List<BannedWord> findAllByOrderByWordAsc();
}
