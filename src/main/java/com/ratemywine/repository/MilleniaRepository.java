package com.ratemywine.repository;

import com.ratemywine.model.Millenia;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MilleniaRepository extends JpaRepository<Millenia, Long> {
    Optional<Millenia> findByPageUrlAndSourceKey(String pageUrl, String sourceKey);
}
