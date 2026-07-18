// frontend/src/test/setup.ts
import '@testing-library/jest-dom/vitest'
import { cleanup } from '@testing-library/react'
import { afterEach } from 'vitest'

afterEach(() => {
    cleanup()

    document.cookie = 'XSRF-TOKEN=; Max-Age=0; Path=/'
    document.body.innerHTML = ''
})

