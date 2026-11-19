-- ============================================
-- XEM DANH SÁCH CUỘC HỌP
-- ============================================

SELECT *
FROM public.meetings
ORDER BY id ASC;


-- ============================================
-- XEM MEETING ID = 13
-- ============================================

SELECT
    id,
    room_name,
    start_time,
    end_time,
    status
FROM meetings
WHERE id = 13;


-- ============================================
-- TEST: ĐƯA END_TIME VỀ 1 PHÚT TRƯỚC
-- CẨN THẬN: LỆNH UPDATE DATA
-- ============================================

UPDATE meetings
SET end_time = LOCALTIMESTAMP - INTERVAL '1 minute'
WHERE id = 13;


-- ============================================
-- TEST: CHO MEETING KẾT THÚC
-- ============================================

UPDATE meetings
SET
    end_time = LOCALTIMESTAMP - INTERVAL '1 minute',
    status = 'COMPLETED'
WHERE id = 13;


-- ============================================
-- KIỂM TRA SAU UPDATE
-- ============================================

SELECT
    id,
    room_name,
    start_time,
    end_time,
    status
FROM meetings
WHERE id = 13;