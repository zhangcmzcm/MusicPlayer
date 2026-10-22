package org.example.music.verification.repository;

import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import org.example.music.verification.domain.VerificationCodeEntity;
import org.example.music.verification.domain.VerificationCodePurpose;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface VerificationCodeRepository extends JpaRepository<VerificationCodeEntity, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select verificationCode
            from VerificationCodeEntity verificationCode
            where verificationCode.email = :email
              and verificationCode.purpose = :purpose
            order by verificationCode.createdAt desc, verificationCode.id desc
            """)
    List<VerificationCodeEntity> findLatestForUpdate(
            @Param("email") String email,
            @Param("purpose") VerificationCodePurpose purpose,
            Pageable pageable
    );


    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update VerificationCodeEntity verificationCode
            set verificationCode.attempts = verificationCode.attempts + 1,
                verificationCode.updatedAt = :now
            where verificationCode.id = :id
            """)
    int incrementAttempts(@Param("id") Long id, @Param("now") LocalDateTime now);
}
