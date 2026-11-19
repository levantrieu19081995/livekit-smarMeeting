import { readdir } from 'node:fs/promises';
import path from 'node:path';
import { NextResponse } from 'next/server';

type HelpFileType = 'pdf' | 'docx';

type HelpFile = {
  name: string;
  type: HelpFileType;
  url: string;
};

function getHelpFileType(filename: string): HelpFileType | null {
  const extension = path.extname(filename).toLowerCase();

  if (extension === '.pdf') {
    return 'pdf';
  }

  if (extension === '.docx') {
    return 'docx';
  }

  return null;
}

export async function GET() {
  const helpDirectory = path.join(process.cwd(), 'public', 'help');

  try {
    const entries = await readdir(helpDirectory, { withFileTypes: true });
    const files: HelpFile[] = entries
      .filter((entry) => entry.isFile())
      .map((entry) => {
        const type = getHelpFileType(entry.name);

        if (!type) {
          return null;
        }

        return {
          name: entry.name,
          type,
          url: `/help/${encodeURIComponent(entry.name)}`,
        };
      })
      .filter((file): file is HelpFile => file !== null)
      .sort((left, right) => left.name.localeCompare(right.name, 'vi', { sensitivity: 'base' }));

    return NextResponse.json({ files });
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code === 'ENOENT') {
      return NextResponse.json({ files: [] });
    }

    throw error;
  }
}
