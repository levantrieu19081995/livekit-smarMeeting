import { afterEach, describe, expect, it, vi } from 'vitest';
import { saveQnaReportFile } from './qnaReportDownload';

const file = { blob: new Blob(['\uFEFFNội dung,Trạng thái']), filename: 'meeting-7-qa.csv' };

function environment() {
  const anchor = { href: '', download: '', click: vi.fn(), remove: vi.fn() };
  const appendChild = vi.fn();
  const createObjectURL = vi.fn(() => 'blob:fixture-report');
  const revokeObjectURL = vi.fn();
  vi.stubGlobal('document', { createElement: vi.fn(() => anchor), body: { appendChild } });
  vi.stubGlobal('URL', { createObjectURL, revokeObjectURL });
  return { anchor, appendChild, createObjectURL, revokeObjectURL };
}

describe('Q&A report download lifecycle', () => {
  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  it('downloads the server blob with a safe filename and cleans up its temporary URL/anchor', () => {
    vi.useFakeTimers();
    const env = environment();
    expect(saveQnaReportFile(file, () => true)).toBe(true);
    expect(env.createObjectURL).toHaveBeenCalledWith(file.blob);
    expect(env.anchor.download).toBe('meeting-7-qa.csv');
    expect(env.appendChild).toHaveBeenCalledWith(env.anchor);
    expect(env.anchor.click).toHaveBeenCalledOnce();
    expect(env.anchor.remove).toHaveBeenCalledOnce();
    vi.advanceTimersByTime(1000);
    expect(env.revokeObjectURL).toHaveBeenCalledWith('blob:fixture-report');
  });

  it('keeps a successful object URL alive long enough for the browser to consume it', () => {
    vi.useFakeTimers();
    const env = environment();
    expect(saveQnaReportFile(file, () => true)).toBe(true);
    expect(env.anchor.remove).toHaveBeenCalledOnce();
    vi.advanceTimersByTime(999);
    expect(env.revokeObjectURL).not.toHaveBeenCalled();
    vi.advanceTimersByTime(1);
    expect(env.revokeObjectURL).toHaveBeenCalledOnce();
  });

  it('does not allocate or download after unmount, abort or permission loss', () => {
    const env = environment();
    expect(saveQnaReportFile(file, () => false)).toBe(false);
    expect(env.createObjectURL).not.toHaveBeenCalled();
    expect(env.anchor.click).not.toHaveBeenCalled();
  });

  it('rechecks permission immediately before clicking and cleans up if it changed', () => {
    const env = environment();
    const permitted = vi.fn().mockReturnValueOnce(true).mockReturnValueOnce(false);
    expect(saveQnaReportFile(file, permitted)).toBe(false);
    expect(env.anchor.click).not.toHaveBeenCalled();
    expect(env.anchor.remove).toHaveBeenCalledOnce();
    expect(env.revokeObjectURL).toHaveBeenCalledOnce();
  });

  it('releases resources even if the browser download action fails', () => {
    const env = environment();
    env.anchor.click.mockImplementation(() => {
      throw new Error('Download failed');
    });
    expect(() => saveQnaReportFile(file, () => true)).toThrow('Download failed');
    expect(env.anchor.remove).toHaveBeenCalledOnce();
    expect(env.revokeObjectURL).toHaveBeenCalledOnce();
  });
});
