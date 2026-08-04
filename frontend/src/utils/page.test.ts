import {
    describe,
    expect,
    it,
} from 'vitest'
import {
    normalizePageResponse,
    pageFromArray,
} from './page'

describe('strict PageResponse', () => {
    it('сохраняет обязательные metadata', () => {
        const page =
            normalizePageResponse({
                content: ['a'],
                page: 1,
                size: 50,
                totalElements: 51,
                totalPages: 2,
            })

        expect(page.page).toBe(1)
        expect(page.totalPages)
            .toBe(2)
    })

    it('не превращает content=null в пустую страницу', () => {
        expect(() =>
            normalizePageResponse(
                {
                    content: null,
                    page: 0,
                    size: 50,
                    totalElements: 0,
                    totalPages: 0,
                } as never,
            ),
        ).toThrow(
            'Некорректный PageResponse',
        )
    })

    it('array преобразуется только явно', () => {
        expect(
            pageFromArray(['a', 'b']),
        ).toEqual({
            content: ['a', 'b'],
            page: 0,
            size: 2,
            totalElements: 2,
            totalPages: 1,
        })
    })
})
