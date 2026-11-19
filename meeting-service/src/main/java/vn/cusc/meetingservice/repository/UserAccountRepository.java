package vn.cusc.meetingservice.repository;

import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.cusc.meetingservice.entity.UserAccountEntity;
import vn.cusc.meetingservice.model.enums.AccountStatus;

public interface UserAccountRepository extends JpaRepository<UserAccountEntity, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select account from UserAccountEntity account where account.id = :accountId")
    Optional<UserAccountEntity> findByIdForUpdate(@Param("accountId") Long accountId);
    Optional<UserAccountEntity> findByPublicId(String publicId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select account from UserAccountEntity account where account.publicId = :publicId")
    Optional<UserAccountEntity> findByPublicIdForUpdate(@Param("publicId") String publicId);
    Optional<UserAccountEntity> findByNormalizedEmail(String normalizedEmail);
    Optional<UserAccountEntity> findByUsername(String username);
    boolean existsByNormalizedEmail(String normalizedEmail);
    boolean existsByUsername(String username);

    @Query("""
        select ua from UserAccountEntity ua
        where (:status is null or ua.accountStatus = :status)
          and (
            :query is null or :query = '' or
            lower(ua.fullName) like lower(concat('%', :query, '%')) or
            lower(ua.normalizedEmail) like lower(concat('%', :query, '%')) or
            lower(coalesce(ua.username, '')) like lower(concat('%', :query, '%'))
          )
        """)
    Page<UserAccountEntity> search(@Param("query") String query, @Param("status") AccountStatus status, Pageable pageable);

    @Query("""
        select ua from UserAccountEntity ua
        where ua.accountStatus = vn.cusc.meetingservice.model.enums.AccountStatus.ACTIVE
          and (
            :query is null or :query = '' or
            lower(ua.fullName) like lower(concat('%', :query, '%')) or
            lower(ua.normalizedEmail) like lower(concat('%', :query, '%')) or
            lower(coalesce(ua.username, '')) like lower(concat('%', :query, '%'))
          )
        """)
    Page<UserAccountEntity> searchActive(@Param("query") String query, Pageable pageable);
}
