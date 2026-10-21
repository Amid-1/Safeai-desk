// frontend/src/features/models/ui/catalogVersion/catalogLabels.ts
// ============================================================
// frontend/src/features/models/ui/catalogVersion/catalogLabels.ts
// ============================================================
import type { ModelLifecycle, ModelCapability, ModelModality, ModelRetentionStatus, ModelTrainingUseStatus, ModelPricingStatus } from '../../../../entities/model/api/modelApi'
import { MODEL_MODALITIES } from '../../../../entities/model/api/modelApi'

export const OUTPUT_MODALITIES =
    MODEL_MODALITIES.filter(
        (value) => value !== 'IMAGE',
    )

export const LIFECYCLE_LABELS: Record<
    ModelLifecycle,
    string
> = {
    ACTIVE: 'Активна',
    DEPRECATED: 'Устаревает',
    DISABLED: 'Отключена',
    RETIRED: 'Выведена из эксплуатации',
}

export const CAPABILITY_LABELS: Record<
    ModelCapability,
    string
> = {
    TOOLS: 'Инструменты',
    VISION: 'Работа с изображениями',
    STRUCTURED_OUTPUT:
        'Структурированный ответ',
}

export const MODALITY_LABELS: Record<
    ModelModality,
    string
> = {
    TEXT: 'Текст',
    IMAGE: 'Изображения',
    AUDIO: 'Аудио',
}

export const RETENTION_LABELS: Record<
    ModelRetentionStatus,
    string
> = {
    NOT_DECLARED: 'Не указано',
    STANDARD: 'Стандартное хранение',
    ZERO_DATA_RETENTION:
        'Данные не сохраняются',
    CUSTOM: 'Особые условия',
}

export const TRAINING_LABELS: Record<
    ModelTrainingUseStatus,
    string
> = {
    NOT_DECLARED: 'Не указано',
    NOT_USED: 'Не используется для обучения',
    MAY_BE_USED: 'Может использоваться для обучения',
    CONTRACTUAL_NO_TRAINING:
        'Обучение запрещено договором',
}

export const PRICING_LABELS: Record<
    ModelPricingStatus,
    string
> = {
    UNPRICED: 'Стоимость не указана',
    FREE: 'Бесплатно',
    CONFIGURED: 'Стоимость настроена',
    INCOMPLETE: 'Стоимость заполнена не полностью',
}
