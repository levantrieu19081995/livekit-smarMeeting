import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { ConclusionResultDisplay } from './MinutesAiConclusion';
import type { ConclusionResult } from '@/types/management/conclusion';

const result: ConclusionResult = {
  summary: 'Tổng hợp từ biên bản',
  decisions: [{ content: 'Thống nhất kế hoạch', sourceRefs: ['minutes.v2'] }],
  pollConclusions: [{ content: 'Tán thành: 2 phiếu', sourceRefs: ['poll.3'] }],
  actionItems: [
    { task: 'Chuẩn bị tài liệu', assignee: null, deadline: null, sourceRefs: ['qa.4', 'chat.5'] },
  ],
  unresolvedIssues: [{ content: 'Chưa thống nhất ngân sách', sourceRefs: ['manual.pre-ai'] }],
};

describe('Conclusion result and provenance display', () => {
  it('renders all structured groups and human-readable source references', () => {
    const html = renderToStaticMarkup(<ConclusionResultDisplay result={result} />);
    for (const label of [
      'Tổng hợp',
      'Quyết định',
      'Kết quả biểu quyết',
      'Công việc cần thực hiện',
      'Vấn đề chưa thống nhất',
      'Biên bản V2',
      'Biểu quyết #3',
      'Hỏi đáp #4',
      'Trao đổi #5',
      'Kết luận đã nhập',
    ])
      expect(html).toContain(label);
  });

  it('makes absent assignments and deadlines explicit without inventing them', () => {
    const html = renderToStaticMarkup(<ConclusionResultDisplay result={result} />);
    expect(html).toContain('Chưa được chỉ định');
    expect(html).toContain('Chưa xác định');
  });

  it('escapes source content as plain text and omits empty result groups', () => {
    const html = renderToStaticMarkup(
      <ConclusionResultDisplay
        result={{
          ...result,
          summary: '<img src=x onerror=alert(1)>',
          decisions: [],
          pollConclusions: [],
          actionItems: [],
          unresolvedIssues: [],
        }}
      />,
    );
    expect(html).not.toContain('<img');
    expect(html).toContain('&lt;img');
    expect(html).not.toContain('Quyết định');
    expect(html).not.toContain('Công việc cần thực hiện');
  });
});
