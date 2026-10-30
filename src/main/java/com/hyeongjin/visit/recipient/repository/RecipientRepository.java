package com.hyeongjin.visit.recipient.repository;

import com.hyeongjin.visit.recipient.domain.Recipient;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RecipientRepository extends JpaRepository<Recipient, Long> {
}
