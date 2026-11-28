package com.vsp.notification.coreapi.adapter.out;

import com.vsp.notification.coreapi.domain.Client;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClientRepository extends JpaRepository<Client, String> {}
