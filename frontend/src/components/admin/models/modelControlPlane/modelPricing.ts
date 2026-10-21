// frontend/src/components/admin/models/modelControlPlane/modelPricing.ts
import type { ModelPricingStatus } from '../../../../api/modelApi'


export function validatePricingDraft(
    status: ModelPricingStatus,
    complete: boolean,
    input: string | null,
    cachedInput: string | null,
    cacheWrite: string | null,
    output: string | null,
    pricingVersion: string | null,
    extraPricingJson: string,
): void {
    if (status === 'UNPRICED') {
        if (
            complete
            || input !== null
            || cachedInput !== null
            || cacheWrite !== null
            || output !== null
            || extraPricingJson !== '{}'
        ) {
            throw new Error(
                'При статусе «Стоимость не указана» ценовые поля должны быть пустыми.',
            )
        }
        return
    }

    if (status === 'FREE') {
        if (
            !complete
            || input === null
            || decimalToScale12(input) !== 0n
            || output === null
            || decimalToScale12(output) !== 0n
            || !isZeroOrNull(cachedInput)
            || !isZeroOrNull(cacheWrite)
            || extraPricingJson !== '{}'
        ) {
            throw new Error(
                'Для бесплатной модели все поля стоимости должны быть нулевыми.',
            )
        }
        return
    }

    if (status === 'CONFIGURED') {
        if (
            !complete
            || input === null
            || output === null
            || !pricingVersion
            || extraPricingJson !== '{}'
        ) {
            throw new Error(
                'Для настроенной стоимости укажите цену входа, цену выхода и версию тарифа; дополнительные JSON-параметры должны быть пустыми.',
            )
        }

        // Общий каталог не навязывает провайдеру соотношение
        // между ценой обычного и кэшированного ввода.

        return
    }

    if (complete) {
        throw new Error(
            'Неполная тарификация не может быть отмечена как полностью известная.',
        )
    }
}

export function compareDecimalStrings(
    left: string,
    right: string,
): number {
    const leftScaled =
        decimalToScale12(left)
    const rightScaled =
        decimalToScale12(right)

    if (leftScaled === rightScaled) {
        return 0
    }

    return leftScaled < rightScaled
        ? -1
        : 1
}

export function decimalToScale12(
    value: string,
): bigint {
    const [integerPart, fractionPart = ''] =
        value.split('.')

    const fraction =
        fractionPart.padEnd(12, '0')

    return (
        BigInt(integerPart)
        * 1_000_000_000_000n
    ) + BigInt(fraction || '0')
}

export function isZeroOrNull(
    value: string | null,
): boolean {
    return value === null
        || decimalToScale12(value) === 0n
}
