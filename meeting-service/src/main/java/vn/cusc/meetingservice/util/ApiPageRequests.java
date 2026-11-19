package vn.cusc.meetingservice.util;

import java.util.Set;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import vn.cusc.meetingservice.exception.BusinessValidationException;

public final class ApiPageRequests {
    private ApiPageRequests() { }
    public static Pageable bounded(int page, int size, Sort sort) {
        if (page < 0 || size < 1 || size > 100 || (long) page * size > 1_000_000)
            throw new BusinessValidationException("Phân trang cần page không âm, size từ 1 đến 100 và phạm vi hợp lệ.");
        return PageRequest.of(page, size, sort);
    }
    public static Pageable meeting(int page, int size, String value) {
        String[] parts = value == null ? new String[0] : value.split(",", -1);
        Set<String> allowed = Set.of("startTime", "endTime", "createdAt", "updatedAt", "title", "meetingCode", "status", "id");
        if (parts.length < 1 || parts.length > 2 || !allowed.contains(parts[0])
            || parts.length == 2 && !parts[1].equalsIgnoreCase("asc") && !parts[1].equalsIgnoreCase("desc"))
            throw new BusinessValidationException("Tiêu chí sắp xếp không hợp lệ.");
        Sort sort = Sort.by(parts.length == 2 ? Sort.Direction.fromString(parts[1]) : Sort.Direction.DESC, parts[0]);
        if (!parts[0].equals("id")) sort = sort.and(Sort.by(Sort.Direction.DESC, "id"));
        return bounded(page, size, sort);
    }
    public static void query(String query) {
        if (query != null && (query.length() > 255 || query.codePoints().anyMatch(Character::isISOControl)))
            throw new BusinessValidationException("Từ khóa tìm kiếm tối đa 255 ký tự.");
    }
}
