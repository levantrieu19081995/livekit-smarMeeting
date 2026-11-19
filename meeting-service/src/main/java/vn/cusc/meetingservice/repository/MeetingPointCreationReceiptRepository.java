package vn.cusc.meetingservice.repository;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import vn.cusc.meetingservice.entity.MeetingPointCreationReceiptEntity;

public interface MeetingPointCreationReceiptRepository extends JpaRepository<MeetingPointCreationReceiptEntity, Long> {
    Optional<MeetingPointCreationReceiptEntity> findByCreatedByAccountIdAndRequestId(Long accountId, String requestId);
}
