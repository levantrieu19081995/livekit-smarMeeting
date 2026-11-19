package vn.cusc.meetingservice.repository;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import vn.cusc.meetingservice.entity.UserIdentityEntity;
import vn.cusc.meetingservice.model.enums.AuthProvider;

public interface UserIdentityRepository extends JpaRepository<UserIdentityEntity, Long> {
    Optional<UserIdentityEntity> findByProviderAndProviderSubject(AuthProvider provider, String providerSubject);
}
