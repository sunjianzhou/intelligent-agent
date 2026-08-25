import { describe, expect, it } from 'vitest'
import { citationKey, appendCitation } from '@/utils/citations'

describe('citations（R-05 知识问答引用去重）', () => {
  it('按 file_id#chunk_index 去重追加', () => {
    const c1 = { file_id: 'f1', chunk_index: 2, label: '手册.md#段落2' }
    const c2 = { file_id: 'f1', chunk_index: 3, label: '手册.md#段落3' }

    const list = appendCitation(appendCitation([c1], c1), c2)

    expect(list).toHaveLength(2)
    expect(citationKey(c1)).toBe('f1#2')
  })

  it('无 file_id 的引用被忽略', () => {
    expect(appendCitation([], { label: 'x' })).toHaveLength(0)
    expect(appendCitation(undefined, { file_id: 'f1', chunk_index: 0 })).toHaveLength(1)
  })
})
