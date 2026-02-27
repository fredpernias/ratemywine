package com.ratemywine.repository;

import com.ratemywine.model.Millenia;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MilleniaRepository extends JpaRepository<Millenia, Long> {
    Optional<Millenia> findByPageUrlAndSourceKey(String pageUrl, String sourceKey);

    Optional<Millenia> findFirstByPageUrl(String pageUrl);

    List<Millenia> findAllByPageUrl(String pageUrl);

    long countByPageUrl(String pageUrl);

    long countByPageUrlIn(Collection<String> pageUrls);

    List<Millenia> findAllByPageUrlIn(Collection<String> pageUrls);
}
