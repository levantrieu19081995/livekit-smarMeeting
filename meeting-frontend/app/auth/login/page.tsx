import { redirect } from 'next/navigation';

type AuthSearchParams = Record<string, string | string[] | undefined>;

function buildAuthRedirectUrl(mode: 'login' | 'register', searchParams: AuthSearchParams) {
  const query = new URLSearchParams();
  query.set('mode', mode);

  for (const [key, value] of Object.entries(searchParams)) {
    if (key === 'mode' || value === undefined) {
      continue;
    }

    if (Array.isArray(value)) {
      for (const item of value) {
        query.append(key, item);
      }
      continue;
    }

    query.set(key, value);
  }

  return `/auth?${query.toString()}`;
}

export default async function LoginPage({
  searchParams,
}: {
  searchParams: Promise<AuthSearchParams>;
}) {
  redirect(buildAuthRedirectUrl('login', await searchParams));
}
