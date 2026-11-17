package com.pnh.coreapi.adapter.out;

import com.pnh.coreapi.domain.Client;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClientRepository extends JpaRepository<Client, String> {}
