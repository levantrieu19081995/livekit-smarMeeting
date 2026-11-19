import type { ReactNode } from 'react';
import type { MinutesListFilters } from '@/types/management/minutes';
import styles from '@/styles/management/ManagementMinutesList.module.css';

interface MinutesFiltersProps {
  filters: MinutesListFilters;
  onChange: (filters: MinutesListFilters) => void;
  onRefresh: () => void;
}

type ToolbarIconName = 'calendar' | 'chevronDown' | 'filePlus' | 'refresh' | 'search' | 'user';

function ToolbarIcon({ name }: { name: ToolbarIconName }) {
  const paths: Record<ToolbarIconName, ReactNode> = {
    calendar: (
      <>
        <path d="M7 3v3" />
        <path d="M17 3v3" />
        <rect x="3.5" y="5" width="17" height="16" rx="2.5" />
        <path d="M3.5 9.5h17" />
      </>
    ),
    chevronDown: <path d="m6 9 6 6 6-6" />,
    filePlus: (
      <>
        <path d="M6 3.5h8l4 4v13H6z" />
        <path d="M14 3.5v4h4" />
        <path d="M12 11v6" />
        <path d="M9 14h6" />
      </>
    ),
    refresh: (
      <>
        <path d="M20 6v5h-5" />
        <path d="M4 18v-5h5" />
        <path d="M18.2 9A7 7 0 0 0 6.1 6.8L4 9" />
        <path d="M5.8 15A7 7 0 0 0 17.9 17.2L20 15" />
      </>
    ),
    search: (
      <>
        <circle cx="11" cy="11" r="6.5" />
        <path d="m16 16 4 4" />
      </>
    ),
    user: (
      <>
        <circle cx="12" cy="8" r="3.5" />
        <path d="M5 20c.8-4 3.2-6 7-6s6.2 2 7 6" />
      </>
    ),
  };

  return (
    <svg
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth={1.8}
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
    >
      {paths[name]}
    </svg>
  );
}

export function MinutesFilters({ filters, onChange, onRefresh }: MinutesFiltersProps) {
  return (
    <section className={styles.filterBar} aria-label="Bộ lọc biên bản">
      <label className={styles.searchField}>
        <span className={styles.visuallyHidden}>Tìm kiếm</span>
        <ToolbarIcon name="search" />
        <input
          value={filters.search}
          onChange={(event) => onChange({ ...filters, search: event.target.value })}
          placeholder="Tìm kiếm theo tên cuộc họp, chủ tọa, nội dung..."
        />
      </label>

      <label className={styles.filterField}>
        <span className={styles.visuallyHidden}>Trạng thái</span>
        <select
          value={filters.status}
          onChange={(event) =>
            onChange({ ...filters, status: event.target.value as MinutesListFilters['status'] })
          }
        >
          <option value="ALL">Tất cả trạng thái</option>
          <option value="NOT_GENERATED">Chưa tạo</option>
          <option value="PROCESSING">Đang xử lý</option>
          <option value="DRAFT">Bản nháp</option>
          <option value="FINAL">Hoàn tất</option>
          <option value="FAILED">Lỗi xử lý</option>
        </select>
        <ToolbarIcon name="chevronDown" />
      </label>

      <label className={styles.filterField}>
        <span className={styles.visuallyHidden}>Thời gian</span>
        <ToolbarIcon name="calendar" />
        <select
          value={filters.time}
          onChange={(event) =>
            onChange({ ...filters, time: event.target.value as MinutesListFilters['time'] })
          }
        >
          <option value="ALL">Tất cả thời gian</option>
          <option value="TODAY">Hôm nay</option>
          <option value="LAST_7_DAYS">7 ngày gần đây</option>
          <option value="LAST_30_DAYS">30 ngày gần đây</option>
        </select>
        <ToolbarIcon name="chevronDown" />
      </label>

      <label className={styles.filterField}>
        <span className={styles.visuallyHidden}>Vai trò của tôi</span>
        <ToolbarIcon name="user" />
        <select
          value={filters.role}
          onChange={(event) =>
            onChange({ ...filters, role: event.target.value as MinutesListFilters['role'] })
          }
        >
          <option value="ALL">Tất cả vai trò của tôi</option>
          <option value="CHAIRMAN">Chủ tọa</option>
          <option value="SECRETARY">Thư ký</option>
          <option value="MEMBER">Thành viên</option>
        </select>
        <ToolbarIcon name="chevronDown" />
      </label>

      <div className={styles.toolbarActions}>
        <button type="button" className={styles.refreshButton} onClick={onRefresh}>
          <ToolbarIcon name="refresh" />
          <span>Làm mới</span>
        </button>

        {/* PRESENTATION_ONLY: no global minutes generation flow in current source */}
        <button type="button" className={styles.createButton} aria-disabled="true">
          <ToolbarIcon name="filePlus" />
          <span>Tạo biên bản</span>
        </button>
      </div>
    </section>
  );
}
