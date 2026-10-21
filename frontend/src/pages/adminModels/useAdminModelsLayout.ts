// ============================================================
// frontend/src/pages/adminModels/useAdminModelsLayout.ts
// ============================================================
import { useLayoutEffect, useRef, useState } from 'react'
import type { OrganizationModelPolicy, RuntimeModelStatus } from '../../api/modelApi'

export function useAdminModelsLayout(
    runtime: RuntimeModelStatus | null,
    policy: OrganizationModelPolicy | null,
) {
    const heroRef =
        useRef<HTMLElement | null>(null)

    const [
        catalogMinUpperHeight,
        setCatalogMinUpperHeight,
    ] = useState(120)

    useLayoutEffect(() => {
        const hero = heroRef.current

        if (!hero) {
            return
        }

        const updateMinimumUpperHeight = () => {
            const parent = hero.parentElement
            const parentStyles = parent
                ? window.getComputedStyle(parent)
                : null
            const rawGap = parentStyles
                ? parentStyles.rowGap
                    || parentStyles.gap
                : ''
            const parsedGap =
                Number.parseFloat(rawGap)
            const gap =
                Number.isFinite(parsedGap)
                    ? parsedGap
                    : 12
            const heroHeight =
                hero.getBoundingClientRect().height

            setCatalogMinUpperHeight(
                Math.max(
                    96,
                    Math.ceil(
                        heroHeight + gap,
                    ),
                ),
            )
        }

        updateMinimumUpperHeight()

        if (
            typeof ResizeObserver
                === 'undefined'
        ) {
            window.addEventListener(
                'resize',
                updateMinimumUpperHeight,
            )

            return () => {
                window.removeEventListener(
                    'resize',
                    updateMinimumUpperHeight,
                )
            }
        }

        const observer =
            new ResizeObserver(
                updateMinimumUpperHeight,
            )

        observer.observe(hero)

        return () => {
            observer.disconnect()
        }
    }, [runtime, policy])

    return { heroRef, catalogMinUpperHeight }
}
