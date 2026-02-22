package com.shotaroi.bank.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final AuditLogRepository auditLogRepository;

    public AuditService(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    @Transactional
    public void log(Long actorCustomerId, String action, String details) {
        AuditLog auditLog = new AuditLog();
        auditLog.setActorCustomerId(actorCustomerId);
        auditLog.setAction(action);
        auditLog.setDetails(details);
        auditLogRepository.save(auditLog);
        log.debug("Audit: actor={}, action={}, details={}", actorCustomerId, action, details);
    }
}
