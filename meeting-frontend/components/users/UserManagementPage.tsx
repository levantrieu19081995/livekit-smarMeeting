'use client';

import { HomeBrandLink } from '@/components/home/HomeBrandLink';

import { useEffect, useMemo, useState } from 'react';
import { ManagementPageHero } from '@/components/management/ManagementPageHero';
import { useManagementAuth } from '@/hooks/useManagementAuth';
import {
  activateManagementUser,
  disableManagementUser,
  searchManagementUsers,
} from '@/services/management/managementUserService';
import type { ManagementUser } from '@/types/management/user';
import styles from '@/styles/management/UserPages.module.css';

type UserIconName =
  | 'chevronLeft'
  | 'chevronRight'
  | 'edit'
  | 'filter'
  | 'plus'
  | 'search'
  | 'sort'
  | 'userOff';

type ManagementUserWithDates = ManagementUser & {
  createdAt?: string | null;
  updatedAt?: string | null;
};

function UserIcon({ name }: { name: UserIconName }) {
  const paths: Record<UserIconName, React.ReactNode> = {
    chevronLeft: <path d="m15 18-6-6 6-6" />,
    chevronRight: <path d="m9 6 6 6-6 6" />,
    edit: (
      <>
        <path d="M4 20h4L18.5 9.5a2.1 2.1 0 0 0-3-3L5 17z" />
        <path d="m14 7 3 3" />
      </>
    ),
    filter: (
      <>
        <path d="M4 6h16M7 12h10M10 18h4" />
        <path d="M8 4v4M16 10v4M12 16v4" />
      </>
    ),
    plus: <path d="M12 5v14M5 12h14" />,
    search: (
      <>
        <circle cx="11" cy="11" r="7" />
        <path d="m16.5 16.5 3.5 3.5" />
      </>
    ),
    sort: (
      <>
        <path d="m8 7 4-4 4 4" />
        <path d="m16 17-4 4-4-4" />
      </>
    ),
    userOff: (
      <>
        <circle cx="10" cy="8" r="3.5" />
        <path d="M3.5 19a6.5 6.5 0 0 1 9.5-5.76" />
        <path d="M17 10v7M13.5 13.5h7" />
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
      focusable="false"
    >
      {paths[name]}
    </svg>
  );
}

function getInitials(name: string) {
  return (
    name
      .trim()
      .split(/\s+/)
      .map((part) => part[0])
      .join('')
      .slice(0, 2)
      .toUpperCase() || 'U'
  );
}

function roleLabel(role: ManagementUser['systemRole']) {
  if (role === 'ADMIN') return 'ADMIN';
  if (role === 'MEETING_MANAGER') return 'MANAGER';
  return 'USER';
}

function statusLabel(status: ManagementUser['accountStatus']) {
  if (status === 'ACTIVE') return 'ACTIVE';
  if (status === 'DISABLED') return 'DISABLED';
  return 'PENDING';
}

function formatDateTime(value?: string | null) {
  if (!value) return '-';
  return new Intl.DateTimeFormat('vi-VN', {
    day: '2-digit',
    month: '2-digit',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  }).format(new Date(value));
}

export function UserManagementPage() {
  const { user } = useManagementAuth();
  const [query, setQuery] = useState('');
  const [roleFilter, setRoleFilter] = useState<'ALL' | ManagementUser['systemRole']>('ALL');
  const [statusFilter, setStatusFilter] = useState<'ALL' | ManagementUser['accountStatus']>('ALL');
  const [pageSize, setPageSize] = useState(10);
  const [page, setPage] = useState(1);
  const [users, setUsers] = useState<ManagementUser[]>([]);
  const [error, setError] = useState<string | null>(null);

  async function load() {
    try {
      setError(null);
      setUsers(await searchManagementUsers(query));
    } catch (loadError) {
      setError(
        loadError instanceof Error ? loadError.message : 'Không thể tải danh sách người dùng.',
      );
    }
  }

  useEffect(() => {
    void load();
  }, []);

  const filteredUsers = useMemo(
    () =>
      users.filter((entry) => {
        const matchesRole = roleFilter === 'ALL' || entry.systemRole === roleFilter;
        const matchesStatus = statusFilter === 'ALL' || entry.accountStatus === statusFilter;
        return matchesRole && matchesStatus;
      }),
    [roleFilter, statusFilter, users],
  );

  const totalPages = Math.max(1, Math.ceil(filteredUsers.length / pageSize));
  const safePage = Math.min(page, totalPages);
  const pageStart = filteredUsers.length === 0 ? 0 : (safePage - 1) * pageSize + 1;
  const pageEnd = Math.min(safePage * pageSize, filteredUsers.length);
  const visibleUsers = filteredUsers.slice((safePage - 1) * pageSize, safePage * pageSize);

  if (user?.systemRole !== 'ADMIN') {
    return (
      <div className={styles.page}>
        <div className={styles.empty}>Bạn không đủ quyền truy cập trang này.</div>
      </div>
    );
  }

  return (
    <div className={styles.page}>
      <ManagementPageHero
        eyebrow={
          <span className={styles.heroBreadcrumb}>
            <span>
              <HomeBrandLink />
            </span>
            <span className={styles.heroBreadcrumbSlash}>/</span>
            <span className={styles.heroBreadcrumbMuted}>Người dùng</span>
          </span>
        }
        title="Người dùng"
        subtitle="Quản lý tài khoản người dùng trong hệ thống."
        actions={[
          {
            label: 'Thêm người dùng',
            icon: <UserIcon name="plus" />,
            disabled: true,
            title: 'Chưa có luồng thêm người dùng trong source hiện tại.',
          },
        ]}
        tagline={{ line1: 'Kết nối tri thức', line2: 'Tạo giá trị mới' }}
      />

      <section className={styles.toolbar} aria-label="Bộ lọc người dùng">
        <label className={styles.searchField}>
          <span className={styles.visuallyHidden}>Tìm kiếm người dùng</span>
          <UserIcon name="search" />
          <input
            value={query}
            onChange={(event) => setQuery(event.target.value)}
            placeholder="Tìm theo tên, username, email..."
          />
        </label>
        <label className={styles.selectField}>
          <span className={styles.visuallyHidden}>Vai trò</span>
          <select
            value={roleFilter}
            onChange={(event) => {
              setRoleFilter(event.target.value as typeof roleFilter);
              setPage(1);
            }}
          >
            <option value="ALL">Tất cả vai trò</option>
            <option value="ADMIN">ADMIN</option>
            <option value="MEETING_MANAGER">MANAGER</option>
            <option value="USER">USER</option>
          </select>
        </label>
        <label className={styles.selectField}>
          <span className={styles.visuallyHidden}>Trạng thái</span>
          <select
            value={statusFilter}
            onChange={(event) => {
              setStatusFilter(event.target.value as typeof statusFilter);
              setPage(1);
            }}
          >
            <option value="ALL">Tất cả trạng thái</option>
            <option value="ACTIVE">ACTIVE</option>
            <option value="PENDING_APPROVAL">PENDING</option>
            <option value="DISABLED">DISABLED</option>
          </select>
        </label>
        <button type="button" onClick={() => void load()} className={styles.filterButton}>
          <UserIcon name="filter" />
          <span>Bộ lọc</span>
        </button>
      </section>

      {error ? <p className={styles.error}>{error}</p> : null}

      <section className={styles.tablePanel} aria-label="Danh sách người dùng">
        <header className={styles.tableHeader}>
          <strong>Tổng {filteredUsers.length} người dùng</strong>
        </header>
        <div className={styles.tableScroller}>
          <table className={styles.userTable}>
            <colgroup>
              <col className={styles.selectColumn} />
              <col className={styles.indexColumn} />
              <col className={styles.userColumn} />
              <col className={styles.roleColumn} />
              <col className={styles.statusColumn} />
              <col className={styles.createdColumn} />
              <col className={styles.updatedColumn} />
              <col className={styles.actionColumn} />
            </colgroup>
            <thead>
              <tr>
                <th>
                  <input type="checkbox" aria-label="Chọn tất cả người dùng" disabled />
                </th>
                <th>#</th>
                <th>
                  <span>Người dùng</span>
                  <UserIcon name="sort" />
                </th>
                <th>
                  <span>Vai trò</span>
                  <UserIcon name="sort" />
                </th>
                <th>
                  <span>Trạng thái</span>
                  <UserIcon name="sort" />
                </th>
                <th>
                  <span>Ngày tạo</span>
                  <UserIcon name="sort" />
                </th>
                <th>
                  <span>Ngày cập nhật</span>
                  <UserIcon name="sort" />
                </th>
                <th>Thao tác</th>
              </tr>
            </thead>
            <tbody>
              {visibleUsers.map((entry, index) => {
                const datedEntry = entry as ManagementUserWithDates;
                const isDisabled = entry.accountStatus === 'DISABLED';
                return (
                  <tr key={entry.publicId}>
                    <td data-label="Chọn">
                      <input type="checkbox" aria-label={`Chọn ${entry.fullName}`} />
                    </td>
                    <td data-label="#">{(safePage - 1) * pageSize + index + 1}</td>
                    <td data-label="Người dùng">
                      <span className={styles.userCell}>
                        {entry.avatarUrl ? (
                          <img src={entry.avatarUrl} alt="" className={styles.avatar} />
                        ) : (
                          <span className={styles.avatar}>{getInitials(entry.fullName)}</span>
                        )}
                        <span>
                          <strong>{entry.fullName}</strong>
                          <small>{entry.email}</small>
                        </span>
                      </span>
                    </td>
                    <td data-label="Vai trò">
                      <span className={`${styles.roleBadge} ${styles[`role${entry.systemRole}`]}`}>
                        {roleLabel(entry.systemRole)}
                      </span>
                    </td>
                    <td data-label="Trạng thái">
                      <span
                        className={`${styles.statusBadge} ${styles[`status${entry.accountStatus}`]}`}
                      >
                        <i aria-hidden="true" />
                        {statusLabel(entry.accountStatus)}
                      </span>
                    </td>
                    <td
                      data-label="Ngày tạo"
                      className={!datedEntry.createdAt ? styles.mutedCell : styles.dateCell}
                    >
                      {formatDateTime(datedEntry.createdAt)}
                    </td>
                    <td
                      data-label="Ngày cập nhật"
                      className={!datedEntry.updatedAt ? styles.mutedCell : styles.dateCell}
                    >
                      {formatDateTime(datedEntry.updatedAt)}
                    </td>
                    <td data-label="Thao tác">
                      <span className={styles.actions}>
                        <button
                          type="button"
                          className={styles.secondaryButton}
                          disabled
                          title="Chưa có luồng đổi role trong source hiện tại."
                        >
                          <UserIcon name="edit" />
                          <span>Đổi role</span>
                        </button>
                        <button
                          type="button"
                          className={styles.secondaryButton}
                          onClick={() =>
                            void (
                              isDisabled
                                ? activateManagementUser(entry.publicId)
                                : disableManagementUser(entry.publicId)
                            ).then(load)
                          }
                        >
                          <UserIcon name="userOff" />
                          <span>{isDisabled ? 'Kích hoạt' : 'Vô hiệu hóa'}</span>
                        </button>
                      </span>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>

          {visibleUsers.length === 0 ? (
            <div className={styles.emptyTable}>Không tìm thấy người dùng phù hợp.</div>
          ) : null}
        </div>

        <footer className={styles.pagination}>
          <label className={styles.pageSize}>
            <span>Hiển thị</span>
            <select
              value={pageSize}
              onChange={(event) => {
                setPageSize(Number(event.target.value));
                setPage(1);
              }}
            >
              <option value={10}>10</option>
              <option value={20}>20</option>
              <option value={50}>50</option>
            </select>
            <span>bản ghi</span>
          </label>
          <span className={styles.pageInfo}>
            {pageStart} - {pageEnd} của {filteredUsers.length} bản ghi
          </span>
          <div className={styles.pageButtons}>
            <button
              type="button"
              onClick={() => setPage((current) => Math.max(1, current - 1))}
              disabled={safePage <= 1}
              aria-label="Trang trước"
            >
              <UserIcon name="chevronLeft" />
            </button>
            <button type="button" className={styles.pageActive}>
              {safePage}
            </button>
            <button
              type="button"
              onClick={() => setPage((current) => Math.min(totalPages, current + 1))}
              disabled={safePage >= totalPages}
              aria-label="Trang sau"
            >
              <UserIcon name="chevronRight" />
            </button>
          </div>
        </footer>
      </section>
    </div>
  );
}
